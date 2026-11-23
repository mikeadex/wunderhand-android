package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.booking.NewBookingStart
import com.wunderhand.app.features.booking.NewBookingViewModel
import com.wunderhand.app.features.booking.Refusal
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.BookingCreated
import com.wunderhand.core.BookingRequest
import com.wunderhand.core.BookingServiceResponse
import com.wunderhand.core.BookingServicesResponse
import com.wunderhand.core.BookingSlotsResponse
import com.wunderhand.core.BookingStep
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.ClientVisitAddressResponse
import com.wunderhand.core.ShopClock
import com.wunderhand.core.VisitAddress
import com.wunderhand.core.VisitDraft
import com.wunderhand.core.VisitMode
import com.wunderhand.core.VisitQuote
import com.wunderhand.core.VisitQuoteRequest
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The where step of a new booking: a home visit's address, the fee for the journey, and a warning that does not stop it. */
@OptIn(ExperimentalCoroutinesApi::class)
class VisitBookingTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private val menu = ChairtimeJson.decodeFromString(BookingServicesResponse.serializer(), text("booking-services"))
    private val fold = ChairtimeJson.decodeFromString(BookingServiceResponse.serializer(), text("booking-service"))
    private val times = ChairtimeJson.decodeFromString(BookingSlotsResponse.serializer(), text("booking-slots"))
    private val inside = ChairtimeJson.decodeFromString(VisitQuote.serializer(), text("booking-visit"))
    private val outside = ChairtimeJson.decodeFromString(VisitQuote.serializer(), text("booking-visit-outside"))
    private val lastVisit = ChairtimeJson.decodeFromString(ClientVisitAddressResponse.serializer(), text("client-visit-address"))
    private val handled = mutableListOf<ApiError>()

    private val travelling = BookingServiceResponse.Outlet("o1", "Peckham", "Peckham, London", travels = true)
    private val staying = BookingServiceResponse.Outlet("o2", "Dalston Lane", "Hackney, London", travels = false)

    /** The fixture's service, with no extras, done the given way from the given outlets. */
    private fun service(mode: String?, outlets: List<BookingServiceResponse.Outlet>? = listOf(travelling)) =
        fold.copy(service = fold.service.copy(locationMode = mode), addons = emptyList(), outlets = outlets)

    private open inner class Shop(var service: BookingServiceResponse) : StubApi() {
        var quote: VisitQuote = inside
        var last: ClientVisitAddressResponse = ClientVisitAddressResponse(null)
        var asked: VisitQuoteRequest? = null
        var booked: BookingRequest.Visit? = null
        var bookedOutlet: String? = null
        override suspend fun bookingServices() = menu
        override suspend fun bookingService(id: String, outletId: String?): BookingServiceResponse { calls += "service" + (outletId?.let { " at $it" } ?: ""); return service }
        override suspend fun bookingSlots(serviceId: String, staffId: String, addonIds: List<String>, from: String?, outletId: String?): BookingSlotsResponse { calls += "slots"; return times }
        override suspend fun clientVisitAddress(clientId: String): ClientVisitAddressResponse { calls += "lastVisit $clientId"; return last }
        override suspend fun visitQuote(request: VisitQuoteRequest): VisitQuote { calls += "quote"; asked = request; return quote }
        override suspend fun book(serviceId: String, staffId: String, startsAt: Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean, outletId: String?, visit: BookingRequest.Visit?): BookingCreated {
            calls += "book"; booked = visit; bookedOutlet = outletId
            return BookingCreated("b1", "a1", startsAt, "2026-09-16")
        }
    }

    private fun model(api: StubApi, start: NewBookingStart = NewBookingStart(), saved: SavedStateHandle = SavedStateHandle()) =
        NewBookingViewModel(start, api, ShopClock("Europe/London"), "GBP", handle = { handled += it }, saved = saved)

    private val someone get() = fold.staff.first()
    private val firstTime get() = times.days.first().slots.first().start
    private fun NewBookingViewModel.toWhere() { choose(menu.services.first()); choose(someone) }
    private fun NewBookingViewModel.type(address: VisitDraft = VisitDraft("9 Bellenden Road", "Flat 1", "London", "SE15 4QL")) = typeAddress { address }

    @Test fun `a service done at the shop never asks where`() {
        for (shop in listOf(service("at_venue"), service(null), service("either", listOf(staying)), service("either", null))) {
            val api = Shop(shop)
            val model = model(api).apply { toWhere() }
            assertEquals(BookingStep.Time, model.state.value.step)
            assertEquals(VisitMode.AtVenue, model.state.value.visitMode)
            assertEquals(3, model.state.value.stepTotal)
            model.select(firstTime); model.book {}
            assertNull(api.booked); assertNull(api.bookedOutlet)
        }
    }

    @Test fun `one done either way asks, and at the shop goes straight on to the times`() {
        val api = Shop(service("either"))
        val model = model(api).apply { toWhere() }
        assertEquals(BookingStep.Where, model.state.value.step)
        assertEquals(3, model.state.value.stepNumber); assertEquals(4, model.state.value.stepTotal)
        assertFalse(model.state.value.isVisit)
        assertTrue(api.calls.none { it == "slots" })

        model.where(atClient = false)
        assertEquals(BookingStep.Time, model.state.value.step)
        assertEquals("slots", api.calls.last())
        model.select(firstTime); model.book {}
        assertNull(api.booked)

        // Back from the times un-answers it.
        assertTrue(model.back())
        assertEquals(BookingStep.Where, model.state.value.step)
        assertNull(model.state.value.visit.atClient)
    }

    @Test fun `a visit is priced before it is booked, and booked with the address as chairtime tidied it`() {
        val api = Shop(service("either"))
        val model = model(api, NewBookingStart(clientId = "c1", clientName = "Ada Nwosu")).apply { toWhere() }
        model.where(atClient = true)
        assertTrue(model.state.value.isVisit)
        assertEquals(BookingStep.Where, model.state.value.step)
        // No fee seen yet: there is no way on.
        assertNull(model.continueFromWhere())

        model.type(VisitDraft(" 9 bellenden road ", "", "london", "se154ql"))
        model.checkAddress()
        assertEquals(VisitQuoteRequest(menu.services.first().id, "o1", someone.id, "9 bellenden road", null, "london", "se154ql"), api.asked)
        val visit = model.state.value.visit
        assertEquals(inside, visit.quote)
        assertEquals(VisitDraft("9 Bellenden Road", "Flat 1", "London", "SE15 4QL"), visit.draft)
        assertTrue(visit.chargeTravel)
        assertEquals(BookingStep.Where, model.state.value.step)

        model.continueFromWhere()
        assertEquals(BookingStep.Time, model.state.value.step)
        assertEquals(4, model.state.value.stepNumber)
        model.select(firstTime); model.book {}
        assertEquals(BookingRequest.Visit("9 Bellenden Road", "Flat 1", "London", "SE15 4QL", chargeTravelFee = true), api.booked)
        // From the outlet it was quoted from, named even though there was only the one.
        assertEquals("o1", api.bookedOutlet)
    }

    @Test fun `a service done only at the client's goes straight to the address`() {
        val api = Shop(service("at_client", listOf(staying)))
        val model = model(api).apply { toWhere() }
        assertEquals(VisitMode.AtClient, model.state.value.visitMode)
        assertEquals(BookingStep.Where, model.state.value.step)
        assertTrue(model.state.value.isVisit)
        // A walk-in has nobody to have been visited before.
        assertTrue(api.calls.none { it.startsWith("lastVisit") })
        assertTrue(model.state.value.visit.draft.isEmpty)
    }

    @Test fun `their last home visit fills the form, once, and typing makes it theirs`() {
        val api = Shop(service("at_client")).apply { last = lastVisit }
        val model = model(api, NewBookingStart(clientId = "c1", clientName = "Ada Nwosu")).apply { toWhere() }
        assertEquals("lastVisit c1", api.calls.last())
        assertEquals(VisitDraft(lastVisit.address!!), model.state.value.visit.draft)
        assertTrue(model.state.value.visit.isPrefilled)
        // A prefill is not a checked address: the fee still has to be asked for.
        assertNull(model.state.value.visit.quote)

        model.typeAddress { it.copy(addressLine2 = "Flat 2") }
        assertFalse(model.state.value.visit.isPrefilled)
        // Back to who and forward again: what was typed stays, and it is not asked for a second time.
        model.back(); model.choose(someone)
        assertEquals("Flat 2", model.state.value.visit.draft.addressLine2)
        assertEquals(1, api.calls.count { it.startsWith("lastVisit") })
    }

    @Test fun `no last visit leaves the form empty, and a failure to ask says nothing`() {
        val none = Shop(service("at_client"))
        assertTrue(model(none, NewBookingStart(clientId = "c1")).apply { toWhere() }.state.value.visit.draft.isEmpty)

        val failing = object : Shop(service("at_client")) { override suspend fun clientVisitAddress(clientId: String) = throw ApiError.Server("Something went wrong.") }
        val model = model(failing, NewBookingStart(clientId = "c1")).apply { toWhere() }
        assertTrue(model.state.value.visit.draft.isEmpty)
        assertNull(model.state.value.visit.problem); assertNull(model.state.value.loadProblem)
        assertEquals(BookingStep.Where, model.state.value.step)
    }

    @Test fun `outside the travel area is a warning, and the booking still goes ahead`() {
        val api = Shop(service("at_client")).apply { quote = outside }
        val model = model(api).apply { toWhere(); type(); checkAddress() }
        val quote = model.state.value.visit.quote!!
        assertTrue(quote.outsideArea)
        assertNotNull(quote.outside)
        assertNull(model.state.value.visit.problem)

        model.continueFromWhere()
        assertEquals(BookingStep.Time, model.state.value.step)
        model.select(firstTime)
        var made: BookingCreated? = null
        model.book { made = it }
        assertNotNull(made)
        assertEquals("BN1 1AA", api.booked?.postcode)
    }

    @Test fun `the fee can be dropped, and the booking says so`() {
        val api = Shop(service("at_client"))
        val model = model(api).apply { toWhere(); type(); checkAddress() }
        model.chargeTravel(false)
        model.continueFromWhere(); model.select(firstTime); model.book {}
        assertEquals(false, api.booked?.chargeTravelFee)
    }

    @Test fun `typing after the check throws the fee away, so what is booked is what was priced`() {
        val api = Shop(service("at_client"))
        val model = model(api).apply { toWhere(); type(); checkAddress(); chargeTravel(false) }
        model.typeAddress { it.copy(postcode = "BN1 1AA") }
        assertNull(model.state.value.visit.quote)
        assertNull(model.continueFromWhere())
        assertEquals(BookingStep.Where, model.state.value.step)
        // A new check charges by default again: it is a different journey.
        model.checkAddress()
        assertTrue(model.state.value.visit.chargeTravel)
    }

    @Test fun `a part left out is named before anything is asked, and chairtime's refusal points at its field`() {
        val api = Shop(service("at_client"))
        val model = model(api).apply { toWhere() }
        model.type(VisitDraft("9 Bellenden Road", "", "London", ""))
        assertNull(model.checkAddress())
        assertEquals("postcode", model.state.value.visit.problem?.field)
        assertTrue(api.calls.none { it == "quote" })

        val refusing = object : Shop(service("at_client")) { override suspend fun visitQuote(request: VisitQuoteRequest) = throw ApiError.Validation("We could not find that postcode.", "postcode") }
        val other = model(refusing).apply { toWhere(); type(); checkAddress() }
        assertEquals("We could not find that postcode.", other.state.value.visit.problem?.text)
        assertEquals("postcode", other.state.value.visit.problem?.field)
        assertFalse(other.state.value.visit.isChecking)
        assertNull(other.state.value.visit.quote)
    }

    @Test fun `an address the booking itself refuses goes back to where it is typed`() {
        val api = object : Shop(service("at_client")) {
            override suspend fun book(serviceId: String, staffId: String, startsAt: Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean, outletId: String?, visit: BookingRequest.Visit?) =
                throw ApiError.Validation("We could not find that postcode.", "visit.postcode")
        }
        val model = model(api).apply { toWhere(); type(); checkAddress(); continueFromWhere(); select(firstTime) }
        model.book {}
        assertEquals(BookingStep.Where, model.state.value.step)
        assertEquals("postcode", model.state.value.visit.problem?.field)
        assertFalse(model.state.value.isBooking)
        // Anything else chairtime will not take is said on the time step, as before.
        val other = object : Shop(service("at_venue")) {
            override suspend fun book(serviceId: String, staffId: String, startsAt: Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean, outletId: String?, visit: BookingRequest.Visit?) =
                throw ApiError.Validation("Pick a time in the future.", "startsAt")
        }
        val plain = model(other).apply { toWhere(); select(firstTime) }
        plain.book {}
        assertEquals(Refusal.Problem("Pick a time in the future."), plain.state.value.refusal)
    }

    @Test fun `where is asked after the outlet, and by that outlet`() {
        val api = Shop(service("either", listOf(staying, travelling)))
        val model = model(api)
        model.choose(menu.services.first())
        assertEquals(BookingStep.Outlet, model.state.value.step)
        model.choose(staying); model.choose(someone)
        // Dalston Lane does not travel: nothing to ask.
        assertEquals(BookingStep.Time, model.state.value.step)

        model.back(); model.back()
        assertEquals(BookingStep.Outlet, model.state.value.step)
        model.choose(travelling); model.choose(someone)
        assertEquals(BookingStep.Where, model.state.value.step)
        assertEquals(4, model.state.value.stepNumber); assertEquals(5, model.state.value.stepTotal)
        model.where(atClient = true); model.type(); model.checkAddress()
        assertEquals("o1", api.asked?.outletId)
    }

    @Test fun `back from where un-answers it, and from the times comes back to the fee`() {
        val api = Shop(service("either"))
        val model = model(api).apply { toWhere(); where(atClient = true); type(); checkAddress(); continueFromWhere() }
        assertEquals(BookingStep.Time, model.state.value.step)
        assertTrue(model.back())
        assertEquals(BookingStep.Where, model.state.value.step)
        assertNotNull(model.state.value.visit.quote)
        assertTrue(model.state.value.isVisit)
        assertTrue(model.back())
        assertEquals(BookingStep.Person, model.state.value.step)
        assertNull(model.state.value.visit.atClient)
        // Who is going may change, so what the journey was quoted does not stand; the address typed does.
        assertNull(model.state.value.visit.quote)
        assertEquals("SE15 4QL", model.state.value.visit.draft.postcode)
    }

    @Test fun `the address is never among what the system is asked to keep`() {
        val saved = SavedStateHandle()
        val api = Shop(service("either"))
        model(api, saved = saved).apply { toWhere(); where(atClient = true); type(); checkAddress(); continueFromWhere(); select(firstTime) }
        val kept = saved.keys().associateWith { saved.get<Any?>(it).toString() }
        for (part in listOf("Bellenden", "Flat 1", "SE15")) assertTrue(kept.values.none { part in it })
        assertEquals("true", kept["atClient"])

        // Handed back by the system: which way is remembered, the address is asked for again.
        val back = model(Shop(service("either")), saved = saved)
        assertEquals(BookingStep.Where, back.state.value.step)
        assertTrue(back.state.value.isVisit)
        assertTrue(back.state.value.visit.draft.isEmpty)
    }

    @Test fun `a visit address does not print itself`() {
        val model = model(Shop(service("at_client"))).apply { toWhere(); type(); checkAddress() }
        val printed = model.state.value.visit.toString() + VisitAddress("9 Bellenden Road", "Flat 1", "London", "SE15 4QL")
        for (part in listOf("Bellenden", "Flat 1", "SE15")) assertFalse(part in printed)
    }
}
