package `in`.doosripari.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.datetime.Instant

/**
 * Plan section 6.10, Health ("Swasthya").
 *
 * The repository interface lives beside the feature rather than in
 * `in.doosripari.data`, where the check-in repository lives, because this
 * package was built without being allowed to add files to `data/`. The
 * interface is the same contract either way; moving it later is a file move
 * and an import change, not a design change.
 *
 * Everything here is *recorded and shown*. Nothing in this file reads a
 * reading, names a condition, or proposes a dose change — see
 * [VitalsLimits] for where the one judgement this module is allowed to make
 * lives, and why it stops there.
 */

// ------------------------------------------------------------------- consent --

/**
 * The two consents of plan section 14 that this module depends on.
 *
 * They are separate, and the separation is the point:
 *
 *  * [healthData] is the pensioner's DPDP consent to their own health records
 *    being kept and synchronised at all. Off means nothing is recorded.
 *  * [guardianSharing] is a *second*, distinct consent to a named family
 *    member being shown those records. Off means the records exist but nobody
 *    else can see them.
 *
 * Turning either off has to actually stop the thing — cancel the alarms, drop
 * the queued syncs, revoke whatever token the guardian device was holding —
 * and not merely set a flag the next read ignores. [withdrawHealthConsent]
 * and [withdrawGuardianSharing] both return a [ConsentWithdrawal] so the
 * screen can tell the user exactly what stopped, rather than asking them to
 * trust a toggle.
 */
data class HealthConsents(
    val healthData: Boolean = false,
    val guardianSharing: Boolean = false,
)

/**
 * What a withdrawal actually did.
 *
 * The list is written in plain sentences because it goes straight onto the
 * screen. "Nothing else changes" is the sentence a person is actually asking
 * for when they turn something off, and it is worth saying out loud.
 */
data class ConsentWithdrawal(
    val stopped: List<String>,
    val cancelledReminderCount: Int = 0,
    val revokedGuardianDeviceAccess: Boolean = false,
)

// ------------------------------------------------------------------- doses --

/**
 * What a person *recorded* about a dose.
 *
 * There is deliberately no `NotTaken`. A person who has not swallowed the
 * tablet has usually forgotten, or been unable to reach it, or been taken ill.
 * None of those is a decision, and a button that records them as a decision is
 * how an adherence figure quietly turns into a judgement about somebody.
 *
 * [Skipped] exists only because a person pressed "I am not taking this one",
 * which is a real, deliberate act and should be recorded as one.
 */
enum class DoseMark { None, Taken, Skipped, Later }

/**
 * What the screen shows for a dose. Superset of [DoseMark].
 *
 * [Missed] is **derived, never stored and never chosen**. See
 * `DoseSlot.statusAt`.
 */
enum class DoseStatus { Pending, Taken, Skipped, Later, Missed }

/**
 * One scheduled dose of one medicine.
 *
 * A slot is created by the schedule, not by the user, so there is always a row
 * for "8:00, nothing happened" — which is the row the whole module exists to
 * make visible.
 */
data class DoseSlot(
    val id: String,
    val medicineId: String,
    val medicineName: String,
    val doseLabel: String,
    val dueAt: Instant,
    val mark: DoseMark,
    val markedAt: Instant? = null,
    /** How long [DoseMark.Later] keeps the dose open. Defaults to 15 minutes. */
    val laterGrace: kotlinx.datetime.Duration = LATER_GRACE,
) {
    companion object {
        /** Plan section 6.10: "Later (15 min)". */
        val LATER_GRACE: kotlinx.datetime.Duration =
            kotlinx.datetime.Duration.parse("15m")
    }
}

/**
 * The heart of the module, and the reason the premise of the product holds.
 *
 * A dose that was never acknowledged becomes **MISSED**. The absence of a tap
 * is itself the event. Nobody in the plan's user base wakes up deciding not to
 * take their tablets; they run out of a strip, or they forget, or they are in
 * hospital and it is somebody else's business that day. Reading the silence as
 * "skipped" would turn a memory lapse into a refusal on the record, and it is
 * exactly the failure this design exists to prevent — see the comment at the
 * render site in `DoseLogScreen.kt`.
 *
 * Nothing here writes to the database. It is a pure function of the slot, the
 * person's mark and the clock, so a test can pin [now] and assert that a dose
 * nobody touched reads as MISSED.
 */
fun DoseSlot.statusAt(now: Instant): DoseStatus = when (mark) {
    DoseMark.Taken -> DoseStatus.Taken
    DoseMark.Skipped -> DoseStatus.Skipped
    DoseMark.Later ->
        if (now < dueAt + laterGrace) DoseStatus.Later else DoseStatus.Missed
    DoseMark.None ->
        // The window is the grace: 30 minutes after the scheduled time, a dose
        // with no acknowledgement is missed. It is long enough for a person
        // fetching water or answering the door, and short enough that a real
        // problem surfaces the same morning.
        if (now > dueAt + MISSED_GRACE) DoseStatus.Missed else DoseStatus.Pending
}

/** How long after the scheduled time a dose stays Pending before it is missed. */
val MISSED_GRACE: kotlinx.datetime.Duration = kotlinx.datetime.Duration.parse("30m")

/**
 * How many doses in a row were missed, and whether that has reached the point
 * at which a guardian is told.
 *
 * Two in a row is the plan's number (section 6.10: "the guardian is told after
 * 2 misses in a row"). [guardianWillBeTold] is deliberately false for a
 * medicine the pensioner has not chosen to share — see [Medicine.shareWithGuardian].
 */
data class AdherenceSummary(
    val missedInARow: Int = 0,
    val guardianWillBeTold: Boolean = false,
    val guardianToldAt: Instant? = null,
) {
    val hasMisses: Boolean get() = missedInARow > 0
}

// --------------------------------------------------------------- medicines --

enum class FoodTiming { BeforeFood, AfterFood, WithFood, Anytime }

data class Medicine(
    val id: String,
    val name: String,
    val doseLabel: String,
    /** Local "HH:mm" times of day, ascending. */
    val times: List<String>,
    /** ISO day of week, 1 = Monday. Empty means every day. */
    val days: Set<Int> = emptySet(),
    val withFood: FoodTiming = FoodTiming.WithFood,
    /** Remaining days of stock, or null when the user has not counted. */
    val stockDaysLeft: Int? = null,
    /**
     * A photo of the strip, taken on the phone. Never uploaded — see the
     * document-safe boundary comment in `feature/me/MeRepository.kt`; a strip
     * photo carries the person's name and their prescription.
     */
    val photoOnDeviceUri: String? = null,
    /**
     * The per-medicine share control.
     *
     * The difference between family support and family surveillance is exactly
     * this one toggle. Sharing a blood-pressure tablet list with a daughter in
     * Pune is how a person who has fallen is found. Sharing it without being
     * asked is how a person who has simply stopped bothering stops answering
     * the phone. Both look identical from the outside — the parent's app either
     * reports the misses or it does not — so the default is off and the toggle
     * is per medicine, not a single global switch for "the family can see my
     * health", which is a much larger thing to hand over by accident.
     */
    val shareWithGuardian: Boolean = false,
) {
    /** Plan section 6.10: a refill alert at five days of stock. */
    val needsRefillSoon: Boolean get() = stockDaysLeft != null && stockDaysLeft <= REFILL_ALERT_DAYS

    val dosesPerDay: Int get() = times.size

    companion object {
        const val REFILL_ALERT_DAYS = 5
    }
}

// ------------------------------------------------------------------ vitals --

enum class VitalKind(val label: String) {
    BloodPressure("Blood pressure"),
    Sugar("Sugar"),
    Weight("Weight"),
    Pulse("Pulse"),
}

data class VitalEntry(
    val id: String,
    val kind: VitalKind,
    /** Systolic / diastolic for blood pressure, otherwise null. */
    val first: Double,
    val second: Double? = null,
    val unit: String,
    val at: Instant,
    /** Set when the value fell outside [VitalsLimits]. Never set for "low" or "high". */
    val flaggedForDoctor: Boolean = false,
) {
    fun displayValue(): String =
        if (second != null) "$first / $second $unit" else "$first $unit"
}

/**
 * The only judgement this module is allowed to make, and it is deliberately a
 * small one: **is this number even plausible?**
 *
 * These bounds are typing guards, not clinical ranges. They catch a swapped
 * pair of digits, a sugar typed as 18.5, a weight entered in pounds, a
 * keyboard that dropped a decimal point. Nothing here knows what a healthy
 * blood pressure is, and nothing here compares a reading with the previous one
 * — that is where a diagnosis creeps in, and a wrong one from a pensioner's
 * phone is a doctor visit, a panic, or a stopped medicine, none of which this
 * app has the standing to cause.
 *
 * When a value falls outside, the screen says "check this with your doctor" and
 * stops. See the comment at the render site in `VitalsScreen.kt`.
 */
object VitalsLimits {

    /** Plausibility only. Not a normal range, not a target, not advice. */
    fun plausibleRange(kind: VitalKind): ClosedFloatingPointRange<Double> = when (kind) {
        // Below 50/30 a person is not typing their own blood pressure; above
        // 300/200 it is not either.
        VitalKind.BloodPressure -> 30.0..300.0
        VitalKind.Sugar -> 20.0..600.0
        VitalKind.Weight -> 20.0..250.0
        VitalKind.Pulse -> 20.0..250.0
    }

    fun isPlausible(kind: VitalKind, value: Double): Boolean =
        value.isFinite() && value in plausibleRange(kind)

    fun firstIsPlausible(entry: VitalEntry) = isPlausible(entry.kind, entry.first)

    fun secondIsPlausible(entry: VitalEntry): Boolean =
        entry.second == null || isPlausible(entry.kind, entry.second)
}

// --------------------------------------------------------------- reminders --

enum class HealthReminderKind { Refill, CghsRenewal, EchRenewal, Checkup, Reading }

data class HealthReminder(
    val id: String,
    val kind: HealthReminderKind,
    val title: String,
    val at: Instant,
    val done: Boolean = false,
)

// --------------------------------------------------------------- facilities --

enum class FacilityKind { Cghs, Ech, Dispensary, Laboratory, Hospital }

data class Facility(
    val id: String,
    val name: String,
    val kind: FacilityKind,
    val address: String,
    val phone: String?,
    /** Plan section 6.10: "last updated", from official lists refreshed monthly. */
    val lastUpdated: String,
    val distanceKm: Double? = null,
)

// ------------------------------------------------------------------ errors --

enum class HealthError { Offline, Failed }

// ------------------------------------------------------------- repository --

interface HealthRepository {

    suspend fun consents(): HealthConsents

    suspend fun grantHealthConsent(): HealthConsents

    /**
     * Stops health-data consent for real: cancels every scheduled alarm and
     * worker, deletes queued syncs and revokes any device token a guardian
     * device was holding. Returns what stopped, so the screen can say so.
     */
    suspend fun withdrawHealthConsent(): ConsentWithdrawal

    /** The same, for guardian sharing alone. Health records are kept. */
    suspend fun withdrawGuardianSharing(): ConsentWithdrawal

    suspend fun medicines(): List<Medicine>

    suspend fun medicine(id: String): Medicine?

    suspend fun saveMedicine(medicine: Medicine): Medicine

    suspend fun deleteMedicine(id: String)

    /** The per-medicine share toggle. This is the whole of the family link. */
    suspend fun setMedicineShared(id: String, shared: Boolean): Medicine

    /** Doses for one local day, "YYYY-MM-DD", ascending by time. */
    suspend fun doseLog(day: String): List<DoseSlot>

    /** Record what a person did about one dose. Never called with "missed". */
    suspend fun markDose(slotId: String, mark: DoseMark): DoseSlot

    suspend fun vitals(kind: VitalKind, days: Int = 30): List<VitalEntry>

    suspend fun addVital(entry: VitalEntry): VitalEntry

    suspend fun reminders(): List<HealthReminder>

    suspend fun setReminderDone(id: String, done: Boolean): HealthReminder

    /**
     * Create a reminder the pensioner asked for (the "Bolo" reminder intent,
     * plan section 5). [HealthReminder.id] is empty on the way in and filled by
     * the store on the way out.
     */
    suspend fun saveReminder(reminder: HealthReminder): HealthReminder

    suspend fun deleteReminder(id: String)

    suspend fun facilities(kind: FacilityKind?, query: String?): List<Facility>
}

/**
 * Builds a [ViewModelProvider.Factory] for the Routes in this package.
 *
 * This is a small copy of the factory MainActivity builds for `CheckInRoute`.
 * It is duplicated in all three feature packages rather than shared because a
 * shared helper would have to live in `ui/` or `data/`, which nobody may edit
 * while three agents work at once.
 */
internal inline fun <reified T : ViewModel> healthViewModelFactory(
    crossinline create: () -> T,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
