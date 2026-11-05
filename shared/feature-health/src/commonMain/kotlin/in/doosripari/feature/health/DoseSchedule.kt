package `in`.doosripari.feature.health

import `in`.doosripari.core.model.DoseDay
import `in`.doosripari.core.model.DoseLog
import `in`.doosripari.core.model.DoseStatus
import `in`.doosripari.core.model.Medicine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

/**
 * Building a [DoseDay] for any date from a user's medicines. Plan section 6.10.
 *
 * ## The rule this file is shaped by
 *
 * A dose is scheduled on the phone AND on the server, and neither is allowed to
 * be the only copy. The phone's copy is what fires when the network is gone; the
 * server's copy is what lets a guardian be told after two misses. So this file
 * does not "the schedule" — it **merges two schedules** and records, for every
 * single row, which copies existed. That is what [PlannedDose.origin] is, and it
 * is not bookkeeping for its own sake: a row only counts towards a guardian
 * alert when at least one copy that could have reminded the user actually
 * existed. Blaming a pensioner for a reminder the phone never got a chance to
 * send is the single worst thing this module could do.
 *
 * ## The awkward cases, all of them handled below rather than left to the caller
 *
 *  - a medicine added mid-day, when its first time of day has already gone by
 *  - a time that has already passed today
 *  - a dose the user deliberately skipped
 *  - a medicine with `isActive = false`
 *
 * ## One defect in core-model, worked around rather than patched
 *
 * `DoseDay.isComplete` is `taken + missed == doses.size`, which never becomes
 * true when a dose is [DoseStatus.Skipped] — a skipped dose is counted by
 * neither term, so a day with one deliberately skipped dose reads as unfinished
 * forever. That is the opposite of what this module's central rule says, so
 * [DoseDaySummary] counts for itself. core-model is not edited from here; this
 * is raised as a finding.
 */
object DoseSchedule {

    /**
     * The whole day, merged, for [date] in [timeZone].
     *
     * @param now the current instant, or null when there is no clock (a
     *   month view of a past month, for instance). Without it, "has this slot
     *   already passed" cannot be answered and the past-slots rule below falls
     *   back to treating everything as still open, which is the safe direction:
     *   an un-taken dose is shown rather than silently marked missed.
     * @param serverLogs the rows the server holds for [date]. Where one matches a
     *   generated row — same medicine, same instant — the server's status wins,
     *   because the server is the copy that reached the phone last time.
     * @param windows when a medicine is not lifelong. core-model's `Medicine`
     *   has no start or end date (see the report), so a course of tablets that
     *   began today is expressed here rather than invented in the data layer.
     */
    fun dayFor(
        date: LocalDate,
        medicines: List<Medicine>,
        timeZone: TimeZone,
        now: Instant? = null,
        serverLogs: List<DoseLog> = emptyList(),
        windows: Map<Int, DoseWindow> = emptyMap(),
    ): DoseDayPlan {
        val planned = ArrayList<PlannedDose>(medicines.size)
        val omitted = ArrayList<OmittedDose>()

        for (medicine in medicines) {
            // A medicine the user has switched off produces nothing at all —
            // not a row, not a miss, not a place on the Today card. Its doses
            // were never going to happen and the day must not carry a ghost of
            // them, because a ghost here becomes a guardian alert.
            if (!medicine.isActive) {
                omitted += medicine.times.forEach { OmittedDose(medicine, it, DoseNote.InactiveMedicine) }
                continue
            }

            // Times can be empty if the user saved a name and came back to the
            // times later. That is a half-finished form, not a schedule.
            if (medicine.times.isEmpty()) {
                omitted += OmittedDose(medicine, null, DoseNote.NoTimesRecorded)
                continue
            }

            val window = windows[medicine.id]
            if (window != null && !window.covers(date)) {
                omitted += medicine.times.forEach { OmittedDose(medicine, it, DoseNote.OutsideScheduleWindow) }
                continue
            }

            for (slot in medicine.times) {
                val dueAt = LocalDateTime(date, slot).toInstant(timeZone)
                val server = serverLogs.firstOrNull {
                    it.medicineId == medicine.id && it.dueAt == dueAt
                }
                planned += PlannedDose(
                    log = server ?: DoseLog(
                        id = localDoseId(medicine.id, slot, date),
                        medicineId = medicine.id,
                        medicineName = medicine.name,
                        dueAt = dueAt,
                        status = DoseStatus.Missed,
                    ),
                    medicine = medicine,
                    slot = slot,
                    origin = when {
                        server != null -> DoseOrigin.BothCopies
                        else -> DoseOrigin.PhoneOnly
                    },
                    note = when {
                        server != null -> DoseNote.ServerCopyMerged
                        window?.firstDoseOn == date && hasPassed(dueAt, now) -> DoseNote.AddedAfterTheSlotPassed
                        else -> null
                    },
                )
            }
        }

        val ordered = planned.sortedWith(compareBy({ it.log.dueAt }, { it.medicine.id }))
        return DoseDayPlan(
            date = date,
            doses = ordered,
            omitted = omitted,
            medicines = medicines.associateBy { it.id },
        )
    }

    /**
     * Carry an untaken dose to the same time tomorrow.
     *
     * This is the "start again tomorrow" action on a dose the user did not get
     * to, and it is *not* a snooze: [DoseTracker.snooze] moves a dose fifteen
     * minutes and leaves it open, whereas this settles today's row and leaves
     * today's row missed. A dose is never quietly turned into a hit by being
     * rescheduled, and a guardian who was owed an alert about it still gets one.
     */
    fun rollToNextDay(log: DoseLog, timeZone: TimeZone): DoseLog {
        val local = log.dueAt.toLocalDateTime(timeZone)
        return log.copy(
            dueAt = LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), local.time).toInstant(timeZone),
            status = DoseStatus.Missed,
            takenAt = null,
        )
    }

    /**
     * Whether [date] is in the past in the user's own timezone. A future date
     * produces rows — so the week strip shows them — but none of them can be
     * late, and this is what tells the screens the difference.
     */
    fun isPast(date: LocalDate, now: Instant, timeZone: TimeZone): Boolean =
        date < now.toLocalDateTime(timeZone).date

    // ------------------------------------------------------------------ internals --

    /**
     * A slot belongs to the date it was written down for, always — so a time
     * already gone by is a dose of *today* that nobody got to, and it stays on
     * today. Rolling it forward would quietly turn a miss into a hit, which is
     * the one thing this module must never do.
     */
    private fun hasPassed(dueAt: Instant, now: Instant?): Boolean =
        now != null && now >= dueAt

    /**
     * A stable, always-negative id for a row that exists only on this phone.
     *
     * Two reasons it is negative and hashed rather than counted:
     *  - negative means "not from the server". The server's `dose_logs.id` is an
     *    autoincrement and always positive, so the merge can never mistake a
     *    local row for a confirmed one and vice versa;
     *  - hashed off (medicine, slot, date) means the same slot on the same day
     *    always produces the same id, so a row generated twice — once on the
     *    Today screen and once by the reminder scheduler — reconciles instead of
     *    duplicating.
     */
    private fun localDoseId(medicineId: Int, slot: LocalTime, date: LocalDate): Int {
        var h = 17
        h = h * 31 + medicineId
        h = h * 31 + slot.hour
        h = h * 31 + slot.minute
        h = h * 31 + date.year
        h = h * 31 + date.monthNumber
        h = h * 31 + date.dayOfMonth
        return -(h and 0x0FFFFFFF) - 1
    }
}

/**
 * The stretch of dates a medicine is actually being taken over.
 *
 * core-model's `Medicine` has `isActive` but no dates, so a twenty-day course
 * that began this morning cannot say so. This carries that, and nothing else.
 */
data class DoseWindow(
    /** The first date a dose is due. Null means "since whenever it was added". */
    val firstDoseOn: LocalDate? = null,
    /** The last date a dose is due, inclusive. Null means indefinitely. */
    val lastDoseOn: LocalDate? = null,
) {
    fun covers(date: LocalDate): Boolean {
        if (firstDoseOn != null && date < firstDoseOn) return false
        if (lastDoseOn != null && date > lastDoseOn) return false
        return true
    }
}

/**
 * Which copies of a dose exist. Plan section 6.10's central rule, in one enum.
 *
 * [PhoneOnly] is the one that matters. A row only this device knows about could
 * not have been reminded about by anyone else, so if it goes un-taken it is
 * recorded honestly and shown to the user, but it does **not** move the guardian
 * counter. Nobody should be telephoned because a reminder that was never sent
 * went unanswered.
 */
enum class DoseOrigin(val code: String) {
    /** Generated on this device; the server has not confirmed it. */
    PhoneOnly("phone_only"),

    /** The server holds the row too, so its reminder would have fired. */
    ServerConfirmed("server_confirmed"),

    /** Both copies exist and have been reconciled into one row. */
    BothCopies("both_copies");

    /**
     * Whether a miss on this row may be counted towards a guardian alert.
     *
     * [BothCopies] and [ServerConfirmed] both mean a copy existed that was
     * capable of reminding the user. [PhoneOnly] does not.
     */
    val canCountAsRealMiss: Boolean get() = this != PhoneOnly

    companion object {
        fun fromCode(code: String?): DoseOrigin =
            entries.firstOrNull { it.code == code } ?: PhoneOnly
    }
}

/**
 * Something worth knowing about a dose, or about a slot that was not scheduled.
 *
 * These are typed rather than free text so a screen can react: a slot omitted
 * for [AddedAfterTheSlotPassed] gets no line at all, because there is nothing
 * for the user to do about a dose that never had a chance to fire, while a slot
 * omitted for [OutsideScheduleWindow] gets a quiet "course finished" note.
 */
enum class DoseNote(val code: String) {
    /**
     * The medicine was added today after its own time of day had already gone.
     *
     * The row is still created and is still visible — the user did put the
     * tablets in front of them — but it is marked so the guardian counter
     * ignores it. This is the mid-day case, and guessing that the user "missed"
     * a dose that was never on their phone would be the most unfair thing this
     * module could do.
     */
    AddedAfterTheSlotPassed("added_after_the_slot_passed"),

    /** `isActive = false`. No row, no miss, no reminder. */
    InactiveMedicine("inactive_medicine"),

    /** The medicine was saved with a name but no times. */
    NoTimesRecorded("no_times_recorded"),

    /** The date falls outside the medicine's course. */
    OutsideScheduleWindow("outside_schedule_window"),

    /** The server's copy was found and its status taken as authoritative. */
    ServerCopyMerged("server_copy_merged"),

    /** Pushed back by the user. "Later (15 min)", plan section 6.10. */
    Snoozed("snoozed");

    /** Whether a screen should say anything at all about this. */
    val isWorthTelling: Boolean get() = this != ServerCopyMerged

    companion object {
        fun fromCode(code: String?): DoseNote? = entries.firstOrNull { it.code == code }
    }
}

/** One scheduled dose, with the medicine it came from and the copies that exist. */
data class PlannedDose(
    val log: DoseLog,
    val medicine: Medicine,
    val slot: LocalTime,
    val origin: DoseOrigin,
    val note: DoseNote? = null,
) {
    /** "After food" is a property of the medicine, and the row does not carry it. */
    val withFood: Boolean get() = medicine.withFood

    /** A local row that the server has not confirmed. */
    val isPhoneOnly: Boolean get() = origin == DoseOrigin.PhoneOnly

    /**
     * Whether this row may be counted as a miss against a guardian.
     *
     * Both conditions matter and neither is enough alone: the row must not be
     * open (the user can still take it), and a copy capable of reminding them
     * must have existed.
     */
    fun countsAsRealMiss(at: Instant, grace: kotlin.time.Duration): Boolean {
        if (log.status != DoseStatus.Missed) return false
        if (log.takenAt != null) return false
        if (at < log.dueAt.plus(grace)) return false
        return origin.canCountAsRealMiss
    }
}

/**
 * A slot that produced no row, and why. The counterpart to [PlannedDose]:
 * a shorter list on the Today screen is never silent, it is explained here.
 */
data class OmittedDose(
    val medicineId: Int,
    val medicineName: String,
    val slot: LocalTime?,
    val reason: DoseNote,
)

/**
 * A day's doses, and the arithmetic done properly.
 *
 * [day] is the core-model [DoseDay] for anything that wants it. [summaryAt] is
 * what the screens should use, because it is the one that does not lose a
 * deliberately skipped dose.
 */
data class DoseDayPlan(
    val date: LocalDate,
    val doses: List<PlannedDose>,
    val omitted: List<OmittedDose>,
    val medicines: Map<Int, Medicine>,
) {
    /** The core-model view, for the wire and for anything already typed on it. */
    val day: DoseDay get() = DoseDay(date = date, doses = doses.map { it.log })

    /**
     * Totals at [at].
     *
     * [DoseDaySummary.skipped] is counted on its own line and never folded into
     * [DoseDaySummary.missed]. A skipped dose is a decision the user made about
     * their own body; counting it as a miss would telephone a husband about his
     * wife choosing not to take a tablet tonight.
     *
     * [DoseDaySummary.missed] and [DoseDaySummary.guardianCount] are different
     * numbers on purpose, and the difference is the whole file. A dose nobody
     * was ever reminded about was still not taken, and the user should see it
     * red on their own screen; it just may not telephone a guardian.
     */
    fun summaryAt(at: Instant, grace: Duration = DoseTracker.GRACE): DoseDaySummary {
        val taken = doses.count { it.log.status == DoseStatus.Taken }
        val skipped = doses.count { it.log.status == DoseStatus.Skipped }
        val late = doses.filter { it.isLate(at, grace) }
        val open = doses.size - taken - skipped - late.size
        return DoseDaySummary(
            date = date,
            total = doses.size,
            taken = taken,
            skipped = skipped,
            missed = late.size,
            guardianCount = late.count { it.origin.canCountAsRealMiss },
            open = open,
        )
    }

    /** Unclaimed and past the grace window, whatever copy knew about it. */
    private fun PlannedDose.isLate(at: Instant, grace: Duration): Boolean =
        log.status == DoseStatus.Missed && log.takenAt == null && at >= log.dueAt.plus(grace)
}

/**
 * A day's totals, counted the way the plan means them.
 *
 * Everything is settled by [at] plus the grace window: a dose still inside its
 * grace period is [open], not [missed], because the user can still take it and
 * counting it early is how a reminder turns into a complaint.
 */
data class DoseDaySummary(
    val date: LocalDate,
    val total: Int,
    val taken: Int,
    val skipped: Int,
    /** Unclaimed and past the grace window, whatever copy knew about it. */
    val missed: Int,
    /** Doses that may move the guardian counter today. Never more than [missed]. */
    val guardianCount: Int,
    /** Still takeable. Not a miss yet, and not counted as one. */
    val open: Int,
) {
    /** Every dose is accounted for and none is waiting. */
    val isSettled: Boolean get() = open == 0 && (taken + skipped + missed) == total

    /**
     * Misses the user was never reminded about.
     *
     * Shown on the day's own card, never sent to anybody. A medicine added this
     * afternoon with an 08:00 time is the usual cause, and a red line the user
     * can explain and dismiss is fine where a telephone call is not.
     */
    val unremindedMisses: Int get() = missed - guardianCount
}
