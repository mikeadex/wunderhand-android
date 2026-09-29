package com.wunderhand.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The day strip's arithmetic: which week is earlier, which day opens, and what the heading says across a month end. */
class TimeStripTest {
    private fun day(iso: String) = SlotsResponse.SlotDay(iso, iso, slots = emptyList())

    @Test fun `the server's labels are used, and dates stand in without them`() {
        val slots = ChairtimeJson.decodeFromString(BookingSlotsResponse.serializer(), Fixtures.text("booking-slots"))
        val first = slots.days.first()
        assertEquals(first.dow, TimeStrip.dow(first))
        assertEquals(first.dom, TimeStrip.dom(first))
        assertTrue(first.slots.all { it.period != null })
        assertNotNull(slots.nextFrom)
        val bare = day("2026-09-29")
        assertEquals("Tue", TimeStrip.dow(bare)); assertEquals("29", TimeStrip.dom(bare)); assertEquals("September", TimeStrip.month(bare))
    }

    @Test fun `the heading names both months across a month end`() {
        val days = listOf("2026-09-28", "2026-09-30", "2026-10-01", "2026-10-03").map(::day)
        assertEquals("September – October", TimeStrip.monthHeading(days))
        assertEquals("September", TimeStrip.monthHeading(days.take(2)))
    }

    @Test fun `previous week never reaches before today`() {
        assertEquals("2026-10-06", TimeStrip.previousFrom("2026-10-13", "2026-09-29"))
        assertEquals("2026-09-29", TimeStrip.previousFrom("2026-10-02", "2026-09-29"))
        assertNull(TimeStrip.previousFrom("2026-09-29", "2026-09-29"))
    }

    @Test fun `the strip opens on the day asked for when it is there`() {
        val days = listOf("2026-10-13", "2026-10-14", "2026-10-15").map(::day)
        assertEquals("2026-10-14", TimeStrip.openingDay(days, "2026-10-14"))
        assertEquals("2026-10-13", TimeStrip.openingDay(days, "2026-10-20"))
        assertEquals("2026-10-13", TimeStrip.openingDay(days, null))
        assertNull(TimeStrip.openingDay(emptyList(), null))
    }

    @Test fun `times group under their part of the day in order`() {
        val clock = ShopClock(ZoneId.of("Europe/London"))
        fun at(h: Int, p: String?) = SlotsResponse.Slot(Instant.ofEpochSecond(1_790_000_000L + h * 3600L), Instant.MAX, false, p)
        val groups = TimeStrip.grouped(listOf(at(3, "evening"), at(1, "morning"), at(2, "afternoon"), at(4, "morning")), clock)
        assertEquals(listOf("morning", "afternoon", "evening"), groups.map { it.first })
        assertEquals(2, groups[0].second.size)
        // Without the server's word, the shop's clock decides.
        assertEquals("morning", TimeStrip.period(SlotsResponse.Slot(Instant.parse("2026-09-29T08:30:00Z"), Instant.MAX), clock))
    }
}
