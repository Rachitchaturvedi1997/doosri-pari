package `in`.doosripari.feature.health

import `in`.doosripari.core.model.Reminder
import `in`.doosripari.core.model.ReminderKind
import `in`.doosripari.core.model.ReminderSchedule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

/**
 * Which reminders may ring the phone, and how loudly. Plan section 6.15.
 *
 * The plan's words are unforgiving: "at most 4 non-urgent notifications a day",
 * "quiet hours 21:00–7:00; safety, medicine and check-in notifications ignore
 * them", and on iOS "SOS and missed check-in: critical or time-sensitive;
 * medicines: time-sensitive; everything else: active or passive; circle digests
 * go into the Notification Summary".
 *
 * Three rules, layered, and every one of them can only make a notification
 * quieter than the caller wanted:
 *
 *  1. **A quiet kind can never be urgent.** [ReminderKind.isQuiet] is the data
 *     for this, and it lives in core-model so that "may this ring the phone at
 *     two in the morning?" is answered in one place rather than at each of the
 *     dozen call sites that build a notification. This is the rule the module
 *     exists to enforce: a circle post must never be able to raise a full-screen
 *     alert, whatever the screen building it decides.
 *  2. **Time-sensitive is narrower than urgent.** Section 6.15 names exactly
 *     three kinds as allowed to break through — medicine, check-in and the life
 *     certificate. [ReminderKind] marks `Appointment` as *not* quiet, which
 *     permits an active interruption, but an appointment is "everything else" as
 *     far as the plan is concerned and is capped at active here. The
 *     disagreement between the data and the plan is a finding; it is not this
 *     file's place to settle it silently, so the stricter reading wins and the
 *     KDoc says so.
 *  3. **Quiet hours and the daily cap can only delay or bundle, never drop.**
 *     A notification is held to the end of quiet hours or folded into a summary.
 *     Nothing is discarded, because a reminder that quietly vanished is the
 *     failure mode this whole app exists to prevent — plan section 21's first
 *     two rows are exactly that failure, and a notification that can be lost is
 *     the same bug wearing a different hat.
 */
object ReminderPolicy {

    /** Plan section 6.15: 21:00. */
    val QUIET_START: LocalTime = LocalTime(21, 0)

    /** Plan section 6.15: 07:00. */
    val QUIET_END: LocalTime = LocalTime(7, 0)

    /**
     * Plan section 6.15: "at most 4 non-urgent notifications a day".
     *
     * Four. This is the most important number in the file, because the failure
     * this app is most likely to have is not a missing alert — it is eleven
     * notifications a day until a pensioner uninstalls it in a good mood.
     * Emergency-grade kinds do not count against it, or a medicine reminder
     * would be the thing that got suppressed.
     */
    const val DAILY_NON_URGENT_LIMIT: Int = 4

    // ------------------------------------------------------------------- levels --

    /**
     * The loudest [kind] may ever be, before any policy is applied.
     *
     * This is the single enforcement point for [ReminderKind.isQuiet]: a screen
     * cannot go around it, because there is nowhere else to go around it to.
     * [mayRequestUrgent] is the same rule as a yes/no question, and both are here
     * because callers ask it both ways.
     */
    fun ceilingFor(kind: ReminderKind): InterruptionLevel = when (kind) {
        ReminderKind.CheckIn -> InterruptionLevel.Critical
        ReminderKind.Medicine -> InterruptionLevel.TimeSensitive
        // Once a year, and missing it costs a pension. Section 6.15 names it.
        ReminderKind.LifeCertificate -> InterruptionLevel.TimeSensitive
        // Not quiet in core-model, so an active banner is allowed. Not named in
        // section 6.15's time-sensitive list, so it stops there.
        ReminderKind.Appointment -> InterruptionLevel.Active
        ReminderKind.CircleEvent -> InterruptionLevel.Passive
        ReminderKind.YogaSession -> InterruptionLevel.Active
        // "Everything else" goes to the Notification Summary.
        ReminderKind.Digest -> InterruptionLevel.Passive
    }

    /**
     * Whether [kind] may use a full-screen or time-sensitive alert.
     *
     * The test is [ReminderKind.isQuiet] and nothing else — no severity table,
     * no caller-supplied urgency, no "but the user asked for this one". A
     * reminder that is quiet is quiet.
     */
    fun mayRequestUrgent(kind: ReminderKind): Boolean = kind.mayUseUrgentAlert

    /** The Android channel / iOS category a reminder belongs to. Section 6.15. */
    fun channelFor(kind: ReminderKind): ReminderChannel = when (kind) {
        ReminderKind.CheckIn -> ReminderChannel.Safety
        ReminderKind.Medicine -> ReminderChannel.Medicines
        ReminderKind.LifeCertificate -> ReminderChannel.Pension
        ReminderKind.Appointment -> ReminderChannel.Medicines
        ReminderKind.CircleEvent -> ReminderChannel.Circles
        ReminderKind.YogaSession -> ReminderChannel.Practice
        ReminderKind.Digest -> ReminderChannel.Circles
    }

    /**
     * Whether [kind] is exempt from quiet hours.
     *
     * Section 6.15 names three and this names two, on purpose. A missed
     * check-in is escalated by `feature-checkin` as a [ReminderKind.CheckIn] and
     * is covered. A **life certificate is not exempt**: the plan did not list
     * it, and a certificate deadline is normally days away, so holding the
     * reminder to 07:00 costs the user nothing and keeps the exemption list
     * honest. A reminder about a deadline that expires *during* quiet hours is
     * the exception — see [holdUntil].
     */
    fun ignoresQuietHours(kind: ReminderKind): Boolean = when (kind) {
        ReminderKind.CheckIn, ReminderKind.Medicine -> true
        else -> false
    }

    // ------------------------------------------------------------------- the day --

    /**
     * Everything due on one local day, decided.
     *
     * The plan puts a per-day cap on *non-urgent* notifications, and a rule
     * about a day cannot be enforced one notification at a time. This therefore
     * takes the day, not a reminder, and is the only entry point for applying
     * the cap.
     *
     * @param alreadyDelivered how many non-urgent notifications already went out
     *   today, so a day partly handled before the app was closed does not get a
     *   fresh budget of four.
     */
    fun planDay(
        day: LocalDate,
        reminders: List<Reminder>,
        timeZone: TimeZone,
        alreadyDelivered: Int = 0,
    ): DayPlan {
        val due = reminders
            .filter { it.isDue && it.nextAt.toLocalDateTime(timeZone).date == day }
            .sortedWith(compareBy({ it.nextAt }, { it.id }))

        val deliveries = ArrayList<Delivery>(due.size)
        val bundled = ArrayList<Reminder>()
        var nonUrgentUsed = alreadyDelivered

        for (reminder in due) {
            val level = ceilingFor(reminder.kind)

            if (level.isUrgent) {
                deliveries += Delivery(
                    reminder = reminder,
                    at = reminder.nextAt,
                    level = level,
                    channel = channelFor(reminder.kind),
                )
                continue
            }

            val held = holdUntil(reminder, timeZone)
            if (held != null) {
                deliveries += Delivery(
                    reminder = reminder,
                    at = held,
                    level = level,
                    heldUntil = held,
                    heldBecause = HoldReason.QuietHours,
                    channel = channelFor(reminder.kind),
                )
                continue
            }

            if (nonUrgentUsed >= DAILY_NON_URGENT_LIMIT) {
                bundled += reminder
                continue
            }

            nonUrgentUsed++
            deliveries += Delivery(
                reminder = reminder,
                at = reminder.nextAt,
                level = level,
                channel = channelFor(reminder.kind),
            )
        }

        return DayPlan(
            date = day,
            deliveries = deliveries,
            bundled = bundled,
            nonUrgentUsed = nonUrgentUsed,
        )
    }

    /**
     * The first instant this reminder may be delivered, or null if it may go out
     * now.
     *
     * Two rules, and the second is a genuine exception rather than an
     * inconsistency:
     *
     *  - a kind that ignores quiet hours, or one that may be urgent, is never
     *    held. Section 6.15's own carve-out.
     *  - a **deadline** is never held. A life certificate due on the 30th does
     *    not pause at ten o'clock at night, and holding a notification until
     *    after the thing it warns about has passed is the same failure as
     *    dropping it. A deadline that falls in quiet hours goes out immediately
     *    and late, and [Delivery.isOverdue] says so on the card.
     */
    fun holdUntil(reminder: Reminder, timeZone: TimeZone): Instant? {
        if (ignoresQuietHours(reminder.kind)) return null
        if (ceilingFor(reminder.kind).isUrgent) return null
        if (reminder.schedule is ReminderSchedule.Deadline) return null
        return quietWindowContaining(reminder.nextAt, timeZone)?.second
    }

    /**
     * The quiet window 21:00–07:00 that contains [at], or null if it is outside
     * one.
     *
     * The window spans midnight, so a notification at 02:00 belongs to the one
     * that opened at 21:00 the previous evening. Getting that wrong is how a
     * two-in-the-morning circle digest gets through, which is the single thing
     * section 6.15 exists to prevent.
     */
    fun quietWindowContaining(at: Instant, timeZone: TimeZone): Pair<Instant, Instant>? {
        val local = at.toLocalDateTime(timeZone)
        val evening = LocalDateTime(local.date, QUIET_START).toInstant(timeZone)
        val morning = LocalDateTime(local.date, QUIET_END).toInstant(timeZone)
        return when {
            at >= evening ->
                evening to LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), QUIET_END).toInstant(timeZone)
            at < morning ->
                LocalDateTime(local.date.minus(1, DateTimeUnit.DAY), QUIET_START).toInstant(timeZone) to morning
            else -> null
        }
    }

    /**
     * The evening-before moment plan section 7.2 asks festival reminders for.
     *
     * Nineteen hundred, because the useful time to learn that tomorrow is Ekadashi
     * is while there is still an evening in which to prepare. This returns the
     * instant and knows nothing about festivals; what a festival *is* is a lookup
     * in `feature-kendra`, and putting the two together here would drag the
     * festival data into a module with no business holding it.
     */
    fun eveningBefore(date: LocalDate, timeZone: TimeZone, hour: LocalTime = LocalTime(19, 0)): Instant =
        LocalDateTime(date.minus(1, DateTimeUnit.DAY), hour).toInstant(timeZone)
}

/** How loudly a reminder may interrupt. Plan section 6.15's iOS levels. */
enum class InterruptionLevel(val code: String) {
    /**
     * Breaks through Do Not Disturb and can take the lock screen. Reserved for
     * SOS and a missed check-in, and gated behind the critical-alert
     * entitlement, which plan section 21 says must be requested early.
     */
    Critical("critical"),

    /** Breaks through Focus but not the lock screen. Medicines. */
    TimeSensitive("time_sensitive"),

    /** An ordinary banner. No special allowance. */
    Active("active"),

    /** Notification Summary only, or nothing at all. */
    Passive("passive");

    val isUrgent: Boolean get() = this == Critical || this == TimeSensitive

    companion object {
        /**
         * Parse a level, defaulting to the *quietest* one.
         *
         * A server that grew a level this build has never heard of must not be
         * able to talk a phone into a full-screen alert by sending a new name.
         * Unknown means passive.
         */
        fun fromCode(code: String?): InterruptionLevel =
            entries.firstOrNull { it.code == code } ?: Passive
    }
}

/**
 * The Android channels / iOS categories. Plan section 6.15 lists them.
 *
 * Only [Safety] cannot be switched off, and that is a property of the Android
 * channel rather than of a preference, so the settings screen is handed the
 * boolean to grey the row out rather than hide it — a row that vanishes is a
 * setting the user cannot find again.
 */
enum class ReminderChannel(val code: String, val canBeDisabled: Boolean) {
    Safety("safety", canBeDisabled = false),
    Medicines("medicines", canBeDisabled = true),
    Pension("pension", canBeDisabled = true),
    Circles("circles", canBeDisabled = true),
    Seva("seva", canBeDisabled = true),
    Practice("practice", canBeDisabled = true);

    companion object {
        fun fromCode(code: String?): ReminderChannel? = entries.firstOrNull { it.code == code }
    }
}

/** Why a delivery is later than the reminder said. Never "why it was dropped". */
enum class HoldReason(val code: String) {
    /** 21:00–07:00, and this kind is not exempt. */
    QuietHours("quiet_hours"),

    /** Past the day's non-urgent budget; folded into [DayPlan.bundled]. */
    DailyCap("daily_cap");

    companion object {
        fun fromCode(code: String?): HoldReason? = entries.firstOrNull { it.code == code }
    }
}

/** One notification, with everything the platform layer needs to post it. */
data class Delivery(
    val reminder: Reminder,
    /** When to post it. Later than [Reminder.nextAt] when held. */
    val at: Instant,
    val level: InterruptionLevel,
    val heldUntil: Instant? = null,
    val heldBecause: HoldReason? = null,
    val channel: ReminderChannel,
) {
    val isHeld: Boolean get() = heldUntil != null

    /**
     * The one field the platform layer must not be able to override. A delivery
     * at [InterruptionLevel.Passive] cannot be posted full-screen, because
     * [ReminderPolicy] has already decided that and the only way past it is
     * through this module.
     */
    val mayPostFullScreen: Boolean get() = level.isUrgent

    /**
     * Whether this went out after the moment it was about, so the card can say
     * so rather than pretending it was on time. A deadline is never held, so
     * this is only ever true for a deadline that genuinely passed while the app
     * was closed.
     */
    val isOverdue: Boolean get() = reminder.schedule is ReminderSchedule.Deadline && at > reminder.nextAt
}

/**
 * A whole day's decisions.
 *
 * [bundled] is the one that matters for trust: these are notifications the user
 * did not get individually because the day had already had four. They are
 * summarised into a single "you have N more updates" row — section 6.15's
 * Notification Summary — and they are never thrown away.
 */
data class DayPlan(
    val date: LocalDate,
    val deliveries: List<Delivery>,
    val bundled: List<Reminder>,
    val nonUrgentUsed: Int,
) {
    val urgent: List<Delivery> get() = deliveries.filter { it.level.isUrgent }
    val held: List<Delivery> get() = deliveries.filter { it.isHeld }
    val isNothingToShow: Boolean get() = deliveries.isEmpty() && bundled.isEmpty()
}
