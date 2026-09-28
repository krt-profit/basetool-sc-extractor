package com.basetool.bpextractor.net

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The exchange's binding back-off numbers (`sync-guide.md#back-off-and-sync-cadence`). */
class BackoffTest {

    private var now = 1_000_000L

    private fun backoff(random: Random = Random(7)) = Backoff(nowMillis = { now }, random = random)

    @Test
    fun `nothing waits before the first failure`() {
        assertEquals(0, backoff().remainingSeconds())
    }

    @Test
    fun `the wait starts at 5 seconds and doubles up to 5 minutes, with at most a fifth of jitter`() {
        val backoff = backoff()
        val expected = listOf(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L)
        for (base in expected) {
            backoff.failed(null)
            val wait = backoff.remainingSeconds()
            assertTrue(wait in base..(base + base / 5 + 1), "wait $wait for base $base")
        }
    }

    @Test
    fun `the wait is never below the server's Retry-After, even above 5 minutes`() {
        val backoff = backoff()
        backoff.failed(3600)
        assertTrue(backoff.remainingSeconds() >= 3600)
        backoff.failed(1)
        assertTrue(backoff.remainingSeconds() >= 10)
    }

    @Test
    fun `the jitter spreads installations apart`() {
        val waits =
            (1..20).map { seed ->
                backoff(Random(seed)).also { b -> repeat(5) { b.failed(null) } }.remainingSeconds()
            }.toSet()
        assertTrue(waits.size > 1, "every installation would retry in step")
    }

    @Test
    fun `the wait runs out, and a success starts again at 5 seconds`() {
        val backoff = backoff()
        repeat(4) { backoff.failed(null) }
        now += 60_000
        assertEquals(0, backoff.remainingSeconds())

        backoff.succeeded()
        backoff.failed(null)
        assertTrue(backoff.remainingSeconds() in 5L..7L)
    }
}
