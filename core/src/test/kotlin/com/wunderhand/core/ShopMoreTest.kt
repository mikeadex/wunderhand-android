package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun <T> read(serializer: KSerializer<T>, name: String): T = ChairtimeJson.decodeFromString(serializer, Fixtures.text(name))

class ConsentTest {
    @Test fun `every kind is listed, written or not`() {
        val consent = read(ConsentResponse.serializer(), "consent")
        assertEquals(listOf("patch_test", "medical_history", "aftercare", "age_verification", "photo_release"), consent.documents.map { it.kind })
        assertEquals(3, consent.missing)
        assertEquals("3 of these have no wording yet", ConsentWords.missingTitle(consent.missing, consent.documents.size))

        val patch = consent.documents.first()
        assertTrue(patch.isWritten); assertNotNull(patch.updatedAt)
        assertEquals("Version 1 · signed 0 times", patch.versionLine)
        assertEquals("Save as version 2", patch.saveLabel)
        assertEquals("Change the wording", ConsentWords.open(patch))

        val medical = consent.documents[1]
        assertFalse(medical.isWritten); assertNull(medical.updatedAt); assertEquals("", medical.body)
        assertEquals("Nothing set", medical.versionLine)
        assertEquals("Save as version 1", medical.saveLabel)
        assertEquals("Add medical history wording", ConsentWords.open(medical))
    }

    @Test fun `a kind has the web's name and reason`() {
        assertEquals("Age", ConsentWords.title("age_verification"))
        assertEquals("Photographs", ConsentWords.title("photo_release"))
        assertTrue(ConsentWords.about("patch_test").startsWith("Usually set by the colour manufacturer"))
        // One the app has not heard of still has a name, and no invented reason.
        assertEquals("Piercing aftercare", ConsentWords.title("piercing_aftercare"))
        assertEquals("", ConsentWords.about("piercing_aftercare"))
        assertEquals("No consent wording is set up", ConsentWords.missingTitle(5, 5))
        assertEquals("1 of these has no wording yet", ConsentWords.missingTitle(1, 5))
    }

    @Test fun `a save answers with the document, and whether anything changed`() {
        val saved = read(ConsentDocument.serializer(), "consent-document")
        assertEquals("aftercare", saved.kind); assertEquals(6, saved.version); assertFalse(saved.unchanged)
        assertEquals("Saved as version 6. Anyone who signed the previous wording is still recorded against that text, not this one.", saved.savedNote)
        assertEquals(ConsentWords.SAME, saved.copy(unchanged = true).savedNote)
        assertEquals("Version 6 · signed 1 time", saved.copy(signatures = 1).versionLine)
        assertEquals("""{"body":"Words"}""", ChairtimeJson.encodeToString(ConsentWrite.serializer(), ConsentWrite("Words")))
    }
}

class QuietTimesTest {
    @Test fun `a quiet diary shows its week, with nothing standing out`() {
        val quiet = read(QuietTimesResponse.serializer(), "quiet-times")
        assertEquals(15, quiet.demand.size)
        assertEquals(listOf(2, 3, 4, 5, 6), quiet.days)
        assertTrue(quiet.suggestions.isEmpty())
        assertEquals(QuietWords.NOTHING_STANDS_OUT, quiet.intro)
        assertNull(quiet.unsold("GBP"))
        assertEquals(2, quiet.sold(2, "m")?.soldPercent)
        assertNull(quiet.sold(0, "m"))

        val rule = quiet.rules.single()
        assertEquals("Wednesday mornings", rule.name)
        assertEquals("20% off · Wed · 00:00–11:59", rule.line("GBP"))
        assertEquals("Shows as a saving", rule.savingLine)
        assertEquals("1 price rule", quiet.rulesTitle)
    }

    @Test fun `a busier one offers what to try, emptiest first`() {
        val quiet = read(QuietTimesResponse.serializer(), "quiet-times-busy")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), quiet.days)
        assertEquals(QuietWords.EMPTIER, quiet.intro)
        assertEquals("About £397 of chair time a week goes unsold", quiet.unsold("GBP"))

        val first = quiet.suggestions.first()
        assertEquals("Tuesday mornings", first.title)
        assertEquals("Try 25% off then", first.button)
        assertEquals(Pence(14_720), first.wouldEarnPence)
        assertEquals(first, quiet.suggestion(2, "m"))
        assertNull(quiet.suggestion(1, "m"))
        assertEquals("Saturday evenings", quiet.suggestions.last().title)
    }

    @Test fun `the week runs Monday to Sunday, and nothing to go on says so`() {
        val week = QuietTimesResponse(demand = listOf(0, 6, 1).map { QuietTimesResponse.Demand(it, "m", 10) })
        assertEquals(listOf(1, 6, 0), week.days)
        assertEquals(QuietWords.NOTHING_YET, QuietTimesResponse().intro)
        assertEquals("No price rules yet", QuietTimesResponse().rulesTitle)
    }

    @Test fun `a rule reads as what it does`() {
        val rule = QuietTimesResponse.PriceRule(kind = "fixed", value = 500, from = "09:00", to = "11:59", showSaving = false)
        assertEquals("£5 off · 09:00–11:59", rule.line("GBP"))
        assertEquals("£18 flat · Mon, Tue · 09:00–11:59", rule.copy(kind = "absolute", value = 1800, weekdays = listOf(1, 2)).line("GBP"))
        assertEquals("Shows as a plain price", rule.savingLine)
    }

    @Test fun `taking a suggestion and the saving switch are written as chairtime reads them`() {
        assertEquals("""{"weekday":2,"part":"m","percent":25}""", ChairtimeJson.encodeToString(QuietTimeAccept.serializer(), QuietTimeAccept(2, "m", 25)))
        assertEquals("""{"showSaving":false}""", ChairtimeJson.encodeToString(QuietTimeUpdate.serializer(), QuietTimeUpdate(false)))
    }
}

class OffersTest {
    @Test fun `an offer says what it is and what it has done`() {
        val offer = read(OffersResponse.serializer(), "offers").offers.single()
        assertEquals("First cut with us", offer.name)
        // The seeded one runs midweek only; the days read as the web lists them.
        assertEquals(listOf(2, 3), offer.weekdays)
        assertEquals("25% off · First visit · Tue, Wed", offer.line("GBP"))
        assertEquals(19, offer.redemptions); assertEquals(Pence(11_535), offer.givenAwayPence)
        assertEquals("0/19", offer.cameBackValue)
        assertEquals("19 new clients, none back yet. Too early to say, unless it has been a while.", offer.verdict("GBP"))
        assertNull(offer.claimed)
    }

    @Test fun `one just started is first, with its code`() {
        val offers = read(OffersResponse.serializer(), "offers-started")
        assertEquals(2, offers.running.size); assertTrue(offers.stopped.isEmpty())
        val started = offers.offers.first()
        assertEquals("20% off · First visit · code ZZTESTOFFER · quiet mornings only", started.line("GBP"))
        assertEquals("—", started.cameBackValue)
        assertNull(started.verdict("GBP"))
        assertTrue(started.weekdays.isEmpty())
    }

    @Test fun `the days read Monday first, and an older answer without them is every day`() {
        assertEquals("Mon, Wed, Sun", OfferWords.days(listOf(0, 3, 1)))
        assertNull(OfferWords.days(emptyList()))
        assertEquals("20% off · Anyone · code MIDWEEK · Tue, Sat · quiet mornings only", Offer(kind = "percent", value = 20, code = "MIDWEEK", weekdays = listOf(6, 2), quietOnly = true).line("GBP"))
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("offers")).jsonObject
        val older = kotlinx.serialization.json.JsonObject(json + ("offers" to kotlinx.serialization.json.JsonArray(json.getValue("offers").jsonArray.map { kotlinx.serialization.json.JsonObject(it.jsonObject - "weekdays") })))
        assertTrue(ChairtimeJson.decodeFromJsonElement(OffersResponse.serializer(), older).offers.single().weekdays.isEmpty())
    }

    @Test fun `what it cost to win somebody back`() {
        val offer = Offer(kind = "fixed", value = 500, audience = "lapsed", firstVisits = 4, cameBack = 2, givenAwayPence = Pence(2_000), redemptions = 4, maxRedemptions = 20, endsOn = "2027-01-31")
        assertEquals("£5 off · Not been in a while · ends 31 Jan 2027", offer.line("GBP"))
        assertEquals("2 of 4 new clients have been back since — about £10 each to acquire.", offer.verdict("GBP"))
        assertEquals("1 of 1 new client has been back since — about £20 each to acquire.", offer.copy(firstVisits = 1, cameBack = 1).verdict("GBP"))
        assertEquals("4 of 20 claimed", offer.claimed)
        assertEquals("£18 flat · Regulars", Offer(kind = "absolute", value = 1_800, audience = "returning").line("GBP"))
    }

    @Test fun `the form writes what chairtime wants`() {
        val draft = OfferDraft(name = " New year ", audience = "first_visit", kind = "percent", amount = "20", code = "newyear", quietOnly = true, endsOn = "2027-01-31")
        assertEquals(OfferWrite("New year", "NEWYEAR", "first_visit", null, "percent", 20, true, null, "2027-01-31"), draft.write())

        // Pounds for an amount off or a flat price; the days only for somebody who has not been in a while.
        val back = OfferDraft(name = "Come back", audience = "lapsed", kind = "fixed", amount = "7.50").write()
        assertEquals(750, back.value); assertEquals(120, back.lapsedAfterDays); assertNull(back.code); assertNull(back.endsOn)
        assertEquals(90, OfferDraft(name = "Come back", audience = "lapsed", lapsedAfterDays = "90", kind = "absolute", amount = "18").write().lapsedAfterDays)

        val json = ChairtimeJson.encodeToJsonElement(OfferWrite.serializer(), back).jsonObject
        assertEquals(setOf("name", "audience", "lapsedAfterDays", "kind", "value", "quietOnly", "weekdays"), json.keys)
    }

    @Test fun `the days ticked and where it stops go with it`() {
        val draft = OfferDraft(name = "Midweek", amount = "15", maxRedemptions = " 25 ").toggled(0).toggled(3).toggled(2).toggled(5).toggled(5)
        val write = draft.write()
        // Monday first, as chairtime sends them back; a day ticked twice is not ticked.
        assertEquals(listOf(2, 3, 0), write.weekdays); assertEquals(25, write.maxRedemptions)
        assertEquals(
            """{"name":"Midweek","audience":"first_visit","kind":"percent","value":15,"quietOnly":false,"weekdays":[2,3,0],"maxRedemptions":25}""",
            ChairtimeJson.encodeToString(OfferWrite.serializer(), write),
        )
        // Left alone: every day, no ceiling.
        OfferDraft(name = "A", amount = "20").write().let { assertTrue(it.weekdays.isEmpty()); assertNull(it.maxRedemptions) }
    }

    @Test fun `and says which field it could not read`() {
        fun field(draft: OfferDraft) = assertThrows(DraftProblem::class.java) { draft.write() }.field
        assertEquals("name", field(OfferDraft(amount = "20")))
        assertEquals("value", field(OfferDraft(name = "A", amount = "")))
        assertEquals("value", field(OfferDraft(name = "A", amount = "95")))
        assertEquals("value", field(OfferDraft(name = "A", kind = "fixed", amount = "0")))
        assertEquals("lapsedAfterDays", field(OfferDraft(name = "A", audience = "lapsed", lapsedAfterDays = "soon", amount = "20")))
        assertEquals("endsOn", field(OfferDraft(name = "A", amount = "20", endsOn = "next month")))
        assertEquals("maxRedemptions", field(OfferDraft(name = "A", amount = "20", maxRedemptions = "0")))
        assertEquals("maxRedemptions", field(OfferDraft(name = "A", amount = "20", maxRedemptions = "lots")))
    }
}

class CalendarLinkTest {
    private val clock = ShopClock("Europe/London")

    @Test fun `a link just made carries its address once`() {
        val link = read(CalendarLinkResponse.serializer(), "calendar")
        assertTrue(link.hasLink)
        assertTrue(link.url!!.endsWith(".ics")); assertTrue(link.webcalUrl!!.startsWith("webcal://"))
        assertEquals(30, link.refreshMinutes)
        assertEquals("What goes into it", link.words.heading); assertEquals(3, link.words.points.size)
        assertEquals("Made 22 Nov, 10:25 · no calendar has fetched it yet", link.status(clock))
    }

    @Test fun `one that is followed says when, and none says nothing`() {
        val made = Instant.parse("2026-11-22T10:25:00Z")
        val followed = CalendarLinkResponse(feed = CalendarLinkResponse.Feed(made, Instant.parse("2026-11-23T08:00:00Z")))
        assertEquals("Made 22 Nov, 10:25 · last fetched by a calendar 23 Nov, 08:00", followed.status(clock))
        assertNull(followed.url)
        val none = ChairtimeJson.decodeFromString(CalendarLinkResponse.serializer(), """{"feed":null,"url":null,"webcalUrl":null,"refreshMinutes":30,"words":{"intro":"","heading":"","points":[],"careful":""}}""")
        assertFalse(none.hasLink); assertNull(none.status(clock))
    }

    @Test fun `the other calendars are added by their own pages`() {
        assertEquals("https://calendar.google.com/calendar/render?cid=webcal%3A%2F%2Fwunderhand.com%2Fapi%2Fcalendar%2Fabc.ics", CalendarWords.google("webcal://wunderhand.com/api/calendar/abc.ics"))
        assertEquals("https://outlook.live.com/calendar/0/addfromweb?url=https%3A%2F%2Fwunderhand.com%2Fapi%2Fcalendar%2Fabc.ics", CalendarWords.outlook("https://wunderhand.com/api/calendar/abc.ics"))
    }
}

class RulesSwitchTest {
    @Test fun `the rules carry whether the team may cancel`() {
        assertTrue(read(BookingRules.serializer(), "rules").staffMayCancel)
        assertTrue(read(ShopResponse.serializer(), "shop").rules.staffMayCancel)
        val off = BookingRules(staffMayCancel = false)
        assertTrue(ChairtimeJson.encodeToString(BookingRules.serializer(), off).contains("\"staffMayCancel\":false"))
        // From a server that has not heard of it, the team may cancel, as they always could.
        assertTrue(ChairtimeJson.decodeFromString(BookingRules.serializer(), """{"slotIntervalMinutes":15}""").staffMayCancel)
    }
}
