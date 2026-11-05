package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.LeaveEncashmentInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Leave encashment. The rule under test is the **accumulation ceiling** — 30
 * days for a non-LR employee, 300 for one who is — because that is the one
 * that surprises people: a pensioner with twenty-five years of service and a
 * balance of four hundred days is not paid for four hundred days.
 *
 * Plan section 6.5 has no leave encashment rule in it at all. These numbers
 * follow the CCS (Leave) Rules.
 */
class LeaveEncashmentTest {

    private fun input(
        days: Int,
        pay: Double = 60_000.0,
        lr: Boolean = false,
        years: Double = 25.0,
    ) = LeaveEncashmentInput(
        leaveDays = days,
        lastDrawnPay = pay,
        serviceYears = years,
        isLrEntitled = lr,
    )

    // ----------------------------------------------------------------- the ceilings --

    @Test
    fun `a non-LR balance is capped at thirty days however long the service`() {
        val result = LeaveEncashmentCalculator.calculate(input(days = 400))

        assertEquals(30, result.daysCounted)
        assertTrue(result.wasCapped)
        // 60,000 / 30 × 30 — a month's pay a day, thirty days.
        assertEquals(60_000.0, result.amount)
    }

    @Test
    fun `an LR balance is capped at three hundred`() {
        val result = LeaveEncashmentCalculator.calculate(input(days = 410, lr = true))

        assertEquals(300, result.daysCounted)
        assertTrue(result.wasCapped)
        assertEquals(600_000.0, result.amount, "60,000 / 30 × 300")
    }

    @Test
    fun `a balance inside the ceiling is not capped`() {
        val result = LeaveEncashmentCalculator.calculate(input(days = 18))

        assertEquals(18, result.daysCounted)
        assertFalse(result.wasCapped)
        assertEquals(36_000.0, result.amount, "60,000 / 30 × 18")
    }

    @Test
    fun `a balance exactly on the ceiling is not capped`() {
        // The rule is "up to", so 30 is encashed in full and nothing is cut.
        val nonLr = LeaveEncashmentCalculator.calculate(input(days = 30))
        assertFalse(nonLr.wasCapped)
        assertEquals(30, nonLr.daysCounted)

        val lr = LeaveEncashmentCalculator.calculate(input(days = 300, lr = true))
        assertFalse(lr.wasCapped)
        assertEquals(300, lr.daysCounted)
    }

    @Test
    fun `the same balance is worth different money to an LR and a non-LR`() {
        val days = 300
        val nonLr = LeaveEncashmentCalculator.calculate(input(days = days, lr = false))
        val lr = LeaveEncashmentCalculator.calculate(input(days = days, lr = true))

        assertEquals(30, nonLr.daysCounted)
        assertEquals(300, lr.daysCounted)
        assertEquals(10.0, lr.amount / nonLr.amount, "ten times the days, ten times the money")
    }

    @Test
    fun `the ceiling is askable on its own`() {
        assertEquals(30, LeaveEncashmentCalculator.ceilingDays(false))
        assertEquals(300, LeaveEncashmentCalculator.ceilingDays(true))
    }

    // ----------------------------------------------------------------- the sentence --

    @Test
    fun `a capped figure comes with the sentence that explains it`() {
        val capped = LeaveEncashmentCalculator.capExplanation(input(days = 412))
        assertTrue(capped != null && capped.contains("412"))
        assertTrue(capped != null && capped.contains("30"))

        val lr = LeaveEncashmentCalculator.capExplanation(input(days = 412, lr = true))
        assertTrue(lr != null && lr.contains("300"))

        assertNull(LeaveEncashmentCalculator.capExplanation(input(days = 12)))
    }

    @Test
    fun `service years are shown but do not move the figure`() {
        // The cap is on accumulated leave, not on how long it took to accumulate
        // it. Twenty-five years and five years produce the same money from the
        // same balance, and the calculator does not invent a limit the rules do
        // not state.
        val long = LeaveEncashmentCalculator.calculate(input(days = 100, years = 30.0))
        val short = LeaveEncashmentCalculator.calculate(input(days = 100, years = 3.0))
        assertEquals(long.amount, short.amount)
    }

    // ----------------------------------------------------------------- degenerate --

    @Test
    fun `no leave is no money`() {
        val result = LeaveEncashmentCalculator.calculate(input(days = 0))
        assertEquals(0, result.daysCounted)
        assertEquals(0.0, result.amount)
        assertFalse(result.wasCapped)
    }

    @Test
    fun `a negative balance is refused rather than paid out`() {
        assertFailsWith<IllegalArgumentException> {
            LeaveEncashmentCalculator.calculate(input(days = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            LeaveEncashmentCalculator.calculate(input(days = 10, pay = -1.0))
        }
    }

    @Test
    fun `the value is grouped for a screen that has nothing better`() {
        val result = LeaveEncashmentCalculator.calculate(input(days = 300, lr = true))
        assertEquals("₹6,00,000", result.display())
    }
}
