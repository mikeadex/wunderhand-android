package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun <T> read(serializer: KSerializer<T>, name: String): T = ChairtimeJson.decodeFromString(serializer, Fixtures.text(name))

class VisitTest {
    @Test fun `a journey is priced, and says how far`() {
        val quote = read(VisitQuote.serializer(), "booking-visit")
        assertEquals("9 Bellenden Road", quote.address.addressLine1); assertEquals("Flat 1", quote.address.addressLine2)
        assertEquals("SE15 4QL", quote.address.postcode)
        assertEquals(Pence(700), quote.feePence); assertTrue(quote.hasFee)
        assertEquals("About 1 mile from London", quote.distance)
        assertEquals("up to 5 miles from London", quote.reach)
        assertEquals("£7", quote.fee("GBP"))
        assertFalse(quote.outsideArea); assertNull(quote.outside)
        assertEquals("Added to the total, as it would be if they booked online.", quote.explains(charged = true))
        assertEquals("Not charged. The booking says so, so the lower total is explained.", quote.explains(charged = false))
        assertEquals("Continue with £7 travel", quote.continueLabel(true, "GBP"))
        assertEquals("Continue, travel not charged", quote.continueLabel(false, "GBP"))
        assertEquals("Kept on this booking, so whoever is going knows which door to knock on. Checked against your travel area — up to 5 miles from London.", BookingWords.whereKept(quote))
    }

    @Test fun `too far is a warning, in chairtime's own words`() {
        val quote = read(VisitQuote.serializer(), "booking-visit-outside")
        assertTrue(quote.outsideArea)
        assertEquals("About 45 miles from London", quote.distance)
        assertTrue(quote.outside!!.startsWith("Ade Balogun travels up to 5 miles from London; this address is about 45 miles away."))
        // An older server's answer still says something.
        assertEquals("Outside your travel area (up to 5 miles from London). You can still book it — the area only limits what clients book online.", quote.copy(warning = null).outside)
    }

    @Test fun `a journey that costs nothing says why`() {
        val unmeasured = VisitQuote(aboutMiles = null)
        assertEquals("The distance could not be checked", unmeasured.distance)
        assertEquals("No travel fee", unmeasured.fee("GBP"))
        assertEquals("Nothing is added for travel on a journey that could not be measured.", unmeasured.explains(true))
        assertEquals("This journey is inside a band you charge nothing for.", VisitQuote(aboutMiles = 2).explains(true))
        assertEquals("Continue, no travel fee", unmeasured.continueLabel(true, "GBP"))
        assertNull(unmeasured.reach)
        assertEquals("up to 1 mile", VisitQuote(radiusMiles = 1.0).reach)
        assertEquals("up to 2.5 miles from Hackney", VisitQuote(radiusMiles = 2.5, area = "Hackney").reach)
    }

    @Test fun `where they were last visited fills the form, and nowhere leaves it empty`() {
        val last = read(ClientVisitAddressResponse.serializer(), "client-visit-address").address!!
        val draft = VisitDraft(last)
        assertEquals(VisitDraft("9 Bellenden Road", "Flat 1", "London", "SE15 4QL"), draft)
        assertEquals(last, draft.address())
        assertNull(read(ClientVisitAddressResponse.serializer(), "client-visit-address-none").address)
        assertTrue(VisitDraft().isEmpty)
    }

    @Test fun `the form says which part is missing`() {
        fun field(draft: VisitDraft) = assertThrows(DraftProblem::class.java) { draft.address() }.field
        assertEquals("addressLine1", field(VisitDraft(town = "London", postcode = "SE15 4QL")))
        assertEquals("town", field(VisitDraft(addressLine1 = "9 Bellenden Road", postcode = "SE15 4QL")))
        assertEquals("postcode", field(VisitDraft(addressLine1 = "9 Bellenden Road", town = "London")))
        assertNull(VisitDraft(" 9 Bellenden Road ", " ", "London", "se15 4ql").address().addressLine2)
    }

    @Test fun `a service is a visit by its own mode and the outlet's`() {
        assertEquals(VisitMode.AtClient, VisitMode.of("at_client", outletTravels = false))
        assertEquals(VisitMode.Either, VisitMode.of("either", outletTravels = true))
        assertEquals(VisitMode.AtVenue, VisitMode.of("either", outletTravels = false))
        assertEquals(VisitMode.AtVenue, VisitMode.of("at_venue", outletTravels = true))
        assertEquals(VisitMode.AtVenue, VisitMode.of(null, outletTravels = true))
        assertTrue(VisitMode.Either.asksWhere); assertFalse(VisitMode.AtVenue.asksWhere)
        assertEquals("Where are they?", BookingWords.whereTitle(VisitMode.AtClient))
        assertEquals("Where is it?", BookingWords.whereTitle(VisitMode.Either))
    }

    @Test fun `where comes after the extras and before the time`() {
        assertEquals(BookingStep.Where, BookingStep.current(serviceChosen = true, staffChosen = true, hasExtras = true, extrasSeen = true, whereNeeded = true))
        assertEquals(BookingStep.Extras, BookingStep.current(serviceChosen = true, staffChosen = true, hasExtras = true, extrasSeen = false, whereNeeded = true))
        assertEquals(BookingStep.Time, BookingStep.current(serviceChosen = true, staffChosen = true, hasExtras = false, extrasSeen = false, whereNeeded = true, whereDone = true))
        assertEquals(BookingStep.Time, BookingStep.current(serviceChosen = true, staffChosen = true, hasExtras = false, extrasSeen = false))
        assertEquals(listOf(BookingStep.Service, BookingStep.Person, BookingStep.Where, BookingStep.Time), BookingStep.steps(hasOutlet = false, hasExtras = false, hasWhere = true))
        assertEquals(3, BookingStep.Where.number(hasExtras = false, hasWhere = true))
        assertEquals(4, BookingStep.total(hasExtras = false, hasWhere = true))
        // A booking with nowhere to ask counts as it always did.
        assertEquals(3, BookingStep.total(hasExtras = false))
        assertEquals(3, BookingStep.Time.number(hasExtras = false))
    }

    @Test fun `a booking carries the address, and whether the journey is charged`() {
        val address = VisitAddress("9 Bellenden Road", null, "London", "SE15 4QL")
        val request = BookingRequest("s", "p", "2026-12-01T10:00:00.000Z", visit = BookingRequest.Visit(address, chargeTravelFee = false))
        val visit = ChairtimeJson.encodeToJsonElement(BookingRequest.serializer(), request).jsonObject.getValue("visit").jsonObject
        assertEquals(setOf("addressLine1", "town", "postcode", "chargeTravelFee"), visit.keys)
        assertEquals("false", visit.getValue("chargeTravelFee").toString())
        // At the shop, there is no visit at all.
        assertFalse("visit" in ChairtimeJson.encodeToJsonElement(BookingRequest.serializer(), BookingRequest("s", "p", "2026-12-01T10:00:00.000Z")).jsonObject)
    }

    @Test fun `an address is never printed`() {
        val address = VisitAddress("9 Bellenden Road", "Flat 1", "London", "SE15 4QL")
        val printed = listOf(
            address, VisitDraft(address), BookingRequest.Visit(address, true), VisitQuote(address = address),
            VisitQuoteRequest("s", null, null, address.addressLine1, address.addressLine2, address.town, address.postcode),
            BookingRequest("s", "p", "2026-12-01T10:00:00.000Z", visit = BookingRequest.Visit(address, true)),
        ).joinToString { it.toString() }
        for (part in listOf("Bellenden", "Flat 1", "SE15")) assertFalse(part in printed)
    }
}

class ChangeFlagsTest {
    @Test fun `an appointment says whether it is the viewer's to change`() {
        val response = read(AppointmentResponse.serializer(), "appointment")
        assertTrue(response.mayChange); assertTrue(response.mayCancel)
        assertEquals("This is Ade Balogun’s appointment", response.notYoursTitle)
    }

    @Test fun `one from before the flags existed reads as allowed`() {
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("appointment")).jsonObject
        val older = ChairtimeJson.decodeFromJsonElement(AppointmentResponse.serializer(), kotlinx.serialization.json.JsonObject(json - "mayChange" - "mayCancel"))
        assertTrue(older.mayChange); assertTrue(older.mayCancel)
        val nobodys = older.copy(appointment = older.appointment.copy(staffName = null))
        assertEquals("This is a colleague’s appointment", nobodys.notYoursTitle)
    }

    @Test fun `a visit's bill says what the journey added`() {
        val response = read(AppointmentResponse.serializer(), "appointment-visit")
        val bill = checkNotNull(response.bill)
        assertEquals(Pence(700), response.appointment.travelFeePence); assertEquals(Pence(700), bill.travelPence)
        // £40 and £7 of travel is the £47 beneath them; no discount is invented to explain the difference.
        assertEquals(
            listOf(Bill.Line(response.appointment.lineItems.single().name, "£40", muted = false), Bill.Line("Travel", "£7", muted = false)),
            bill.lines(response.appointment),
        )
        assertEquals(Pence(4_700), bill.chargePence); assertEquals(Pence(4_700), bill.toTakePence)

        // Between anything taken off and a deposit already paid, as on the web's sheet.
        val busy = bill.copy(discountPence = Pence(500), depositPaid = true).lines(response.appointment.copy(listPricePence = Pence(4_500), promotionName = "Midweek", depositPence = Pence(1_700)))
        assertEquals(listOf("£45", "−£5", "£7", "−£17"), busy.map { it.value })
        assertEquals(listOf(false, true, false, true), busy.map { it.muted })
    }

    @Test fun `at the shop, and from a chairtime that sends no journey, there is no travel line`() {
        val shop = read(AppointmentResponse.serializer(), "appointment")
        assertEquals(Pence(0), shop.bill?.travelPence)
        assertTrue(shop.bill!!.lines(shop.appointment).none { it.label == Bill.TRAVEL })

        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("appointment-visit")).jsonObject
        val older = ChairtimeJson.decodeFromJsonElement(AppointmentResponse.serializer(), kotlinx.serialization.json.JsonObject(
            json + ("bill" to kotlinx.serialization.json.JsonObject(json.getValue("bill").jsonObject - "travelPence")) +
                ("appointment" to kotlinx.serialization.json.JsonObject(json.getValue("appointment").jsonObject - "travelFeePence")),
        ))
        assertNull(older.appointment.travelFeePence); assertEquals(Pence(0), older.bill?.travelPence)
        assertTrue(older.bill!!.lines(older.appointment).none { it.label == Bill.TRAVEL })
    }

    @Test fun `history decodes from chairtime's own answer`() {
        val history = read(HistoryResponse.serializer(), "appointment-history")
        assertEquals(listOf("booked", "payment_recorded"), history.lines.map { it.kind })
        assertEquals("£60 deposit paid", history.lines.last().text)
        assertTrue(history.lines.all { it.showsTime && it.count == 1 })
        assertTrue(history.lines.first().at <= history.lines.last().at)
    }

    @Test fun `history is lines, the first of which has no time of its own`() {
        val history = ChairtimeJson.decodeFromString(
            HistoryResponse.serializer(),
            """{"lines":[{"at":"2026-11-01T09:00:00.000Z","text":"History starts here","kind":"history_start","count":1},
               {"at":"2026-11-02T10:30:00.000Z","text":"Moved to Tuesday 3 November, 2:00pm by Kit Alvarez","kind":"moved","count":1},
               {"at":"2026-11-02T10:31:00.000Z","text":"Confirmation email sent","kind":"email","count":1,"somethingNew":true}]}""",
        )
        assertEquals(3, history.lines.size)
        assertFalse(history.lines.first().showsTime); assertTrue(history.lines[1].showsTime)
        assertEquals("Confirmation email sent", history.lines.last().text)
        assertTrue(ChairtimeJson.decodeFromString(HistoryResponse.serializer(), """{"lines":[]}""").lines.isEmpty())
    }
}
