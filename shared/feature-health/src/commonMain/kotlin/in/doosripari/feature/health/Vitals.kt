package `in`.doosripari.feature.health

import `in`.doosripari.core.model.VITAL_SINGLE_KEY
import `in`.doosripari.core.model.Vital
import `in`.doosripari.core.model.VitalKind
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Recording and trending vitals. Plan section 6.10.
 *
 * ## This app does not give medical advice
 *
 * Plan section 14 says it in one line — "general wellness information only, no
 * diagnosis" — and this file is shaped by that line rather than by the fact that
 * it would be convenient not to be.
 *
 * `VitalKind.isOutOfRange` is a **threshold to notice, not a diagnosis.** A
 * number outside it means exactly one thing: this is different from the band
 * this app usually sees for this person, and a doctor is the person who knows
 * what to do about it. So nothing here:
 *
 *  - names a condition ("hypertension", "arrhythmia", "diabetes"),
 *  - gives advice ("reduce salt", "stop taking", "see a doctor urgently"),
 *  - calls anything dangerous, normal, or fine,
 *  - uses the `safety` colour token, which plan section 4.3 reserves for SOS and
 *    a missed check-in. An out-of-range blood pressure is a marigold line on a
 *    card, not a red banner.
 *
 * The output is a question, not a finding: *this is higher than usual — would
 * you like to show it to a doctor?* That is the whole of the advice this app is
 * permitted to give. [VitalNotice.isDiagnosis] is carried as a field
 * (permanently false) and [VitalNotice.offerToShowDoctor] as one (permanently
 * true) so that a screen cannot drift into something stronger without it being
 * visible in a diff.
 */
object Vitals {

    /**
     * The window plan section 6.10's charts use.
     *
     * Thirty days, not a year. A year of daily blood pressure for a 70-year-old
     * is four hundred points on a phone screen, and a chart nobody can read is
     * worse than no chart, because it looks like the app is keeping an eye on
     * something. Thirty days is enough for a doctor to see a shape and short
     * enough to draw.
     */
    val CHART_WINDOW: Duration = 30.days

    /** A comparison needs a band to compare against, so it needs more than one. */
    const val MIN_POINTS_FOR_COMPARISON: Int = 3

    /**
     * How far from the person's own median counts as a change: fifteen per cent.
     *
     * A proportion of the median rather than an absolute number, so a pulse and
     * a weight are not held to the same tolerance. Fifteen per cent is a real
     * change and not a rounding error: day-to-day variation in a home
     * blood-pressure reading runs to about ten per cent, so anything under this
     * is the noise of the instrument and the cuff rather than of the body.
     */
    const val TYPICAL_BAND_FRACTION: Double = 0.15

    // ------------------------------------------------------------------ recording --

    /**
     * Drop anything a [Vital] is not allowed to carry.
     *
     * `Vital.values` is a map, and a map is the easiest thing in this codebase
     * to fill with junk: a key from a voice transcription, a unit-suffixed
     * string somebody parsed by hand, a second "value" on a blood-pressure row.
     * `VitalKind.allowedKeys` is the contract, and the cheapest place to hold it
     * is on the way in.
     *
     * A value of zero or below is dropped rather than stored. A negative blood
     * pressure is a parsing failure rather than a measurement, and a number like
     * that sitting in a log which a doctor may eventually be shown is a thing
     * this app should never hand over.
     */
    fun tidy(vital: Vital): Vital = vital.copy(
        values = vital.values
            .filterKeys { it in vital.kind.allowedKeys }
            .filterValues { it > 0.0 }
            .toSortedMap(),
    )

    /**
     * Whether this reading is worth a word on the Today screen.
     *
     * Delegates to [VitalKind.isOutOfRange] rather than re-deriving it, so there
     * is one place where the band lives and one place to change it. A missing
     * value is not out of range — [VitalKind.isOutOfRangeValue] already returns
     * false for null, and a blank field must never produce a worry.
     */
    fun notice(vital: Vital): VitalNotice? {
        val out = vital.outOfRangeKeys()
        if (out.isEmpty()) return null
        val columns = out.map { key -> DirectionColumn(key, vital.values.getValue(key), isHigherThanBand(vital.kind, key, vital.values.getValue(key))) }
        return VitalNotice(
            kind = vital.kind,
            values = vital.values,
            keys = out,
            direction = directionOf(columns),
            headline = Phrase(
                key = headlineKey(columns, selfComparison = false),
                args = mapOf("reading" to readOut(vital, out)),
            ),
        )
    }

    // -------------------------------------------------------------------- trending --

    /**
     * The readings of one kind, newest first, inside [window].
     *
     * Readings from HealthKit or Health Connect arrive whenever the other app
     * feels like syncing and are marked [Vital.fromHealthPlatform]. They are
     * included — a cuff that logs automatically is better data, not worse — but
     * a series made only of them is marked, because a chart of a device's own
     * estimates with one hand-typed reading somewhere in it invites a question
     * this app cannot answer.
     */
    fun series(
        vitals: List<Vital>,
        kind: VitalKind,
        now: Instant,
        window: Duration = CHART_WINDOW,
    ): VitalSeries {
        val from = now - window
        val points = vitals
            .filter { it.kind == kind && it.measuredAt in from..now }
            .map { VitalPoint(at = it.measuredAt, values = tidy(it).values, fromHealthPlatform = it.fromHealthPlatform) }
            .sortedByDescending { it.at }
        return VitalSeries(
            kind = kind,
            points = points,
            typical = typicalFor(kind, points),
        )
    }

    /**
     * The middle of this person's own recent readings, per key.
     *
     * Not a population range, not last month's average, and definitely not a
     * threshold. "Typical" here means *the band this app has actually seen for
     * this person lately*, which is the only comparison a user can act on
     * without a doctor's help. A median rather than a mean, because one wild
     * reading — a cuff put on over a coat, a number typed with two extra digits
     * — moves a mean and not a median.
     */
    fun typicalFor(kind: VitalKind, points: List<VitalPoint>): Map<String, Double?> =
        kind.allowedKeys.associateWith { key ->
            val column = points.mapNotNull { it.values[key] }.sorted()
            if (column.isEmpty()) null else column[column.size / 2]
        }

    /**
     * A notice about a reading *relative to this person's own recent band*,
     * whether or not it is outside the absolute band.
     *
     * This is the one that earns the feature. Somebody whose pulse has been 78
     * for six months and reads 61 this morning is inside every threshold in
     * [VitalKind] and is also plainly not themselves, and only a comparison with
     * their own history catches that. The result is a *direction*, never a
     * magnitude with a verdict attached: 61 and 12 are both "lower than usual"
     * here, and the difference between them is for a doctor and not for a
     * notification.
     */
    fun comparisonNotice(latest: Vital, series: VitalSeries): VitalNotice? {
        if (series.points.size < MIN_POINTS_FOR_COMPARISON) return null
        val columns = series.kind.allowedKeys.mapNotNull { key ->
            val value = latest.values[key] ?: return@mapNotNull null
            val usual = series.typicalFor(key) ?: return@mapNotNull null
            if (abs(value - usual) < usual * TYPICAL_BAND_FRACTION) return@mapNotNull null
            DirectionColumn(key, value, isHigher = value > usual)
        }
        if (columns.isEmpty()) return null
        return VitalNotice(
            kind = latest.kind,
            values = latest.values,
            keys = columns.map { it.key },
            direction = directionOf(columns),
            headline = Phrase(
                key = headlineKey(columns, selfComparison = true),
                args = mapOf("reading" to readOut(latest, columns.map { it.key })),
            ),
        )
    }

    // ------------------------------------------------------------------- internals --

    private fun DirectionColumn(key: String, value: Double, isHigher: Boolean) =
        Triple(key, value, isHigher)

    private fun directionOf(columns: List<Triple<String, Double, Boolean>>): VitalDirection {
        val higher = columns.count { it.third }
        return when {
            higher > 0 && higher < columns.size -> VitalDirection.Mixed
            higher == columns.size -> VitalDirection.Higher
            else -> VitalDirection.Lower
        }
    }

    private fun headlineKey(
        columns: List<Triple<String, Double, Boolean>>,
        selfComparison: Boolean,
    ): String = when (directionOf(columns)) {
        VitalDirection.Higher ->
            if (selfComparison) KEYS.HIGHER_THAN_YOURSELF else KEYS.HIGHER
        VitalDirection.Lower ->
            if (selfComparison) KEYS.LOWER_THAN_YOURSELF else KEYS.LOWER
        VitalDirection.Mixed ->
            if (selfComparison) KEYS.UNUSUAL_FOR_YOU else KEYS.MIXED
    }

    /**
     * Whether a value sits above the band rather than below it.
     *
     * The bounds are restated here because [VitalKind] exposes a predicate
     * (`isOutOfRangeValue`) and not a range, and "higher or lower" is the one
     * question this module cannot answer from a yes/no. **This table must be
     * changed whenever `VitalKind.isOutOfRangeValue` is** — the two are held
     * together by `VitalsBandTest`, which asserts they agree, so a one-sided
     * edit fails the build rather than shipping a card that says "lower" for a
     * number that is too high. Exposing `range(key)` on `VitalKind` would
     * remove the duplication and is raised as a core-model finding.
     */
    private fun isHigherThanBand(kind: VitalKind, key: String, value: Double): Boolean {
        val band = bandFor(kind, key) ?: return false
        return value > band.last
    }

    /** The absolute band, mirroring `VitalKind.isOutOfRangeValue`. */
    internal fun bandFor(kind: VitalKind, key: String): ClosedFloatingPointRange<Double>? = when (kind) {
        VitalKind.BloodPressure -> when (key) {
            "systolic" -> 90.0..140.0
            "diastolic" -> 60.0..90.0
            else -> null
        }
        VitalKind.Pulse -> 50.0..100.0
        VitalKind.BloodSugarFasting -> 70.0..140.0
        VitalKind.BloodSugarRandom -> 70.0..200.0
        // No band, on purpose. Weight and temperature are recorded and shown
        // and nothing is said about them (plan section 14).
        VitalKind.Weight, VitalKind.Temperature -> null
    }

    /**
     * "150 over 95" rather than "systolic 150, diastolic 95".
     *
     * Every key reaching here came from [Vital.outOfRangeKeys], which only
     * returns keys with a value present, so [getValue] cannot throw.
     */
    private fun readOut(vital: Vital, keys: List<String>): String {
        fun v(key: String): String {
            val value = vital.values.getValue(key)
            return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        }
        return when {
            keys == listOf(VITAL_SINGLE_KEY) -> v(VITAL_SINGLE_KEY)
            keys.size == 2 -> "${v(keys[0])} over ${v(keys[1])}"
            else -> keys.joinToString(", ") { "$it ${v(it)}" }
        }
    }

    /** String keys for the vitals copy. Owned by each platform's catalogue. */
    object KEYS {
        /** "This is higher than usual." */
        const val HIGHER: String = "health.vitals.higher_than_usual"

        /** "This is lower than usual." */
        const val LOWER: String = "health.vitals.lower_than_usual"

        /** "Some of these numbers are outside the usual band." */
        const val MIXED: String = "health.vitals.mixed"

        /** "This is higher than the readings we have seen for you lately." */
        const val HIGHER_THAN_YOURSELF: String = "health.vitals.higher_than_your_own"

        /** "This is lower than the readings we have seen for you lately." */
        const val LOWER_THAN_YOURSELF: String = "health.vitals.lower_than_your_own"

        /** "This is not like your other readings." */
        const val UNUSUAL_FOR_YOU: String = "health.vitals.unusual_for_you"

        /**
         * The action: "Would you like to show it to a doctor?" Plan section
         * 6.10 asks for "talk to your doctor", and this is the whole of the
         * advice this app is permitted to give.
         */
        const val SHOW_TO_DOCTOR: String = "health.vitals.show_to_doctor"

        /**
         * The standing disclaimer, from plan section 6.10: usual-range
         * references and "talk to your doctor", never a diagnosis.
         */
        const val NOT_A_DIAGNOSIS: String = "health.vitals.not_a_diagnosis"
    }
}

/** Which way a reading moved. A direction, never a severity. */
enum class VitalDirection(val code: String) {
    Higher("higher"),
    Lower("lower"),

    /** Some keys up, some down — which is the one worth a doctor's attention. */
    Mixed("mixed");

    companion object {
        fun fromCode(code: String?): VitalDirection =
            entries.firstOrNull { it.code == code } ?: Mixed
    }
}

/**
 * Something the app noticed, phrased as a question.
 *
 * [isDiagnosis] is always false and [offerToShowDoctor] always true; see the
 * file KDoc. They are fields rather than conventions so that a reviewer reading
 * a diff can see a screen is not being handed something to escalate on.
 */
data class VitalNotice(
    val kind: VitalKind,
    val values: Map<String, Double>,
    /** The specific readings, by key, so the line can name which number is odd. */
    val keys: List<String>,
    val direction: VitalDirection,
    val headline: Phrase,
    val offerToShowDoctor: Boolean = true,
    /** Always false. Plan section 14: this app gives no medical advice. */
    val isDiagnosis: Boolean = false,
) {
    /**
     * The action, if the app is allowed one.
     *
     * Export and sharing are plan section 6.10 features and they act on the
     * reading rather than on the notice, so a "take this to a doctor"
     * affordance is offered and nothing clinical.
     */
    val actionPhrase: Phrase get() = Phrase(Vitals.KEYS.SHOW_TO_DOCTOR)

    val disclaimerPhrase: Phrase get() = Phrase(Vitals.KEYS.NOT_A_DIAGNOSIS)
}

/** One point on a chart. */
data class VitalPoint(
    val at: Instant,
    val values: Map<String, Double>,
    val fromHealthPlatform: Boolean = false,
)

/**
 * A kind of reading over a window, and the person's own band within it.
 *
 * [typical] is a median of what this person has recorded, which is a different
 * thing from a reference range and must never be drawn as one. There is no
 * comparison with other users anywhere in this file or in this app.
 */
data class VitalSeries(
    val kind: VitalKind,
    /** Newest first. */
    val points: List<VitalPoint>,
    /** Median per key. Null where there is no history for that key. */
    val typical: Map<String, Double?>,
) {
    val isEmpty: Boolean get() = points.isEmpty()

    val size: Int get() = points.size

    /** The median for one key, or null. */
    fun typicalFor(key: String): Double? = typical[key]

    /**
     * True when every point on the chart came from the device rather than from
     * the user. Drawn with a note, because a chart of one machine's estimates is
     * a chart of a machine.
     */
    val isDeviceOnly: Boolean get() = points.isNotEmpty() && points.all { it.fromHealthPlatform }
}
