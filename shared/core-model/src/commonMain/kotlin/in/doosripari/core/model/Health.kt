package `in`.doosripari.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Medicines, doses and vitals. Plan section 6.10.
 *
 * The one rule that shapes this file: **a dose is scheduled on the phone AND
 * on the server**, and neither is allowed to be the only copy. A pensioner
 * living in a small town with patchy data who misses an evening tablet because
 * the reminder never arrived is precisely the person this app exists for. The
 * phone's copy fires whether or not there is a network; the server's copy
 * exists so that a guardian can be told after two misses.
 *
 * [DoseLog.status] carries the state machine for that second half, and it is
 * deliberately small: a dose is either taken, missed, or skipped on purpose.
 */

data class Medicine(
    val id: Int,
    val name: String,
    /** e.g. "5 mg". Free text, because "1 tab" and "5mg" are both correct. */
    val dose: String,
    /** Times of day, local. A medicine is not a 9-to-5 schedule. */
    val times: List<LocalTime> = emptyList(),
    val withFood: Boolean = false,
    /** Days remaining at [dose], from the count the user last entered. */
    val stock: Int = 0,
    val isActive: Boolean = true,
) {
    val dosesPerDay: Int get() = times.size

    /**
     * Whether the user is running out.
     *
     * Three days is chosen because that is roughly how long it takes to reach
     * a doctor and a pharmacy, and because a warning that arrives on the day
     * the tablets run out is not a warning.
     */
    val isRunningLow: Boolean get() = stock in 1..LOW_STOCK_DAYS
    val isOut: Boolean get() = stock <= 0

    companion object {
        const val LOW_STOCK_DAYS = 3
    }
}

enum class DoseStatus(val code: String) {
    /** Confirmed by the user, or by a scan. */
    Taken("taken"),
    /** The window closed with nothing recorded. Counts towards the alert. */
    Missed("missed"),
    /** The user said they are deliberately not taking it. Not a miss. */
    Skipped("skipped");

    /** Only [Missed] counts towards alerting a guardian (plan section 12). */
    val countsAsMiss: Boolean get() = this == Missed

    companion object {
        fun fromCode(code: String?): DoseStatus? = entries.firstOrNull { it.code == code }
    }
}

data class DoseLog(
    val id: Int,
    val medicineId: Int,
    val medicineName: String = "",
    val dueAt: Instant,
    val status: DoseStatus = DoseStatus.Missed,
    val takenAt: Instant? = null,
) {
    val isOpen: Boolean get() = status == DoseStatus.Missed && takenAt == null
}

/** A day's worth of doses, which is what the phone renders. */
data class DoseDay(
    val date: LocalDate,
    val doses: List<DoseLog>,
) {
    val taken: Int get() = doses.count { it.status == DoseStatus.Taken }
    val missed: Int get() = doses.count { it.status.countsAsMiss }
    val remaining: Int get() = doses.size - taken - missed

    /** True when every dose for the day is accounted for. */
    val isComplete: Boolean get() = doses.isNotEmpty() && taken + missed == doses.size
}

/**
 * A vitals reading. Plan section 6.10.
 *
 * [value] is a map rather than a typed field because the readings differ by
 * service: blood pressure is a pair, pulse is a number, blood sugar carries
 * the unit it was taken in. The keys are fixed by [VitalKind.allowedKeys] so
 * the map cannot drift into free-form anything.
 */
data class Vital(
    val id: Int,
    val kind: VitalKind,
    val values: Map<String, Double>,
    val unit: String,
    val measuredAt: Instant,
    /** True when the reading came from HealthKit / Health Connect. */
    val fromHealthPlatform: Boolean = false,
) {
    /**
     * True when a reading is far enough out to be worth a word on the Today
     * screen. A threshold, not a diagnosis: this app does not give medical
     * advice (plan section 14), it only notices.
     */
    fun isOutsideTypicalRange(): Boolean = kind.isOutOfRange(values)

    /** The out-of-range keys, for saying which number is odd rather than just "odd". */
    fun outOfRangeKeys(): List<String> =
        kind.allowedKeys.filter { isOutOfRangeValue(it, values[it]) }
}

/**
 * The single-number key, used by every reading that is one value rather than a
 * pair. Blood pressure is the only kind that is not.
 */
const val VITAL_SINGLE_KEY = "value"

enum class VitalKind(val code: String, val allowedKeys: List<String>, val unit: String) {
    BloodPressure("blood_pressure", listOf("systolic", "diastolic"), "mmHg"),
    Pulse("pulse", listOf(VITAL_SINGLE_KEY), "bpm"),
    BloodSugarFasting("blood_sugar_fasting", listOf(VITAL_SINGLE_KEY), "mg/dL"),
    BloodSugarRandom("blood_sugar_random", listOf(VITAL_SINGLE_KEY), "mg/dL"),
    Weight("weight", listOf(VITAL_SINGLE_KEY), "kg"),
    Temperature("temperature", listOf(VITAL_SINGLE_KEY), "°F"),
    ;

    /** True when any reading this kind carries is outside its typical range. */
    fun isOutOfRange(values: Map<String, Double>): Boolean =
        allowedKeys.any { isOutOfRangeValue(it, values[it]) }

    /**
     * Whether one named reading is outside its typical range.
     *
     * A missing reading is not out of range. A blank field is not a finding,
     * and telling somebody their blood pressure is worrying when they simply
     * did not enter it is exactly the kind of false alarm that makes people
     * stop reading the app.
     */
    fun isOutOfRangeValue(key: String, value: Double?): Boolean {
        val v = value ?: return false
        return when (this) {
            BloodPressure -> when (key) {
                "systolic" -> v < 90 || v > 140
                "diastolic" -> v < 60 || v > 90
                else -> false
            }
            Pulse -> v < 50 || v > 100
            BloodSugarFasting -> v < 70 || v > 140
            BloodSugarRandom -> v < 70 || v > 200
            // Deliberately no range. This app does not give medical advice
            // (plan section 14); weight and temperature are recorded and shown,
            // and a judgement about them belongs to a doctor.
            Weight, Temperature -> false
        }
    }

    companion object {
        fun fromCode(code: String?): VitalKind? = entries.firstOrNull { it.code == code }
    }
}

// -------------------------------------------------------------- reminders --

/**
 * What a reminder is for. Plan section 6.15 sets the notification policy: the
 * app is allowed to interrupt for a medicine, a check-in and a life certificate,
 * and is not allowed to interrupt for anything else. The [isQuiet] flag below
 * is that policy, in data.
 */
enum class ReminderKind(val code: String, val isQuiet: Boolean) {
    Medicine("medicine", false),
    CheckIn("check_in", false),
    /** Life certificate deadline. Once a year, and missing it costs a pension. */
    LifeCertificate("life_certificate", false),
    Appointment("appointment", false),
    CircleEvent("circle_event", true),
    YogaSession("yoga_session", true),
    /** Everything else goes here and never rings the phone. */
    Digest("digest", true);

    /**
     * Whether this reminder is allowed to use a full-screen or time-sensitive
     * alert. Plan section 6.15: a circle post does not, a missed medicine does.
     */
    val mayUseUrgentAlert: Boolean get() = !isQuiet

    companion object {
        fun fromCode(code: String?): ReminderKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A reminder, scheduled server-side.
 *
 * Doses additionally exist in the phone's local store; this is the copy that
 * lets a guardian be told about two misses, and the copy that still fires if
 * the app is force-quit.
 */
data class Reminder(
    val id: Int,
    val kind: ReminderKind,
    /** What the reminder is about, as a typed reference rather than free text. */
    val schedule: ReminderSchedule,
    val nextAt: Instant,
    val doneAt: Instant? = null,
) {
    val isDue: Boolean get() = doneAt == null
}

sealed interface ReminderSchedule {
    /** A daily thing at a wall-clock time. */
    data class DailyAt(val time: LocalTime) : ReminderSchedule

    /** Once, on a date. */
    data class OnceOn(val date: LocalDate, val time: LocalTime) : ReminderSchedule

    /** Repeating on weekdays, e.g. a weekly yoga class. */
    data class WeeklyOn(val weekdays: Set<Int>, val time: LocalTime) : ReminderSchedule

    /** A deadline, e.g. "life certificate by the 30th". */
    data class Deadline(val at: Instant) : ReminderSchedule
}

/**
 * A health facility. Plan section 6.10 asks for a CGHS finder, and the honest
 * version of that is a list of empanelled hospitals with what they take.
 */
data class Facility(
    val id: Int,
    val name: String,
    val location: GeoPoint,
    val address: String? = null,
    val schemes: List<String> = emptyList(),
    val phone: String? = null,
    val distanceKm: Double? = null,
) {
    val acceptsCghs: Boolean get() = schemes.any { it.equals("CGHS", ignoreCase = true) }
    val acceptsEchs: Boolean get() = schemes.any { it.equals("ECHS", ignoreCase = true) }
}
