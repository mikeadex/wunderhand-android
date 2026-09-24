package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookingTest {
    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, name: String) = ChairtimeJson.decodeFromString(serializer, Fixtures.text(name))

    @Test fun `the menu decodes from chairtime's own response`() {
        val menu = decode(BookingServicesResponse.serializer(), "booking-services")
        assertTrue(menu.services.any { it.name == "Skin fade" && it.minutes == 45 })
    }

    @Test fun `a service brings its people, extras and rules`() {
        val body = decode(BookingServiceResponse.serializer(), "booking-service")
        assertTrue(body.staff.any { it.name == "Kit Alvarez" && it.initials == "KA" })
        assertTrue(body.addons.any { it.pricePence.value > 0 })
        assertNull(body.requirements.minAgeYears)
    }

    @Test fun `times come priced, with the standard beside them`() {
        val slots = decode(BookingSlotsResponse.serializer(), "booking-slots")
        val first = slots.days.first().slots.first()
        assertTrue(first.end > first.start)
        assertEquals(slots.standard.pence, first.price.listPence)
        assertEquals(first, slots.slot(first.start))
    }

    @Test fun `prices read as the menu writes them`() {
        fun service(mode: String, price: Int?, hourly: Int?) = BookingService("s", "S", pricingMode = mode, pricePence = price?.let(::Pence), hourlyRatePence = hourly?.let(::Pence), minutes = 45)
        assertEquals("£28", service("fixed", 2800, null).priceLabel("GBP"))
        assertEquals("Free", service("fixed", 0, null).priceLabel("GBP"))
        assertEquals("from £40", service("from", 4000, null).priceLabel("GBP"))
        assertEquals("£15 each", service("per_person", 1500, null).priceLabel("GBP"))
        assertEquals("£90/hr", service("hourly", null, 9000).priceLabel("GBP"))
        // A way of pricing this build has not heard of is still a price.
        assertEquals("£28", service("by_the_inch", 2800, null).priceLabel("GBP"))
    }

    @Test fun `a ranged sitting shows its range`() {
        val sitting = BookingService("s", "Tattoo sitting", pricingMode = "hourly", hourlyRatePence = Pence(9000), minutes = 300, durationMode = "ranged", minMinutes = 180, maxMinutes = 480)
        assertEquals("3h–8h", sitting.durationLabel)
    }

    @Test fun `the step follows what has been chosen`() {
        assertEquals(BookingStep.Service, BookingStep.current(false, true, false, false))
        assertEquals(BookingStep.Person, BookingStep.current(true, false, true, false))
        assertEquals(BookingStep.Extras, BookingStep.current(true, true, true, false))
        assertEquals(BookingStep.Time, BookingStep.current(true, true, true, true))
        // No extras: straight from the person to the time, which is step 3 of 3.
        val time = BookingStep.current(true, true, false, false)
        assertEquals(BookingStep.Time, time)
        assertEquals(3, time.number(hasExtras = false))
        assertEquals(3, BookingStep.total(hasExtras = false))
        assertEquals(4, BookingStep.Time.number(hasExtras = true))
    }

    @Test fun `the outlet is a step only when there is a choice`() {
        assertEquals(BookingStep.Outlet, BookingStep.current(true, false, false, false, outletNeeded = true, outletChosen = false))
        assertEquals(BookingStep.Person, BookingStep.current(true, false, false, false, outletNeeded = true, outletChosen = true))
        assertEquals(BookingStep.Person, BookingStep.current(true, false, false, false))
        assertEquals(5, BookingStep.total(hasExtras = true, hasOutlet = true))
        assertEquals(4, BookingStep.total(hasExtras = false, hasOutlet = true))
        assertEquals(3, BookingStep.Person.number(hasExtras = false, hasOutlet = true))
        assertEquals(4, BookingStep.Time.number(hasExtras = false, hasOutlet = true))
        assertEquals(3, BookingStep.Time.number(hasExtras = false))
    }

    @Test fun `a service names the outlets where it is done`() {
        // Fold Barbers has more than one outlet, so the outlet is a step.
        val all = ChairtimeJson.decodeFromString(BookingServiceResponse.serializer(), Fixtures.text("booking-service"))
        assertTrue(all.needsOutlet)
        // Asked with ?outlet=: every outlet still listed, only the people at that one offered.
        val at = ChairtimeJson.decodeFromString(BookingServiceResponse.serializer(), Fixtures.text("booking-service-outlets"))
        assertEquals(all.outlets!!.size, at.outlets!!.size)
        assertTrue(at.outlets!!.any { it.name == "ZZ Mobile Booking Outlet Two" && it.area == "London" && it.travels })
        assertEquals(1, at.staff.size)
    }

    @Test fun `only a prerequisite can be answered by the pro`() {
        assertTrue(Ineligible.MissingPrerequisite.isOverridable)
        assertFalse(Ineligible.TooYoung.isOverridable)
        assertFalse(Ineligible.NoDateOfBirth.isOverridable)
        assertEquals(Ineligible.TooYoung, Ineligible.of("too_young"))
        assertNull(Ineligible.of("a_rule_from_the_future"))
    }

    @Test fun `the extras button counts them`() {
        assertEquals("Nothing extra, continue", BookingWords.continueWith(0))
        assertEquals("Continue with 1 extra", BookingWords.continueWith(1))
        assertEquals("Continue with 3 extras", BookingWords.continueWith(3))
    }

    @Test fun `a booking goes out without what was not chosen`() {
        val body = ChairtimeJson.encodeToString(BookingRequest.serializer(), BookingRequest("s1", "p1", "2026-09-16T10:15:00.000Z"))
        assertEquals("""{"serviceId":"s1","staffId":"p1","startsAt":"2026-09-16T10:15:00.000Z","addonIds":[],"overridePrerequisite":false}""", body)
    }
}
