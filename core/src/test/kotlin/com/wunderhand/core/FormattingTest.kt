package com.wunderhand.core

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PenceTest {
    @Test fun `money, as the web shows it`() {
        val cases = listOf(2800 to "£28", 3740 to "£37.40", 41200 to "£412", 5 to "£0.05", 123456 to "£1,234.56")
        for ((pence, expected) in cases) assertEquals(expected, Pence(pence).formatted("GBP"))
    }

    @Test fun `decodes from a bare integer`() {
        val decoded = ChairtimeJson.decodeFromString(ListSerializer(Pence.serializer()), "[2800, 0]")
        assertEquals(listOf(Pence(2800), Pence(0)), decoded)
    }
}

class DurationsTest {
    @Test fun `short, on a diary row`() {
        for ((minutes, expected) in listOf(45 to "45m", 60 to "1h", 90 to "1h 30m", 330 to "5h 30m")) {
            assertEquals(expected, Durations.short(minutes))
        }
    }

    @Test fun `label, in a sentence`() {
        for ((minutes, expected) in listOf(45 to "45 min", 60 to "1h", 75 to "1h 15", 0 to "0 min")) {
            assertEquals(expected, Durations.label(minutes))
        }
    }
}

/** The shop's clock, not the phone's. */
class ShopClockTest {
    private val london = ShopClock("Europe/London")
    private fun instant(iso: String) = Instant.parse(iso)

    @Test fun `draws times in the shop's zone whatever the device is set to`() {
        // 11:30 UTC in September is 12:30 in London (BST).
        assertEquals("12:30", london.time(instant("2026-09-16T11:30:00Z")))
        // ...and in January, GMT, the same wall-clock hour is UTC.
        assertEquals("11:30", london.time(instant("2026-01-16T11:30:00Z")))
    }

    @Test fun `names the shop's date, not UTC's`() {
        // 23:30 UTC on 15 September is already the 16th in London.
        assertEquals("2026-09-16", london.isoDate(instant("2026-09-15T23:30:00Z")))
    }

    @Test fun `the book button names the day and time`() {
        val at = instant("2026-10-14T10:15:00Z")
        assertEquals("Wed 11:15", london.weekdayTime(at))
        assertEquals("Wed 14 Oct, 11:15", london.shortDayTime(at))
    }

    /** "Sept", as the web and the iPhone say it — Java's own en-GB says "Sep". */
    @Test fun `September is written the way the other two write it`() {
        assertEquals("Wed 16 Sept", london.shortDay(instant("2026-09-16T09:00:00Z")))
    }

    @Test fun `the eyebrow reads like the web`() {
        assertEquals("WEDNESDAY 16 SEPTEMBER", london.dayEyebrow(instant("2026-09-16T09:00:00Z")))
    }

    @Test fun `the clocks going back makes a 25 hour day`() {
        // Sunday 25 October 2026, British Summer Time ends.
        val day = london.dayBounds(instant("2026-10-25T12:00:00Z"))
        assertEquals(Duration.ofHours(25), Duration.between(day.start, day.end))
    }

    @Test fun `the clocks going forward makes a 23 hour day`() {
        // Sunday 29 March 2026, British Summer Time begins.
        val day = london.dayBounds(instant("2026-03-29T12:00:00Z"))
        assertEquals(Duration.ofHours(23), Duration.between(day.start, day.end))
    }

    @Test fun `an unknown zone falls back to London`() {
        assertEquals("Europe/London", ShopClock("Not/AZone").zone.id)
    }
}

/** What a screen says without signal. */
class SignalWordsTest {
    private val clock = ShopClock("Europe/London")
    /** Thursday 17 September 2026, 15:32 in London. */
    private val loaded = Instant.ofEpochSecond(1_789_655_520)

    @Test fun `a moment old reads as a moment ago`() {
        val words = SignalWords.stale(loaded, loaded.plusSeconds(20), clock, offline = true)
        assertEquals("No signal. Showing the day as it was a moment ago.", words)
    }

    @Test fun `later the same day carries the time`() {
        val words = SignalWords.stale(loaded, loaded.plusSeconds(3600), clock, offline = true)
        assertEquals("No signal. Showing the day as it was at 15:32.", words)
    }

    @Test fun `the next day carries the day too`() {
        val words = SignalWords.stale(loaded, loaded.plusSeconds(20 * 3600), clock, offline = false)
        assertEquals("Could not reach Wunderhand. Showing the day as it was on Thu 17 Sept, 15:32.", words)
    }

    /** The phone has a connection and chairtime still did not answer.
     *  Saying "no signal" would send a shop to check their router. */
    @Test fun `a refusal is not blamed on the signal`() {
        val words = SignalWords.stale(loaded, loaded.plusSeconds(120), clock, offline = false)
        assertEquals(true, words.startsWith("Could not reach Wunderhand."))
    }
}
