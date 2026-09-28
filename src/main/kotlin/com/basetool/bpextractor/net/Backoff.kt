package com.basetool.bpextractor.net

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The exchange's binding back-off: after a refused or failed request the next attempt waits 5 seconds,
 * doubling with each further failure up to 5 minutes, plus random jitter, and never less than the
 * answer's `Retry-After`; a success ends it.
 *
 * @param nowMillis the clock
 * @param random the jitter source
 */
class Backoff(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private var failures = 0
    private var untilMillis = 0L

    /** Seconds until the next attempt may run, rounded up; zero when it may run now. */
    @Synchronized
    fun remainingSeconds(): Long = max(0L, (untilMillis - nowMillis() + 999) / 1000)

    /**
     * Records a refused or failed request.
     *
     * @param retryAfterSeconds the answer's `Retry-After`, or `null`
     */
    @Synchronized
    fun failed(retryAfterSeconds: Long?) {
        failures++
        val base = min(FIRST_SECONDS shl min(failures - 1, 16), MAX_SECONDS)
        val wait = max(base, retryAfterSeconds ?: 0L) * 1000
        untilMillis = nowMillis() + wait + random.nextLong(0, wait / JITTER_DIVISOR + 1)
    }

    /** Records a success, which ends the back-off. */
    @Synchronized
    fun succeeded() {
        failures = 0
        untilMillis = 0L
    }

    companion object {
        /** The first wait. */
        const val FIRST_SECONDS = 5L

        /** The longest wait the doubling reaches; a longer `Retry-After` still wins. */
        const val MAX_SECONDS = 300L

        /** The jitter is up to this fraction of the wait. */
        private const val JITTER_DIVISOR = 5L

        /** The back-off every exchange action of this process shares. */
        val SHARED = Backoff()
    }
}
