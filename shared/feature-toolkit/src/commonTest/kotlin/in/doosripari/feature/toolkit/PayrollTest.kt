package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.DrRate
import `in`.doosripari.core.model.PayBasis
import `in`.doosripari.core.model.ServiceType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gross pay, and the one check this screen exists for: whether the DA drawn
 * matches the rate in force.
 *
 * Plan section 6.5 says the slip reader must ask *"was the DR applied from the
 * right month?"* — so the module does not compute net pay, deductions or
 * anything else, it adds up a slip and names a gap if there is one. These tests
 * keep it that way: the absence of a net-pay function is a decision, and a
 * future contributor adding one by reflex is exactly what the file comment in
 * Payroll.kt is warning about.
 */
class PayrollTest {

    private fun at(iso: String): Instant = Instant.parse("${iso}T00:00:00Z")

    /** The same table ArrearsTest uses, read through the payroll screen. */
    private val centralRates = listOf(
        DrRate(ServiceType.CentralCivil, 100.0, at("2014-01-01")),
        DrRate(ServiceType.CentralCivil, 125.0, at("2016-01-01")),
        DrRate(ServiceType.CentralCivil, 150.0, at("2018-01-01")),
    )

    private fun allowance(code: String, amount: Double, label: String = code) =
        Allowance(code = code, label = label, amount = amount)

    // -------------------------------------------------------------------- the addition --

    @Test
    fun `basic plus dearness plus allowances is the gross`() {
        val result = PayrollCalculator.grossPay(
            pay = PayBasis(basicPay = 30_000.0, da = 45_000.0),
            daRatePercent = 150.0,
            allowances = listOf(allowance("hra", 3_000.0), allowance("transport", 1_500.0)),
        )

        // 150% of 30,000 is 45,000 — the same figure the DA actually drawn says.
        assertEquals(45_000.0, result.dearnessPay)
        assertEquals(4_500.0, result.allowanceTotal)
        assertEquals(79_500.0, result.grossPay, "30,000 + 45,000 + 4,500")
        assertFalse(result.isDaMismatch)
    }

    @Test
    fun `no allowances is the normal case, not a missing one`() {
        val result = PayrollCalculator.grossPay(PayBasis(basicPay = 30_000.0, da = 45_000.0), 150.0)

        assertTrue(result.allowances.isEmpty())
        assertEquals(0.0, result.allowanceTotal)
        assertEquals(75_000.0, result.grossPay)
    }

    @Test
    fun `a zero DA rate leaves a pensioner on basic alone`() {
        val result = PayrollCalculator.grossPay(PayBasis(basicPay = 30_000.0), 0.0)
        assertEquals(0.0, result.dearnessPay)
        assertEquals(30_000.0, result.grossPay)
        assertFalse(result.isDaMismatch, "no DA drawn and none due is not a mismatch")
    }

    @Test
    fun `gratuity is not part of a month of pay`() {
        // PayBasis carries it; a monthly figure must not include it, or the
        // payroll screen and the gratuity screen would both be showing it.
        val result = PayrollCalculator.grossPay(
            pay = PayBasis(basicPay = 30_000.0, da = 45_000.0, gratuity = 403_846.0),
            daRatePercent = 150.0,
        )
        assertEquals(75_000.0, result.grossPay)
    }

    // ------------------------------------------------------------------ the DA check --

    @Test
    fun `a pensioner left on an old DA rate is told the size of the gap`() {
        // Drawing 125% when 150% has been in force for two years.
        val result = PayrollCalculator.grossPay(
            pay = PayBasis(basicPay = 30_000.0, da = 37_500.0),
            daRatePercent = 150.0,
        )

        assertEquals(45_000.0, result.dearnessPay, "what the rate in force says")
        assertEquals(37_500.0, result.drawnDa, "what the slip says")
        assertEquals(7_500.0, result.daShortfall)
        assertTrue(result.isDaMismatch)
    }

    @Test
    fun `DA drawn above the rate in force is a mismatch too`() {
        // The mirror case, and it must not be reported as money owed: a
        // negative shortfall is a question for the Accounts Office, not a claim.
        val result = PayrollCalculator.grossPay(
            pay = PayBasis(basicPay = 30_000.0, da = 50_000.0),
            daRatePercent = 150.0,
        )
        assertEquals(-5_000.0, result.daShortfall)
        assertTrue(result.isDaMismatch)
    }

    @Test
    fun `a gap under a rupee is not a mismatch`() {
        // The shortfall is settled in whole rupees, and a pensioner is not going
        // to the counter over forty paise. This also means a mismatch test can
        // never fail on float noise alone.
        val result = PayrollCalculator.grossPay(
            pay = PayBasis(basicPay = 30_000.0, da = 45_000.40),
            daRatePercent = 150.0,
        )
        assertEquals(0.0, result.daShortfall)
        assertFalse(result.isDaMismatch)
    }

    // ----------------------------------------------------------------- the rate table --

    @Test
    fun `the payroll screen and the arrears screen read one table`() {
        assertEquals(100.0, PayrollCalculator.currentDaRatePercent(centralRates, ServiceType.CentralCivil, at("2015-06-01")))
        assertEquals(125.0, PayrollCalculator.currentDaRatePercent(centralRates, ServiceType.CentralCivil, at("2017-01-01")))
        assertEquals(150.0, PayrollCalculator.currentDaRatePercent(centralRates, ServiceType.CentralCivil, at("2026-01-01")))
    }

    @Test
    fun `a rate for another service is not this pensioner's rate`() {
        val mixed = centralRates + DrRate(ServiceType.Defence, 300.0, at("2024-01-01"))
        assertEquals(
            150.0,
            PayrollCalculator.currentDaRatePercent(mixed, ServiceType.CentralCivil, at("2026-01-01")),
            "300% for defence must not price a civil pensioner",
        )
    }

    @Test
    fun `before the table reaches back there is no rate, and no guess`() {
        assertEquals(
            0.0,
            PayrollCalculator.currentDaRatePercent(centralRates, ServiceType.CentralCivil, at("2013-12-31")),
        )
    }

    // -------------------------------------------------------------------- degenerate --

    @Test
    fun `a negative allowance is refused rather than offset`() {
        assertFailsWith<IllegalArgumentException> { Allowance(code = "hra", amount = -1.0) }
    }

    @Test
    fun `a negative DA rate is refused`() {
        assertFailsWith<IllegalArgumentException> {
            PayrollCalculator.grossPay(PayBasis(basicPay = 30_000.0), -10.0)
        }
    }

    @Test
    fun `negative pay components are refused by the pay basis itself`() {
        assertFailsWith<IllegalArgumentException> { PayBasis(basicPay = -1.0) }
        assertFailsWith<IllegalArgumentException> { PayBasis(basicPay = 1.0, da = -1.0) }
    }

    // -------------------------------------------------------------------- the string --

    @Test
    fun `the gross is grouped the Indian way`() {
        // 7,60,000 and not 760,000 — the grouping a pensioner reads on a slip,
        // and the reason roundToRupees exists in core-model at all.
        val result = PayrollCalculator.grossPay(
            pay = PayBasis(basicPay = 500_000.0, da = 250_000.0),
            daRatePercent = 50.0,
            allowances = listOf(allowance("medical", 10_000.0, label = "Medical allowance")),
        )
        assertEquals(760_000.0, result.grossPay)
        assertEquals("₹7,60,000", result.displayGross)
    }
}
