@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

// A new booking, a step at a time (chairtime `app/(pro)/booking/new`).

/** What a price and a length read as, wherever a service is listed. */
interface ServiceFacts {
    /** fixed, from, hourly or per_person. */
    val pricingMode: String
    val pricePence: Pence?
    val hourlyRatePence: Pence?
    val minutes: Int
    /** fixed or ranged. */
    val durationMode: String
    val minMinutes: Int?
    val maxMinutes: Int?

    /** "45m", or "3h–8h" for a ranged sitting (`describeDuration`). */
    val durationLabel: String
        get() {
            val min = minMinutes
            val max = maxMinutes
            return if (durationMode == "ranged" && min != null && max != null && min > 0 && max > 0) "${Durations.short(min)}–${Durations.short(max)}"
            else Durations.short(minutes)
        }

    /** "£28", "from £40", "£90/hr", "£15 each" or "Free" (`describePrice`). */
    fun priceLabel(currency: String): String {
        fun money(p: Pence?) = p?.formatted(currency) ?: "—"
        return when (pricingMode) {
            "hourly" -> "${money(hourlyRatePence)}/hr"
            "from" -> "from ${money(pricePence)}"
            "per_person" -> "${money(pricePence)} each"
            else -> if (pricePence?.value == 0) "Free" else money(pricePence)
        }
    }
}

/** A service on the menu, as the first step lists it. */
@Serializable
data class BookingService(
    val id: String,
    val name: String,
    val description: String? = null,
    val categoryName: String? = null,
    override val pricingMode: String = "fixed",
    override val pricePence: Pence? = null,
    override val hourlyRatePence: Pence? = null,
    override val minutes: Int,
    override val durationMode: String = "fixed",
    override val minMinutes: Int? = null,
    override val maxMinutes: Int? = null,
    /** at_venue, at_client or either. Missing from older chairtime responses. */
    val locationMode: String? = null,
) : ServiceFacts

/** `GET /api/v1/booking/services` */
@Serializable
data class BookingServicesResponse(val services: List<BookingService>)

/** `GET /api/v1/booking/services/{id}`: what the person and extras steps need. */
@Serializable
data class BookingServiceResponse(
    val service: BookingService,
    val staff: List<Performer>,
    val addons: List<Addon> = emptyList(),
    val requirements: Requirements = Requirements(),
    /** The outlets where somebody does it. One at most shops, when nothing is
     *  asked; the app asks which only when there is a choice. */
    val outlets: List<Outlet>? = null,
) {
    /** More than one outlet does this: ask which before asking who. */
    val needsOutlet: Boolean get() = (outlets?.size ?: 0) > 1

    /** An outlet a booking can be made at. */
    @Serializable
    data class Outlet(val id: String, val name: String, /** "Peckham, London" */ val area: String? = null, /** Does home visits from here. */ val travels: Boolean = false)

    @Serializable
    data class Performer(
        val id: String,
        val name: String,
        val roleTitle: String? = null,
        /** Their own price where they charge one, else the service's. */
        val pricePence: Pence? = null,
    ) {
        val initials: String get() = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
    }

    @Serializable
    data class Addon(val id: String, val name: String, val description: String? = null, val pricePence: Pence, val minutes: Int = 0)

    @Serializable
    data class Requirements(val minAgeYears: Int? = null, val prerequisiteName: String? = null, val prerequisiteLeadHours: Int? = null)
}

/** `GET /api/v1/booking/slots`: the times a whole booking fits, priced. */
@Serializable
data class BookingSlotsResponse(
    val days: List<Day>,
    /** The booking at the standard price, before a time is picked. */
    val standard: Standard,
) {
    @Serializable
    data class Day(val isoDate: String, val label: String, val slots: List<Slot>)

    @Serializable
    data class Slot(val start: Instant, val end: Instant, val closesGapExactly: Boolean = false, val gapMinutes: Int? = null, val price: Price)

    @Serializable
    data class Price(val pence: Pence, val listPence: Pence, val savingPence: Pence = Pence(0), val ruleName: String? = null) {
        val isDiscounted: Boolean get() = savingPence.value > 0
    }

    @Serializable
    data class Standard(val minutes: Int, val pence: Pence? = null)

    private val all: Sequence<Slot> get() = days.asSequence().flatMap { it.slots }

    /** The first time that closes a gap exactly — named above the days. */
    val firstExact: Slot? get() = all.firstOrNull { it.closesGapExactly }

    /** The first time a price rule brings down, when any does. Prices show on
     *  the times only then; otherwise every time costs the same. */
    val firstDiscounted: Slot? get() = all.firstOrNull { it.price.isDiscounted }

    fun slot(startingAt: Instant): Slot? = all.firstOrNull { it.start == startingAt }
}

/** `POST /api/v1/bookings` */
@Serializable
data class BookingRequest(
    val serviceId: String,
    val staffId: String,
    /** As chairtime reads an instant: UTC, to the millisecond. */
    val startsAt: String,
    val clientId: String? = null,
    val addonIds: List<String> = emptyList(),
    /** "The consultation was done, just not through here." Never gets past an age limit. */
    val overridePrerequisite: Boolean = false,
    /** Which outlet, at a shop with more than one. Null means the first. */
    val outletId: String? = null,
)

@Serializable
data class BookingCreated(
    val bookingId: String,
    val appointmentId: String,
    val startsAt: Instant,
    /** The shop's calendar date it is on, for the diary to open. */
    val date: String,
)

/** A rule a booking failed, by chairtime's code. */
enum class Ineligible(val code: String) {
    TooYoung("too_young"), NoDateOfBirth("no_date_of_birth"), MissingPrerequisite("missing_prerequisite");

    /** A pro may know a consultation or patch test happened elsewhere. An age
     *  limit is never theirs to click past. */
    val isOverridable: Boolean get() = this == MissingPrerequisite

    companion object {
        fun of(code: String): Ineligible? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Which step of a new booking is showing, from what has been chosen — the
 * web's `step` in `booking/new/page.tsx`. Extras are a step only for a service
 * that has some, and come before the time because they lengthen it.
 */
enum class BookingStep(val title: String) {
    Service("Pick a service"), Outlet("Which outlet?"), Person("Who is doing it?"), Extras("Anything else?"), Time("Pick a time");

    /** "Step 3 of 4": the number counts only the steps this booking has. */
    fun number(hasExtras: Boolean, hasOutlet: Boolean = false): Int = steps(hasOutlet, hasExtras).indexOf(this) + 1

    companion object {
        /** The outlet is a step only at a shop where more than one does the service. */
        fun current(serviceChosen: Boolean, staffChosen: Boolean, hasExtras: Boolean, extrasSeen: Boolean, outletNeeded: Boolean = false, outletChosen: Boolean = false): BookingStep = when {
            !serviceChosen -> Service
            outletNeeded && !outletChosen -> Outlet
            !staffChosen -> Person
            hasExtras && !extrasSeen -> Extras
            else -> Time
        }

        /** The steps this booking has, in order. */
        fun steps(hasOutlet: Boolean, hasExtras: Boolean): List<BookingStep> =
            entries.filter { (it != Outlet || hasOutlet) && (it != Extras || hasExtras) }

        fun total(hasExtras: Boolean, hasOutlet: Boolean = false): Int = steps(hasOutlet, hasExtras).size
    }
}

object BookingWords {
    /** Somebody took this time while it was being chosen. */
    fun taken(at: Instant, clock: ShopClock): String =
        "Someone got there first — ${clock.time(at)} was taken while you were choosing. Nothing has been booked. The times below are current."

    fun continueWith(extras: Int): String = when (extras) {
        0 -> "Nothing extra, continue"
        1 -> "Continue with 1 extra"
        else -> "Continue with $extras extras"
    }
}
