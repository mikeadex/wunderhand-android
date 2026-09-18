package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun at(iso: String): Instant = Instant.parse(iso)

/** Running the day. */
class ActionRulesTest {
    @Test fun `snaps drags to the shop's grid`() {
        // 96dp an hour on the phone: 1.6dp a minute.
        assertEquals(15, DragSnap.minutes(20.0, 1.6, 15))   // 12.5 min
        assertEquals(0, DragSnap.minutes(10.0, 1.6, 15))    // 6.25 min
        assertEquals(-30, DragSnap.minutes(-50.0, 1.6, 15)) // -31.25 min
        assertEquals(40, DragSnap.minutes(44.0, 1.0, 10))
        assertEquals(0, DragSnap.minutes(44.0, 0.0, 10))
    }

    @Test fun `done opens half an hour early, and no-show at the start`() {
        val start = at("2026-09-16T11:00:00Z")
        assertFalse(Arrival.hasArrived(start, at("2026-09-16T10:29:00Z")))
        assertTrue(Arrival.hasArrived(start, at("2026-09-16T10:30:00Z")))
        assertFalse(Arrival.hasStarted(start, at("2026-09-16T10:59:00Z")))
        assertTrue(Arrival.hasStarted(start, start))
    }

    @Test fun `a cancellation's refund decodes, and so does none`() {
        val refunded = ChairtimeJson.decodeFromString(CloseResponse.serializer(), """{"outcome":"cancelled","refund":{"refundPence":1000,"keptPence":0}}""")
        assertEquals(Pence(1000), refunded.refund?.refundPence)
        assertNull(ChairtimeJson.decodeFromString(CloseResponse.serializer(), """{"outcome":"completed","refund":null}""").refund)
    }

    /** The app knows what it asked for. A new word in the reply must not turn a finished action into a failure. */
    @Test fun `an outcome this build has not heard of still decodes`() {
        assertEquals("rebooked", ChairtimeJson.decodeFromString(CloseResponse.serializer(), """{"outcome":"rebooked"}""").outcome)
    }

    @Test fun `slots decode, with the ones that close a gap marked`() {
        val json = """{"currentStartsAt":"2026-09-16T09:00:00.000Z","days":[{"isoDate":"2026-09-17","label":"Thursday 17 September",
            "slots":[{"start":"2026-09-17T09:00:00.000Z","end":"2026-09-17T09:45:00.000Z","closesGapExactly":true},
                     {"start":"2026-09-17T09:15:00.000Z","end":"2026-09-17T10:00:00.000Z","closesGapExactly":false}]}]}"""
        val slots = ChairtimeJson.decodeFromString(SlotsResponse.serializer(), json)
        assertEquals(listOf(true, false), slots.days.single().slots.map { it.closesGapExactly })
    }

    @Test fun `blocking time is sent as the shop's wall clock, in chairtime's words`() {
        val body = ChairtimeJson.encodeToString(BlockRequest.serializer(), BlockRequest("s1", BlockRequest.Kind.Holiday, "2026-09-16", "13:00", "13:30", null))
        assertEquals("""{"staffId":"s1","kind":"holiday","date":"2026-09-16","from":"13:00","to":"13:30"}""", body)
    }
}

/** What the sheet says after something was done. */
class ActionWordsTest {
    private val clock = ShopClock("Europe/London")

    @Test fun `closing says what happened to the money`() {
        assertEquals("Marked done.", ActionWords.closed(CloseOutcome.Completed, null, "GBP"))
        assertEquals("Recorded as a no-show.", ActionWords.closed(CloseOutcome.NoShow, null, "GBP"))
        assertEquals("Cancelled.", ActionWords.closed(CloseOutcome.Cancelled, CloseResponse.Refund(Pence(0), Pence(1000)), "GBP"))
        assertEquals("Cancelled. £10 deposit refunded.", ActionWords.closed(CloseOutcome.Cancelled, CloseResponse.Refund(Pence(1000), Pence(0)), "GBP"))
    }

    @Test fun `repeating counts what was booked, and admits what was not`() {
        assertEquals("Repeating, but nothing more could be booked yet.", ActionWords.repeatStarted(RepeatStarted(0)))
        assertEquals("Booked the next one.", ActionWords.repeatStarted(RepeatStarted(1)))
        assertEquals("Booked the next 3. Some dates could not be — they are listed below.", ActionWords.repeatStarted(RepeatStarted(3, skipped = 1)))
        assertEquals("Stopped. Anything already booked is still booked.", ActionWords.repeatStopped(RepeatStopped(0)))
        assertEquals("Stopped, and cancelled 2 booked after this.", ActionWords.repeatStopped(RepeatStopped(2)))
    }

    @Test fun `a move names the new time and day, in the shop's zone`() {
        assertEquals("Moved to 11:15 on Wednesday 14 October.", ActionWords.moved(at("2026-10-14T10:15:00Z"), clock))
    }

    @Test fun `a closed appointment says how it ended`() {
        val start = at("2026-09-16T09:00:00Z")
        assertEquals("Done at 10:50.", ActionWords.closedLine("completed", at("2026-09-16T09:50:00Z"), start, clock))
        assertEquals("Done at 09:05 on Thu 17 Sept.", ActionWords.closedLine("completed", at("2026-09-17T08:05:00Z"), start, clock))
        assertEquals("Done.", ActionWords.closedLine("completed", null, start, clock))
        assertEquals("Recorded as a no-show.", ActionWords.closedLine("no_show", null, start, clock))
        assertEquals("Cancelled.", ActionWords.closedLine("cancelled", null, start, clock))
    }

    @Test fun `consent names the version it was recorded against`() {
        assertEquals("Consent recorded against version 3 of the form.", ActionWords.consentRecorded(3))
        assertEquals("Consent recorded.", ActionWords.consentRecorded(null))
    }
}
