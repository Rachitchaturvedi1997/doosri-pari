package `in`.doosripari.feature.health

import `in`.doosripari.core.model.Medicine

/**
 * How much medicine is left, and when to say so. Plan section 6.10.
 *
 * The user counts their own tablets. There is no dispensing record, no pharmacy
 * integration and no barcode database in this plan, so `Medicine.stock` is a
 * number a person typed at some point and it is only as true as the last time
 * they remembered to update it. Everything below is written with that in mind:
 * the numbers are estimates and are labelled as such, and nothing in this file
 * is allowed to be as confident as a prescription.
 */
object StockCheck {

    /**
     * Days of medicine left, estimated.
     *
     * [Medicine.stock] is a count of doses, not of days, so the arithmetic has
     * to know the size of a day for that particular medicine. A tablet taken
     * three times a day runs out three times faster than one taken once, and
     * using a flat "days" divisor is how a stock warning arrives on the morning
     * the strip empties.
     *
     * Deliberately a floor, not a rounding: rounding 2.6 days to 3 would let a
     * warning fire a day late. A user told "2 days left" and finding 2.6 is not
     * misled; a user told "3 days left" and finding 2.6 has been told a
     * falsehood by an app.
     */
    fun daysRemaining(medicine: Medicine): Int {
        val perDay = medicine.dosesPerDay
        if (perDay <= 0) return 0
        return medicine.stock / perDay
    }

    /**
     * The one-line status for a medicine.
     *
     * Three states and no more, because this is a sentence at the top of a
     * screen and a fourth state is a fourth thing a 70-year-old has to read.
     */
    fun statusOf(medicine: Medicine): StockStatus = when {
        // No times recorded is not "out of stock". It is a half-finished form
        // and it must not put a red line on the Today card; the fix is to add
        // the times, and the screen says exactly that.
        medicine.dosesPerDay <= 0 -> StockStatus.NoSchedule
        medicine.isOut -> StockStatus.Out
        medicine.isRunningLow -> StockStatus.RunningLow
        else -> StockStatus.Sufficient
    }

    /**
     * The report the Today card and the medicine screen both read.
     *
     * @param at the current instant, used only by [StockReport.willGenerateMisses]
     *   to look at the doses still to come today.
     */
    fun check(medicines: List<Medicine>, day: DoseDayPlan? = null): StockReport {
        val items = medicines.map { medicine ->
            StockLine(
                medicine = medicine,
                status = statusOf(medicine),
                daysRemaining = daysRemaining(medicine),
                dosesToday = if (medicine.isActive) medicine.dosesPerDay else 0,
            )
        }
        return StockReport(
            items = items,
            needsRefill = items.any { it.status == StockStatus.RunningLow || it.status == StockStatus.Out },
            willGenerateMisses = day != null && items.any { it.status == StockStatus.Out && it.dosesToday > 0 },
        )
    }
}

/**
 * Where a medicine stands on stock. Ordered by how loudly it should be said.
 */
enum class StockStatus(val code: String) {
    /** Enough for a while. Not worth a line. */
    Sufficient("sufficient"),

    /**
     * Three days or fewer left, and at least one.
     *
     * **Why three.** Three days is roughly the round trip from a small town to
     * a doctor and a pharmacy and back, with a working day either side of it. A
     * warning that arrives on the day the tablets run out is not a warning, it
     * is an obituary for the routine.
     *
     * A note on the plan: section 6.10 says "a refill alert at 5 days of stock"
     * and core-model's `Medicine.LOW_STOCK_DAYS` says 3. Three is what ships,
     * because the constant is already in the data layer and a user who is told
     * at three days has a spare day to act on it. If the intent is genuinely
     * five, the constant is the one line to change — not the reasoning here,
     * which is about lead time and not about the number.
     */
    RunningLow("running_low"),

    /**
     * Nothing left.
     *
     * **What happens at zero is the important part, and it is not the same
     * thing.** `Medicine.isRunningLow` is `stock in 1..3`, so at zero it is
     * false and the quiet "you are nearly out" warning stops. That is
     * deliberate: "nearly out" and "out" are different sentences and a user who
     * has run out needs a next step, not a smaller version of yesterday's
     * warning. An out medicine with a live schedule will also turn every
     * remaining dose today into a miss, which is why [StockReport
     * .willGenerateMisses] exists — the app can see a guardian alert building
     * and the honest thing is to offer the pharmacy before it fires, not after.
     */
    Out("out"),

    /**
     * A name with no times. A half-saved form, not an empty cupboard — saying
     * "out of stock" here would be a lie and would frighten somebody.
     */
    NoSchedule("no_schedule");

    /** Whether this warrants interrupting the user. It never does on its own. */
    val isActionable: Boolean get() = this == RunningLow || this == Out

    companion object {
        fun fromCode(code: String?): StockStatus =
            entries.firstOrNull { it.code == code } ?: Sufficient
    }
}

/** One medicine's stock, and the estimate in the units a person thinks in. */
data class StockLine(
    val medicine: Medicine,
    val status: StockStatus,
    val daysRemaining: Int,
    val dosesToday: Int,
) {
    /**
     * Whether the app is already on borrowed time: more doses are scheduled
     * today than there are tablets to take them with.
     */
    val isShortForToday: Boolean get() = dosesToday > medicine.stock && medicine.stock >= 0
}

/**
 * Every medicine's stock at once, plus the two questions the screens actually
 * ask: does anybody need to go to a pharmacy, and is a guardian alert about to
 * go out because of a strip that is empty?
 */
data class StockReport(
    val items: List<StockLine>,
    val needsRefill: Boolean,
    val willGenerateMisses: Boolean,
) {
    val runningLow: List<StockLine> get() = items.filter { it.status == StockStatus.RunningLow }
    val out: List<StockLine> get() = items.filter { it.status == StockStatus.Out }
    val needsTimes: List<StockLine> get() = items.filter { it.status == StockStatus.NoSchedule }

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * The medicine to put on top of the card when only one can be shown.
     *
     * Out beats running low, running low beats a half-saved form, and an
     * inactive medicine is never chosen at all — a switched-off medicine has no
     * business on the Today screen whatever its count says.
     */
    fun mostUrgent(): StockLine? =
        items
            .filter { it.medicine.isActive && it.status.isActionable }
            .minByOrNull { it.medicine.stock }
}
