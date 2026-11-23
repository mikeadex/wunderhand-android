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
    /** `from` for the week after these days; null when there were none, or from a server without it. */
    val nextFrom: String? = null,
) {
    @Serializable
    data class Day(
        override val isoDate: String,
        val label: String,
        /** The strip's labels, in the shop's time zone: "Tue", "29", "September". Absent from an older server. */
        override val dow: String? = null,
        override val dom: String? = null,
        override val month: String? = null,
        override val slots: List<Slot>,
    ) : StripDay<Slot> {
        /** A dot on the strip: some time this day is cheaper. */
        val hasCheaper: Boolean get() = slots.any { it.price.isDiscounted }
    }

    @Serializable
    data class Slot(
        override val start: Instant,
        val end: Instant,
        override val closesGapExactly: Boolean = false,
        val gapMinutes: Int? = null,
        /** "morning", "afternoon" or "evening"; absent from an older server. */
        override val period: String? = null,
        val price: Price,
    ) : StripSlot

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
    /** Where to go, when it is a home visit. Null books it at the shop. */
    val visit: Visit? = null,
) {
    /** The address, and whether the journey is charged for. chairtime prices it afresh as it books. */
    @Serializable
    data class Visit(
        val addressLine1: String,
        val addressLine2: String? = null,
        val town: String,
        val postcode: String,
        val chargeTravelFee: Boolean = true,
    ) {
        constructor(address: VisitAddress, chargeTravelFee: Boolean) :
            this(address.addressLine1, address.addressLine2, address.town, address.postcode, chargeTravelFee)

        /** An address is never printed, whatever ends up logging this. */
        override fun toString(): String = "Visit(chargeTravelFee=$chargeTravelFee)"
    }
}

/** Where a service is done, once the outlet is known. */
enum class VisitMode {
    /** At the shop: nothing to ask. */
    AtVenue,
    /** Always at the client's: the step goes straight to the address. */
    AtClient,
    /** Either way: ask which. */
    Either;

    val asksWhere: Boolean get() = this != AtVenue

    companion object {
        /**
         * chairtime's `effectiveMode`: the service's own mode, except that a
         * service done either way from an outlet that does not travel is done
         * at the shop. A service done only at the client's is always a visit.
         */
        fun of(serviceMode: String?, outletTravels: Boolean): VisitMode = when (serviceMode) {
            "at_client" -> AtClient
            "either" -> if (outletTravels) Either else AtVenue
            else -> AtVenue
        }
    }
}

/** An address a visit goes to, in the four fields the client's own checkout asks for. */
@Serializable
data class VisitAddress(
    val addressLine1: String = "",
    val addressLine2: String? = null,
    val town: String = "",
    val postcode: String = "",
) {
    /** An address is never printed, whatever ends up logging this. */
    override fun toString(): String = "VisitAddress(…)"
}

/** `GET /api/v1/clients/{id}/visit-address`: where they were last visited; null when they never have been. */
@Serializable
data class ClientVisitAddressResponse(val address: VisitAddress? = null)

/** `POST /api/v1/booking/visit`: a POST so the address stays out of the URL. Writes nothing. */
@Serializable
data class VisitQuoteRequest(
    val serviceId: String,
    val outletId: String? = null,
    val staffId: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val town: String,
    val postcode: String,
) {
    override fun toString(): String = "VisitQuoteRequest(serviceId=$serviceId)"
}

/** What travelling to an address would be, before anything is booked. */
@Serializable
data class VisitQuote(
    /** The address as it will be stored, tidied. */
    val address: VisitAddress = VisitAddress(),
    val feePence: Pence = Pence(0),
    /** To the nearest mile; null when it could not be measured. */
    val aboutMiles: Int? = null,
    val area: String? = null,
    val radiusMiles: Double? = null,
    val travelMinutes: Int = 30,
    /** Further than the outlet says it travels. A warning: the shop may still book it. */
    val outsideArea: Boolean = false,
    val warning: String? = null,
) {
    val hasFee: Boolean get() = feePence.value > 0

    /** "up to 5 miles from London", where the outlet has a travel area. */
    val reach: String?
        get() = radiusMiles?.let { r ->
            val miles = if (r == Math.floor(r)) "${r.toInt()}" else "$r"
            "up to $miles ${if (r == 1.0) "mile" else "miles"}${area?.let { " from $it" }.orEmpty()}"
        }

    /** "About 3 miles from London", or that it could not be checked. */
    val distance: String
        get() = aboutMiles?.let { "About $it ${if (it == 1) "mile" else "miles"}${area?.let { a -> " from $a" }.orEmpty()}" }
            ?: "The distance could not be checked"

    /** "£7", or "No travel fee". */
    fun fee(currency: String): String = if (hasFee) feePence.formatted(currency) else "No travel fee"

    /** The sentence to show when it is outside the area: chairtime's own, or the web's. */
    val outside: String?
        get() = if (!outsideArea) null
        else warning ?: "Outside your travel area${reach?.let { " ($it)" }.orEmpty()}. You can still book it — the area only limits what clients book online."

    /** What the fee line says under the figure. */
    fun explains(charged: Boolean): String = when {
        hasFee && charged -> "Added to the total, as it would be if they booked online."
        hasFee -> "Not charged. The booking says so, so the lower total is explained."
        aboutMiles == null -> "Nothing is added for travel on a journey that could not be measured."
        else -> "This journey is inside a band you charge nothing for."
    }

    /** The button that leaves the step, saying what it is agreeing to. */
    fun continueLabel(charged: Boolean, currency: String): String = when {
        !hasFee -> "Continue, no travel fee"
        charged -> "Continue with ${feePence.formatted(currency)} travel"
        else -> "Continue, travel not charged"
    }

    override fun toString(): String = "VisitQuote(feePence=${feePence.value}, aboutMiles=$aboutMiles, outsideArea=$outsideArea)"
}

/** The address as it is being typed. [address] is what is sent, or which field is missing. */
data class VisitDraft(
    val addressLine1: String = "",
    val addressLine2: String = "",
    val town: String = "",
    val postcode: String = "",
) {
    constructor(a: VisitAddress) : this(a.addressLine1, a.addressLine2.orEmpty(), a.town, a.postcode)

    val isEmpty: Boolean get() = addressLine1.isBlank() && addressLine2.isBlank() && town.isBlank() && postcode.isBlank()

    @Throws(DraftProblem::class)
    fun address(): VisitAddress {
        if (addressLine1.isBlank()) throw DraftProblem("addressLine1", "Add the address to go to")
        if (town.isBlank()) throw DraftProblem("town", "Add the town or city")
        if (postcode.isBlank()) throw DraftProblem("postcode", "Add the postcode")
        return VisitAddress(addressLine1.trim(), addressLine2.trim().ifEmpty { null }, town.trim(), postcode.trim())
    }

    /** An address is never printed, whatever ends up logging this. */
    override fun toString(): String = "VisitDraft(…)"
}

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
    Service("Pick a service"), Outlet("Which outlet?"), Person("Who is doing it?"), Extras("Anything else?"), Where("Where is it?"), Time("Pick a time");

    /** "Step 3 of 4": the number counts only the steps this booking has. */
    fun number(hasExtras: Boolean, hasOutlet: Boolean = false, hasWhere: Boolean = false): Int = steps(hasOutlet, hasExtras, hasWhere).indexOf(this) + 1

    companion object {
        /**
         * The outlet is a step only at a shop where more than one does the
         * service. Where it happens is a step only for a service that can be a
         * home visit, and comes after the extras as the web has it.
         */
        fun current(
            serviceChosen: Boolean, staffChosen: Boolean, hasExtras: Boolean, extrasSeen: Boolean,
            outletNeeded: Boolean = false, outletChosen: Boolean = false,
            whereNeeded: Boolean = false, whereDone: Boolean = false,
        ): BookingStep = when {
            !serviceChosen -> Service
            outletNeeded && !outletChosen -> Outlet
            !staffChosen -> Person
            hasExtras && !extrasSeen -> Extras
            whereNeeded && !whereDone -> Where
            else -> Time
        }

        /** The steps this booking has, in order. */
        fun steps(hasOutlet: Boolean, hasExtras: Boolean, hasWhere: Boolean = false): List<BookingStep> =
            entries.filter { (it != Outlet || hasOutlet) && (it != Extras || hasExtras) && (it != Where || hasWhere) }

        fun total(hasExtras: Boolean, hasOutlet: Boolean = false, hasWhere: Boolean = false): Int = steps(hasOutlet, hasExtras, hasWhere).size
    }
}

object BookingWords {
    /** Somebody took this time while it was being chosen. */
    fun taken(at: Instant, clock: ShopClock): String =
        "Someone got there first — ${clock.time(at)} was taken while you were choosing. Nothing has been booked. The times below are current."

    /** The where step's heading: a question only where there is a choice. */
    fun whereTitle(mode: VisitMode): String = if (mode == VisitMode.AtClient) "Where are they?" else BookingStep.Where.title

    const val AT_SHOP = "At the shop"
    const val AT_SHOP_SAYS = "They come to you."
    const val AT_CLIENT = "At the client’s"
    const val AT_CLIENT_SAYS = "You go to them."
    const val WHERE_TO_GO = "Where to go"
    const val WHERE_KEPT = "Kept on this booking, so whoever is going knows which door to knock on."
    const val LAST_VISIT = "Their last home visit was here. Change it if they have moved."
    const val ADDRESS = "Address"
    const val ADDRESS_2 = "Flat, floor, buzzer (optional)"
    const val TOWN = "Town or city"
    const val POSTCODE = "Postcode"
    const val CHECK = "Check the address"
    const val CHECK_AGAIN = "Check it again"
    const val CHARGE = "Charge for travel"

    /** "Kept on this booking… Checked against your travel area — up to 5 miles from London." */
    fun whereKept(quote: VisitQuote?): String =
        WHERE_KEPT + quote?.reach?.let { " Checked against your travel area — $it." }.orEmpty()

    fun continueWith(extras: Int): String = when (extras) {
        0 -> "Nothing extra, continue"
        1 -> "Continue with 1 extra"
        else -> "Continue with $extras extras"
    }
}
