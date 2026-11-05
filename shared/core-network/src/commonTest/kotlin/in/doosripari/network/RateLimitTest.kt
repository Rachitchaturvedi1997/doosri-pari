package `in`.doosripari.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Back-off after a 429. Plan section 11 rate-limits every write per user and
 * per IP, and the client half of that is "do not send again until the server
 * said it was all right to".
 */
class RateLimitTest {

    private val bucket = ApiClient.BUCKET_USER

    @Test
    fun `a 429 with Retry-After holds the bucket for exactly that long`() {
        val gate = RateLimitGate()

        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "30")), nowMs = 1_000)

        assertEquals(1_000L + 30_000L, gate.nextAttemptAt(bucket, nowMs = 1_000))
        assertEquals(30_000L, gate.cooldownRemainingMs(bucket, nowMs = 1_000))
        assertTrue(gate.isCoolingDown(bucket, nowMs = 1_000))
    }

    @Test
    fun `the cooldown expires on its own`() {
        val gate = RateLimitGate()
        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "30")), nowMs = 0)

        assertTrue(gate.isCoolingDown(bucket, nowMs = 29_999))
        assertFalse(gate.isCoolingDown(bucket, nowMs = 30_000))
        assertNull(gate.nextAttemptAt(bucket, nowMs = 31_000))
    }

    @Test
    fun `a success clears the cooldown`() {
        // A stale cooldown left behind after one working call makes a healthy
        // app feel broken, and plan section 6.16 is about not doing that.
        val gate = RateLimitGate()
        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "30")), nowMs = 0)

        gate.observe(bucket, WireResponse(status = 200), nowMs = 100)

        assertFalse(gate.isCoolingDown(bucket, nowMs = 100))
        assertEquals(0L, gate.cooldownRemainingMs(bucket, nowMs = 100))
    }

    @Test
    fun `with no Retry-After the back-off grows and is clamped`() {
        val gate = RateLimitGate(fallbackCooldown = 5.seconds)

        gate.observe(bucket, WireResponse(status = 429), nowMs = 0)
        assertEquals(5_000L, gate.cooldownRemainingMs(bucket, nowMs = 0))

        gate.observe(bucket, WireResponse(status = 429), nowMs = 0)
        assertEquals(10_000L, gate.cooldownRemainingMs(bucket, nowMs = 0))

        // Well past the ceiling, clamped to MAX_COOLDOWN rather than growing to
        // tomorrow.
        repeat(10) { gate.observe(bucket, WireResponse(status = 429), nowMs = 0) }
        assertEquals(RateLimitGate.MAX_COOLDOWN.inWholeMilliseconds, gate.cooldownRemainingMs(bucket, nowMs = 0))
    }

    @Test
    fun `an absurd Retry-After is clamped to the ceiling`() {
        // A server is entitled to ask for a pause. It is not entitled to define
        // one long enough that a pensioner cannot say they are fine until
        // tomorrow morning.
        val gate = RateLimitGate()

        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "86400")), nowMs = 0)

        assertEquals(RateLimitGate.MAX_COOLDOWN.inWholeMilliseconds, gate.cooldownRemainingMs(bucket, nowMs = 0))
    }

    @Test
    fun `after enough 429s in a row the client's own back-off takes over`() {
        val gate = RateLimitGate(fallbackCooldown = 5.seconds)

        // The server keeps asking for one second, which is not a pause.
        repeat(RateLimitGate.MAX_OBSERVED_429) {
            gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "1")), nowMs = 0)
        }
        assertEquals(1_000L, gate.cooldownRemainingMs(bucket, nowMs = 0))

        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "1")), nowMs = 0)
        assertTrue(
            gate.cooldownRemainingMs(bucket, nowMs = 0) > 1_000L,
            "a server asking for 1s for ever must not be obeyed for ever",
        )
    }

    @Test
    fun `buckets are independent`() {
        val gate = RateLimitGate()
        gate.observe("user", WireResponse(status = 429, headers = mapOf("Retry-After" to "30")), nowMs = 0)

        assertTrue(gate.isCoolingDown("user", nowMs = 0))
        assertFalse(gate.isCoolingDown("other", nowMs = 0))
    }

    @Test
    fun `reset clears one bucket or all of them`() {
        val gate = RateLimitGate()
        gate.observe("a", WireResponse(status = 429), nowMs = 0)
        gate.observe("b", WireResponse(status = 429), nowMs = 0)

        gate.reset("a")
        assertFalse(gate.isCoolingDown("a", nowMs = 0))
        assertTrue(gate.isCoolingDown("b", nowMs = 0))

        gate.reset()
        assertFalse(gate.isCoolingDown("b", nowMs = 0))
    }

    @Test
    fun `awaitPermit returns at once for a free bucket`() = runTest {
        val gate = RateLimitGate()
        var slept = 0L

        gate.awaitPermit(bucket, nowMs = { 0L }) { slept += it.inWholeMilliseconds }

        assertEquals(0L, slept)
    }

    @Test
    fun `awaitPermit waits out the cooldown and then returns`() = runTest {
        val gate = RateLimitGate()
        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "3")), nowMs = 0)

        // A virtual clock the sleeper advances, which is the point of injecting
        // both: this test costs no wall-clock time.
        var now = 0L
        val slept = mutableListOf<Long>()
        gate.awaitPermit(bucket, nowMs = { now }) { wait ->
            slept += wait.inWholeMilliseconds
            now += wait.inWholeMilliseconds
        }

        assertEquals(listOf(3_000L), slept)
        assertFalse(gate.isCoolingDown(bucket, nowMs = now))
    }

    @Test
    fun `a second 429 during the wait extends it`() = runTest {
        val gate = RateLimitGate()
        gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "3")), nowMs = 0)

        var now = 0L
        gate.awaitPermit(bucket, nowMs = { now }) { wait ->
            now += wait.inWholeMilliseconds
            // Something else in the app got a 429 too while we were asleep.
            gate.observe(bucket, WireResponse(status = 429, headers = mapOf("Retry-After" to "2")), now)
        }

        assertTrue(gate.isCoolingDown(bucket, nowMs = now), "the newer 429 must still be honoured")
        assertEquals(5_000L, gate.cooldownRemainingMs(bucket, nowMs = now))
    }
}
