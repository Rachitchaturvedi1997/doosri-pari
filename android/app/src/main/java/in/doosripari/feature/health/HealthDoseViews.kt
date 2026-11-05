package `in`.doosripari.feature.health

import kotlinx.datetime.Instant

/**
 * Shared dose-view helpers for the Swasthya screens.
 *
 * These live in their own file because the dose log, the schedule screen and
 * the home screen all need the same resolution of a [DoseSlot] against the
 * clock, and each of them must resolve it the same way. A per-screen
 * `statusAt` call is how a day ends up disagreeing with itself on screen.
 */

/**
 * One slot resolved against the clock, with the share flag folded in.
 *
 * [DoseSlotView.missedTwiceRunning] is filled by [adherenceFor]; passing it
 * here would mean computing the run twice.
 */
fun DoseSlot.asView(
    now: Instant,
    shared: Boolean,
    missedTwiceRunning: Boolean = false,
): DoseSlotView = DoseSlotView(
    slot = this,
    status = statusAt(now),
    medicineSharesWithGuardian = shared,
    missedTwiceRunning = missedTwiceRunning,
)

/**
 * Plan section 6.10: "the guardian is told after 2 misses in a row, if
 * allowed".
 *
 * The run is the count of consecutive missed doses immediately before the
 * first non-missed one at the end of the list — a person who missed Tuesday
 * and Wednesday but took Thursday's tablet has a run of zero today, and the
 * family should not be told about last week.
 *
 * [AdherenceSummary.guardianWillBeTold] is true only when the medicine is
 * shared. Two misses on an unshared medicine are the pensioner's own business
 * and stay that way — see [Medicine.shareWithGuardian] and the comment at the
 * toggle in `MedicineScheduleScreen.kt`.
 *
 * A run that crosses midnight continues across `doseLog(day)` calls; this
 * helper sees one day at a time, so a full build computes the run over the
 * schedule rather than the day. The threshold is in one place either way.
 */
fun adherenceFor(
    slots: List<DoseSlot>,
    shared: Boolean,
    now: Instant,
): AdherenceSummary {
    // The run at the *end* of the sequence. A person who missed Tuesday and
    // Wednesday but took Thursday's tablet has a run of zero today, and the
    // family should not be told about last week. Skipped breaks the run too:
    // "I am not taking this one" is a decision, and a decision is not a
    // problem to report.
    var run = 0
    for (slot in slots.sortedBy { it.dueAt }.asReversed()) {
        if (slot.statusAt(now) == DoseStatus.Missed) {
            run++
        } else {
            break
        }
    }

    return AdherenceSummary(
        missedInARow = run,
        guardianWillBeTold = shared && run >= GUARDIAN_TELL_THRESHOLD,
        guardianToldAt = null,
    )
}

/** Plan section 6.10: two misses in a row. */
const val GUARDIAN_TELL_THRESHOLD = 2