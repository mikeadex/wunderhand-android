package com.wunderhand.network

import com.wunderhand.core.BookingRequest
import com.wunderhand.core.OfferWrite
import com.wunderhand.core.VisitAddress
import com.wunderhand.core.VisitQuoteRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The 1.3 calls: consent, quiet times, offers, a visit's address, the calendar link and history. */
class ShopMoreCallsTest {
    private val server = MockWebServer()
    private val logged = mutableListOf<String>()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {}, log = { logged += it })
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun sent() = server.takeRequest()

    @Test fun `consent is read whole and saved a kind at a time`() = runTest {
        reply(200, fixture("consent")); reply(200, fixture("consent-document"))
        val client = client()
        assertEquals(5, client.consent().documents.size)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/shop/consent", it.url.encodedPath) }

        val saved = client.saveConsent("aftercare", "Keep it dry.")
        assertEquals(6, saved.version)
        sent().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/shop/consent/aftercare", it.url.encodedPath)
            assertEquals("""{"body":"Keep it dry."}""", it.body!!.utf8())
        }
    }

    @Test fun `wording too short to mean anything is refused against the box`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"Not saved. Consent wording has to say something.","field":"body"}}""")
        val error = runCatching { client().saveConsent("aftercare", "") }.exceptionOrNull() as ApiError.Validation
        assertEquals("body", error.field)
    }

    @Test fun `quiet times are taken, switched and stopped, each answering with the screen`() = runTest {
        repeat(4) { reply(200, fixture("quiet-times-busy")) }
        val client = client()
        assertEquals(4, client.quietTimes().suggestions.size)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/shop/quiet-times", it.url.encodedPath) }

        client.acceptQuietTime(2, "m", 25)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/quiet-times", it.url.encodedPath)
            assertEquals("""{"weekday":2,"part":"m","percent":25}""", it.body!!.utf8())
        }

        client.showSaving("r1", false)
        sent().let {
            assertEquals("PATCH", it.method); assertEquals("/api/v1/shop/quiet-times/r1", it.url.encodedPath)
            assertEquals("""{"showSaving":false}""", it.body!!.utf8())
        }

        assertEquals(1, client.stopQuietTime("r1").rules.size)
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/quiet-times/r1", it.url.encodedPath) }
    }

    @Test fun `hours that already have a lower price say which rule is in the way`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"Those hours already have a lower price — Wednesday mornings. Stop that one first.","field":"part"}}""")
        val error = runCatching { client().acceptQuietTime(3, "m", 25) }.exceptionOrNull() as ApiError.Validation
        assertTrue(error.message!!.contains("Wednesday mornings"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `an offer is started and stopped, and the list comes back`() = runTest {
        reply(200, fixture("offers")); reply(200, fixture("offers-started")); reply(200, fixture("offers"))
        val client = client()
        assertEquals(1, client.offers().offers.size)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/shop/offers", it.url.encodedPath) }

        val started = client.startOffer(OfferWrite("ZZ Test offer", "ZZTESTOFFER", "first_visit", null, "percent", 20, quietOnly = true))
        assertEquals("ZZ Test offer", started.offers.first().name)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/offers", it.url.encodedPath)
            assertEquals("""{"name":"ZZ Test offer","code":"ZZTESTOFFER","audience":"first_visit","kind":"percent","value":20,"quietOnly":true,"weekdays":[]}""", it.body!!.utf8())
        }
        // The seeded one, as chairtime now sends it: the days it runs on.
        assertEquals(listOf(2, 3), started.offers.last().weekdays)

        client.stopOffer("o1")
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/offers/o1", it.url.encodedPath) }
    }

    @Test fun `an address is priced in a body, and never in the address of the request`() = runTest {
        reply(200, fixture("booking-visit-outside"))
        val quote = client().visitQuote(VisitQuoteRequest("s1", "o1", "p1", "9 Bellenden Road", "Flat 1", "London", "BN1 1AA"))
        assertTrue(quote.outsideArea)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/booking/visit", it.url.toString().substringAfter(server.url("/").toString().trimEnd('/')))
            assertEquals("""{"serviceId":"s1","outletId":"o1","staffId":"p1","addressLine1":"9 Bellenden Road","addressLine2":"Flat 1","town":"London","postcode":"BN1 1AA"}""", it.body!!.utf8())
        }
    }

    @Test fun `a postcode that cannot be found is refused against its field`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"We could not find that postcode.","field":"postcode"}}""")
        val error = runCatching { client().visitQuote(VisitQuoteRequest("s1", null, null, "9 Bellenden Road", null, "London", "ZZ1 1ZZ")) }.exceptionOrNull() as ApiError.Validation
        assertEquals("postcode", error.field)
        // A service that is not done at a client's says so in chairtime's own sentence.
        reply(422, """{"error":{"code":"visit_not_offered","message":"Skin fade is done at the shop."}}""")
        val notOffered = runCatching { client().visitQuote(VisitQuoteRequest("s1", null, null, "9 Bellenden Road", null, "London", "SE15 4QL")) }.exceptionOrNull() as ApiError.Validation
        assertEquals("Skin fade is done at the shop.", notOffered.message)
    }

    @Test fun `an answer about a visit that cannot be read is not printed`() = runTest {
        reply(200, """{"address":{"addressLine1":"9 Bellenden Road","town":"London","postcode":"SE15 4QL"},"feePence":"a lot"}""")
        assertTrue(runCatching { client().visitQuote(VisitQuoteRequest("s1", null, null, "9 Bellenden Road", null, "London", "SE15 4QL")) }.exceptionOrNull() is ApiError.Server)
        assertEquals(1, logged.size)
        assertFalse(logged.single().contains("Bellenden")); assertFalse(logged.single().contains("SE15"))
    }

    @Test fun `where a client was last visited is asked by who they are`() = runTest {
        reply(200, fixture("client-visit-address")); reply(200, fixture("client-visit-address-none"))
        val client = client()
        assertEquals("SE15 4QL", client.clientVisitAddress("c1").address?.postcode)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/clients/c1/visit-address", it.url.encodedPath); assertNull(it.url.query) }
        assertNull(client.clientVisitAddress("c2").address)
    }

    @Test fun `a home visit is booked with its address and whether the journey is charged`() = runTest {
        reply(200, """{"bookingId":"b1","appointmentId":"a1","startsAt":"2026-09-16T10:15:00.000Z","date":"2026-09-16"}""")
        val visit = BookingRequest.Visit(VisitAddress("9 Bellenden Road", null, "London", "SE15 4QL"), chargeTravelFee = false)
        client().book("s1", "p1", java.time.Instant.parse("2026-09-16T10:15:00Z"), "c1", emptyList(), false, outletId = "o1", visit = visit)
        sent().let {
            assertEquals("/api/v1/bookings", it.url.encodedPath); assertNull(it.url.query)
            assertEquals(
                """{"serviceId":"s1","staffId":"p1","startsAt":"2026-09-16T10:15:00.000Z","clientId":"c1","addonIds":[],"overridePrerequisite":false,"outletId":"o1","visit":{"addressLine1":"9 Bellenden Road","town":"London","postcode":"SE15 4QL","chargeTravelFee":false}}""",
                it.body!!.utf8(),
            )
        }
    }

    @Test fun `an address the booking refuses comes back against the visit's own field`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"Add the postcode.","field":"visit.postcode"}}""")
        val visit = BookingRequest.Visit(VisitAddress("9 Bellenden Road", null, "London", ""), true)
        val error = runCatching { client().book("s1", "p1", java.time.Instant.parse("2026-09-16T10:15:00Z"), null, emptyList(), false, visit = visit) }.exceptionOrNull() as ApiError.Validation
        assertEquals("visit.postcode", error.field)
        assertEquals(1, server.requestCount)
    }

    @Test fun `the calendar link is read, made and switched off`() = runTest {
        val none = """{"feed":null,"url":null,"webcalUrl":null,"refreshMinutes":30,"words":{"intro":"i","heading":"h","points":["p"],"careful":"c"}}"""
        reply(200, none); reply(200, fixture("calendar")); reply(200, none)
        val client = client()
        assertFalse(client.calendarLink().hasLink)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/me/calendar", it.url.encodedPath) }

        val made = client.makeCalendarLink()
        assertTrue(made.hasLink); assertTrue(made.webcalUrl!!.startsWith("webcal://"))
        sent().let { assertEquals("POST", it.method); assertEquals("/api/v1/me/calendar", it.url.encodedPath); assertEquals("", it.body?.utf8().orEmpty()) }

        assertFalse(client.stopCalendarLink().hasLink)
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/me/calendar", it.url.encodedPath) }
    }

    @Test fun `making a link is never sent twice`() = runTest {
        reply(503, "")
        assertTrue(runCatching { client().makeCalendarLink() }.exceptionOrNull() is ApiError)
        assertEquals(1, server.requestCount)
    }

    @Test fun `history is asked for by the appointment`() = runTest {
        reply(200, fixture("appointment-history"))
        assertEquals("£60 deposit paid", client().history("a1").lines.last().text)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/appointments/a1/history", it.url.encodedPath) }
    }

    @Test fun `the rules are saved with whether the team may cancel`() = runTest {
        reply(200, fixture("rules"))
        val saved = client().saveRules(com.wunderhand.core.BookingRules(staffMayCancel = false))
        assertTrue(saved.staffMayCancel)
        assertTrue(sent().body!!.utf8().endsWith(""","staffMayCancel":false}"""))
    }
}
