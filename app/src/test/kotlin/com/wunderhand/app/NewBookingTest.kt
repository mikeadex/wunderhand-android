package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.booking.NewBookingStart
import com.wunderhand.app.features.booking.NewBookingViewModel
import com.wunderhand.app.features.booking.Refusal
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.BookingCreated
import com.wunderhand.core.BookingServiceResponse
import com.wunderhand.core.BookingServicesResponse
import com.wunderhand.core.BookingSlotsResponse
import com.wunderhand.core.BookingStep
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Ineligible
import com.wunderhand.core.ShopClock
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NewBookingTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private val menu = ChairtimeJson.decodeFromString(BookingServicesResponse.serializer(), text("booking-services"))
    // The fixture is Fold's, which has several outlets; most of these tests are about a one-outlet shop.
    private val withOutlets = ChairtimeJson.decodeFromString(BookingServiceResponse.serializer(), text("booking-service"))
    private val detail = withOutlets.copy(outlets = null)
    private val times = ChairtimeJson.decodeFromString(BookingSlotsResponse.serializer(), text("booking-slots"))
    private val plain = detail.copy(addons = emptyList())
    private val handled = mutableListOf<ApiError>()

    private open inner class Shop(var service: BookingServiceResponse = detail) : StubApi() {
        var onBook: (Instant) -> BookingCreated = { BookingCreated("b1", "a1", it, "2026-09-16") }
        override suspend fun bookingServices() = menu
        override suspend fun bookingService(id: String, outletId: String?): BookingServiceResponse {
            calls += "service $id" + (outletId?.let { " at $it" } ?: ""); return service
        }
        override suspend fun bookingSlots(serviceId: String, staffId: String, addonIds: List<String>, from: String?, outletId: String?): BookingSlotsResponse {
            calls += "slots $staffId $addonIds $from" + (outletId?.let { " at $it" } ?: ""); return times
        }
        override suspend fun book(serviceId: String, staffId: String, startsAt: Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean, outletId: String?): BookingCreated {
            calls += "book $staffId $clientId $addonIds $overridePrerequisite" + (outletId?.let { " at $it" } ?: ""); return onBook(startsAt)
        }
    }

    private fun model(api: StubApi, start: NewBookingStart = NewBookingStart(), saved: SavedStateHandle = SavedStateHandle()) =
        NewBookingViewModel(start, api, ShopClock("Europe/London"), "GBP", handle = { handled += it }, saved = saved)

    private val someone get() = detail.staff.first()
    private val firstTime get() = times.days.first().slots.first().start

    @Test fun `it opens on the menu`() {
        val state = model(Shop()).state.value
        assertEquals(BookingStep.Service, state.step)
        assertEquals(menu.services, state.services)
    }

    @Test fun `tapping an answer moves on by itself, and extras wait for Continue`() {
        val api = Shop()
        val model = model(api)
        model.choose(menu.services.first())
        assertEquals(BookingStep.Person, model.state.value.step)
        model.choose(someone)
        assertEquals(BookingStep.Extras, model.state.value.step)
        assertTrue(api.calls.none { it.startsWith("slots") }) // not until the length is known
        model.toggle(detail.addons.first())
        model.continueFromExtras()
        assertEquals(BookingStep.Time, model.state.value.step)
        assertEquals("slots ${someone.id} [${detail.addons.first().id}] null", api.calls.last())
        assertEquals(4, model.state.value.step!!.number(model.state.value.hasExtras))
    }

    @Test fun `a service with no extras goes straight from who to when`() {
        val model = model(Shop(plain))
        model.choose(menu.services.first())
        model.choose(someone)
        assertEquals(BookingStep.Time, model.state.value.step)
        assertEquals(times, model.state.value.slots)
    }

    @Test fun `changing an extra throws away times that were for another length`() {
        val model = model(Shop())
        model.choose(menu.services.first()); model.choose(someone); model.continueFromExtras()
        model.select(firstTime)
        model.back() // to extras
        model.toggle(detail.addons.first())
        assertNull(model.state.value.slot)
        assertNull(model.state.value.slots)
    }

    @Test fun `back undoes one answer at a time, and the first step has nowhere to go`() {
        val model = model(Shop())
        assertFalse(model.back())
        model.choose(menu.services.first()); model.choose(someone); model.continueFromExtras()
        assertTrue(model.back()); assertEquals(BookingStep.Extras, model.state.value.step)
        assertTrue(model.back()); assertEquals(BookingStep.Person, model.state.value.step)
        assertTrue(model.back()); assertEquals(BookingStep.Service, model.state.value.step)
        assertFalse(model.back())
    }

    @Test fun `started from somebody's column, it already knows who — unless they do not do this`() {
        val theirs = model(Shop(plain), NewBookingStart(staffId = someone.id))
        theirs.choose(menu.services.first())
        assertEquals(BookingStep.Time, theirs.state.value.step)

        val notTheirs = model(Shop(plain), NewBookingStart(staffId = "somebody-who-only-does-tattoos"))
        notTheirs.choose(menu.services.first())
        assertEquals(BookingStep.Person, notTheirs.state.value.step)
    }

    @Test fun `a time tapped on the grid is kept only if it is one on offer`() {
        val offered = model(Shop(plain), NewBookingStart(staffId = someone.id, slot = firstTime))
        offered.choose(menu.services.first())
        assertEquals(firstTime, offered.state.value.slot)

        val notOffered = model(Shop(plain), NewBookingStart(staffId = someone.id, slot = Instant.parse("2001-01-01T03:07:00Z")))
        notOffered.choose(menu.services.first())
        assertNull(notOffered.state.value.slot)
    }

    @Test fun `booking sends who it is for, and says yes once`() {
        val api = Shop(plain)
        val model = model(api, NewBookingStart(clientId = "c1", clientName = "Ellis Warner"))
        model.choose(menu.services.first()); model.choose(someone); model.select(firstTime)
        var booked: BookingCreated? = null
        model.book { booked = it }
        assertEquals("a1", booked?.appointmentId)
        assertEquals("book ${someone.id} c1 [] false", api.calls.last())
        assertFalse(model.state.value.isBooking)
    }

    @Test fun `a time taken while choosing is said, dropped, and the times are asked for again`() {
        val api = Shop(plain).apply { onBook = { throw ApiError.SlotTaken("Someone got there first.") } }
        val model = model(api)
        model.choose(menu.services.first()); model.choose(someone); model.select(firstTime)
        var booked = false
        model.book { booked = true }
        assertFalse(booked)
        assertEquals(Refusal.Taken(firstTime), model.state.value.refusal)
        assertNull(model.state.value.slot)
        assertEquals(2, api.calls.count { it.startsWith("slots") })
        // Picking another time is the answer to it.
        model.select(times.days.first().slots[1].start)
        assertNull(model.state.value.refusal)
    }

    @Test fun `an age limit is said and cannot be ticked past, but a consultation done elsewhere can`() {
        val api = Shop(plain).apply { onBook = { throw ApiError.Ineligible("too_young", "This service is for over-18s.") } }
        val model = model(api, NewBookingStart(clientId = "c1"))
        model.choose(menu.services.first()); model.choose(someone); model.select(firstTime)
        model.book { }
        val refused = model.state.value.refusal as Refusal.Rule
        assertEquals(Ineligible.TooYoung, refused.rule)
        assertFalse(refused.rule.isOverridable)

        api.onBook = { throw ApiError.Ineligible("missing_prerequisite", "They need a consultation first.") }
        model.book { }
        assertTrue((model.state.value.refusal as Refusal.Rule).rule.isOverridable)
        api.onBook = { BookingCreated("b1", "a1", it, "2026-09-16") }
        model.setOverride(true)
        model.book { }
        assertEquals("book ${someone.id} c1 [] true", api.calls.last())
    }

    @Test fun `a rule this build has not heard of is still said, in chairtime's words`() {
        val api = Shop(plain).apply { onBook = { throw ApiError.Ineligible("needs_a_licence_check", "Check their licence first.") } }
        val model = model(api)
        model.choose(menu.services.first()); model.choose(someone); model.select(firstTime)
        model.book { }
        assertEquals(Refusal.Problem("Check their licence first."), model.state.value.refusal)
    }

    @Test fun `later days start the day after the last one shown`() {
        val api = Shop(plain)
        val model = model(api)
        model.choose(menu.services.first()); model.choose(someone)
        model.laterDays()
        assertTrue(api.calls.last().endsWith(com.wunderhand.core.IsoDay.shift(times.days.last().isoDate, 1)))
    }

    @Test fun `a phone call in the middle does not lose the booking`() {
        val saved = SavedStateHandle()
        val first = model(Shop(), saved = saved)
        first.choose(menu.services.first()); first.choose(someone); first.toggle(detail.addons.first()); first.continueFromExtras(); first.select(firstTime)

        val back = model(Shop(), saved = saved).state.value
        assertEquals(BookingStep.Time, back.step)
        assertEquals(someone.id, back.staffId)
        assertEquals(listOf(detail.addons.first().id), back.addonIds)
        assertEquals(firstTime, back.slot)
        assertEquals(times, back.slots)
    }

    @Test fun `a session that has ended is the app's business`() {
        val api = object : StubApi() { override suspend fun bookingServices(): BookingServicesResponse = throw ApiError.Unauthorized("Sign in again.") }
        val state = model(api).state.value
        assertTrue(handled.single() is ApiError.Unauthorized)
        assertNull(state.loadProblem)
    }

    @Test fun `at a shop with more than one outlet, which comes before who, and rides on every request`() {
        val outlets = withOutlets.outlets!!
        assertTrue(outlets.size > 1)
        val api = Shop(withOutlets)
        val model = model(api)
        model.choose(menu.services.first { it.id == withOutlets.service.id })
        assertEquals(BookingStep.Outlet, model.state.value.step)
        assertEquals(2, model.state.value.step!!.number(model.state.value.hasExtras, model.state.value.needsOutlet))

        val chosen = outlets.first()
        model.choose(chosen)
        assertEquals(BookingStep.Person, model.state.value.step)
        assertEquals("service ${withOutlets.service.id} at ${chosen.id}", api.calls.last())

        model.choose(someone); model.continueFromExtras()
        assertEquals("slots ${someone.id} [] null at ${chosen.id}", api.calls.last())
        model.select(firstTime)
        model.book {}
        assertEquals("book ${someone.id} null [] false at ${chosen.id}", api.calls.last())

        // Back from who goes to which, not to the menu.
        assertTrue(model.back()); assertTrue(model.back()); assertTrue(model.back())
        assertEquals(BookingStep.Outlet, model.state.value.step)
        assertEquals(null, model.state.value.outletId)
    }
}
