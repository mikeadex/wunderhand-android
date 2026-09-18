package com.wunderhand.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a request that failed for a passing reason is tried again.
 *
 * Only a read is retried: asking for the diary twice costs nothing, while
 * closing an appointment twice is a second thing happening to a client.
 *
 * @param attempts tries in total, the first included.
 * @param firstWait the wait before the second try; it doubles after that.
 */
class RetryPlan(attempts: Int, val firstWait: Duration) {
    val attempts: Int = maxOf(1, attempts)

    companion object {
        /** Three goes, about a second and a half apart at most: long enough
         *  for a lift, a tunnel or a deploy going out, short enough that
         *  somebody holding the phone does not give up first. */
        val Standard = RetryPlan(attempts = 3, firstWait = 400.milliseconds)
        /** One go, for a screen that would rather say so at once. */
        val StraightAway = RetryPlan(attempts = 1, firstWait = Duration.ZERO)
    }
}
