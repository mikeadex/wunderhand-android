package com.wunderhand.core

import java.time.Duration
import java.time.Instant

/**
 * What a screen says when it could not reach chairtime.
 *
 * The app keeps what it last loaded rather than emptying the screen: a shop
 * in a basement salon still needs to know who is coming at three. What it
 * must never do is let that look current, so the words say when the screen
 * was true, and the diary keeps showing it until a load succeeds.
 */
object SignalWords {
    /**
     * Over a day that could not be refreshed.
     *
     * @param moment when the screen was last loaded.
     * @param offline the phone knows it has no connection, rather than having
     *   asked and been refused.
     */
    fun stale(moment: Instant, now: Instant = Instant.now(), clock: ShopClock, offline: Boolean): String {
        val why = if (offline) "No signal." else "Could not reach Wunderhand."
        return "$why Showing the day as it was ${whenWas(moment, now, clock)}."
    }

    /** While the app is trying again on its own, which is most of a second. */
    const val TRYING_AGAIN = "No signal. Trying again."

    /** A moment, said the way somebody would say it: the time on its own
     *  today, the day with it after that, and "a moment ago" when it has barely
     *  been a minute — "as it was at 14:32" reads oddly at 14:32. */
    internal fun whenWas(moment: Instant, now: Instant, clock: ShopClock): String {
        if (Duration.between(moment, now).seconds < 60) return "a moment ago"
        return if (clock.isSameDay(moment, now)) "at ${clock.time(moment)}" else "on ${clock.shortDayTime(moment)}"
    }
}
