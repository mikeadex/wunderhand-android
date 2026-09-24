package com.wunderhand.core

import kotlinx.serialization.Serializable

// The Shop tab (chairtime `app/(pro)/shop`): the index, and the settings a
// shop changes often enough to want on a phone.

private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** `GET /api/v1/shop`: enough of each subject to know whether to open it. The row words are the web index's. */
@Serializable
data class ShopResponse(
    val isOwner: Boolean = false,
    val team: Team = Team(),
    val outlets: Outlets = Outlets(),
    val hours: Hours = Hours(),
    val rules: BookingRules = BookingRules(),
    val policy: PolicyFacts = PolicyFacts(),
    val reminders: Reminders = Reminders(),
    val payments: Payments = Payments(),
    val plan: Plan = Plan(),
    val site: Site = Site(),
) {
    @Serializable data class Team(val count: Int = 0, val withoutLogin: Int = 0)
    @Serializable data class Outlets(val count: Int = 0, val streetPrivate: Int = 0, /** Any outlet does home visits. */ val travels: Boolean? = null)
    @Serializable data class Hours(val days: Int = 0)
    @Serializable data class PolicyFacts(val standardDepositPence: Pence = Pence(0), val freeCancellationHours: Int = 0, val noShowPercent: Int = 0)
    @Serializable data class Reminders(val anyOn: Boolean = false, val on: List<Int> = emptyList())
    @Serializable data class Payments(val connected: Boolean = false)
    @Serializable data class Plan(val seats: Int = 0, val outlets: Int = 0)
    @Serializable data class Site(val domain: String? = null)

    val teamValue: String get() = count(team.count, "person", "people")
    val teamHint: String get() = if (team.withoutLogin > 0) "${team.withoutLogin} without a login · seats, and who works where" else "People, seats and who works where"

    val outletsValue: String get() = "${outlets.count}"
    val outletsHint: String
        get() = when {
            outlets.streetPrivate == 0 -> "Addresses, timezones and how far you travel"
            outlets.count > 1 -> "Addresses and how far you travel · street private at ${outlets.streetPrivate}"
            else -> "Addresses and how far you travel · street private"
        }

    val hoursValue: String get() = if (hours.days == 0) "Not set" else count(hours.days, "day")
    fun hoursHint(yourName: String): String = "Yours, and everybody else's · $yourName"

    fun policyValue(currency: String): String = policy.standardDepositPence.formatted(currency)
    val policyHint: String get() = "Free to cancel ${policy.freeCancellationHours}h before · ${policy.noShowPercent}% no-show fee"

    val remindersValue: String get() = if (reminders.anyOn) "${reminders.on.size} on" else "Off"
    val remindersHint: String get() = if (reminders.anyOn) reminders.on.joinToString(" · ", transform = ReminderWords::offsetLabel) else "Nothing is emailed before an appointment"

    val paymentsValue: String get() = if (payments.connected) "Connected" else "Set up"
    val paymentsHint: String get() = if (payments.connected) "Deposits go straight to your own Stripe account" else "Connect Stripe to take deposits online"

    /** Seats and outlets, and nothing else: the app never says what a plan costs. */
    val planValue: String get() = "${count(plan.seats, "seat")} · ${count(plan.outlets, "outlet")}"
    val siteValue: String get() = site.domain ?: "Set up"
    val siteHint: String get() = site.domain?.let { "Live on $it" } ?: "A site of your own, on your own address"
}

/** `GET` and `PUT /api/v1/shop/rules`: the six numbers that shape every time offered to a client. */
@Serializable
data class BookingRules(
    val slotIntervalMinutes: Int = 15,
    val bufferMinutes: Int = 0,
    val noticeHours: Int = 0,
    val horizonWeeks: Int = 12,
    val paymentHoldMinutes: Int = 10,
    val waitlistHoldMinutes: Int = 30,
) {
    /** "15 min slots" — the index row's value. */
    val summary: String get() = "$slotIntervalMinutes min slots"
    /** "2h notice · 10 min buffer · books 12 weeks ahead". */
    val detail: String get() = "${Durations.label(noticeHours * 60)} notice · $bufferMinutes min buffer · books $horizonWeeks weeks ahead"
}

/** `GET /api/v1/shop/policy`: deposits and cancellation, with the line a client is told when the wording is left blank. */
@Serializable
data class PolicyResponse(
    val standardDepositPence: Pence = Pence(0),
    val freeCancellationHours: Int = 0,
    val lateCancellationPercent: Int = 0,
    val noShowPercent: Int = 0,
    val payInFullAfterNoShows: Int = 0,
    val clientFacingWording: String? = null,
    /** Bumped whenever the wording changes, so old bookings keep their terms. */
    val wordingVersion: String = "",
    val defaultWording: String = "",
)

/** `PUT /api/v1/shop/policy`. */
@Serializable
data class PolicyWrite(
    val standardDepositPence: Int, val freeCancellationHours: Int, val lateCancellationPercent: Int,
    val noShowPercent: Int, val payInFullAfterNoShows: Int, val clientFacingWording: String? = null,
)

/**
 * `GET /api/v1/shop/hours`: one person's hours at one outlet. A weekday
 * (0 = Sunday) not listed is a day off. Times are local wall-clock "HH:MM".
 */
@Serializable
data class HoursResponse(
    val staff: List<Person> = emptyList(),
    val outlets: List<Outlet> = emptyList(),
    val staffId: String? = null,
    val outletId: String? = null,
    val days: List<Day> = emptyList(),
) {
    @Serializable data class Person(val id: String, val name: String)
    @Serializable data class Outlet(val id: String, val name: String)
    @Serializable data class Day(val weekday: Int, val opensAt: String, val closesAt: String)

    fun day(weekday: Int): Day? = days.firstOrNull { it.weekday == weekday }
    val person: Person? get() = staff.firstOrNull { it.id == staffId }
    val outlet: Outlet? get() = outlets.firstOrNull { it.id == outletId }

    companion object {
        /** Monday first, as the web's screen orders them; Postgres counts from Sunday. */
        val weekOrder = listOf(1, 2, 3, 4, 5, 6, 0)
        fun dayName(weekday: Int): String = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").getOrElse(weekday) { "Day $weekday" }
    }
}

/** `PUT /api/v1/shop/hours`. */
@Serializable data class HoursWrite(val staffId: String, val outletId: String, val days: List<HoursResponse.Day>)

/** `GET /api/v1/shop/reminders`: always three slots — the saved rules in send order, padded with presets the shop has not taken (position 0, off). */
@Serializable
data class RemindersResponse(val slots: List<Slot> = emptyList(), val anyOn: Boolean = false, val recent: Recent = Recent()) {
    @Serializable
    data class Slot(val position: Int = 0, val minutesBefore: Int, val enabled: Boolean = false) {
        val isSaved: Boolean get() = position > 0
    }

    @Serializable data class Recent(val sent: Int = 0, val failed: Int = 0)

    /** "Nothing has gone out yet." or "12 reminders sent". */
    val recentLabel: String get() = if (recent.sent == 0 && recent.failed == 0) "Nothing has gone out yet." else "${count(recent.sent, "reminder")} sent"
}

/** `PUT /api/v1/shop/reminders`. */
@Serializable
data class RemindersWrite(val slots: List<Slot>) {
    @Serializable data class Slot(val minutesBefore: Int, val enabled: Boolean)
}

/** When a reminder goes out, in the web's words (lib/reminders/schedule.ts). */
object ReminderWords {
    enum class Unit(val minutes: Int) {
        Minutes(1), Hours(60), Days(1440);
        val label: String get() = "${name.lowercase()} before"
    }

    /** "1 day before", "2 hours before", "15 minutes before" — the largest unit that divides exactly, so a shop sees the number it typed. */
    fun offsetLabel(minutes: Int): String {
        val n = maxOf(0, minutes)
        return when {
            n >= 10080 && n % 10080 == 0 -> "${count(n / 10080, "week")} before"
            n >= 1440 && n % 1440 == 0 -> "${count(n / 1440, "day")} before"
            n >= 60 && n % 60 == 0 -> "${count(n / 60, "hour")} before"
            else -> "${count(n, "minute")} before"
        }
    }

    /** Stored minutes back into a number and a unit for the form. */
    fun split(minutes: Int): Pair<Int, Unit> = when {
        minutes <= 0 -> 0 to Unit.Hours
        minutes % 1440 == 0 -> minutes / 1440 to Unit.Days
        minutes % 60 == 0 -> minutes / 60 to Unit.Hours
        else -> minutes to Unit.Minutes
    }

    fun minutes(value: Int, unit: Unit): Int = value * unit.minutes

    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 20160
}

// region The team

enum class Employment(val raw: String, val label: String) {
    Employed("employed", "Employed"), ChairRenter("chair_renter", "Rents a chair"), Apprentice("apprentice", "Apprentice"), AdminOnly("admin_only", "Office");
    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } }
}

/** The sign-in badge: "No login", "Invited", "Seat", "Free", "Suspended" — or the word itself, for one this build has not heard of. */
fun accountStatusLabel(status: String): String = when (status) {
    "none" -> "No login"
    "invited" -> "Invited"
    "active" -> "Seat"
    "comped" -> "Free"
    "suspended" -> "Suspended"
    else -> status
}

/** `GET /api/v1/shop/team`. */
@Serializable
data class TeamResponse(
    val people: List<Person> = emptyList(),
    /** Accounts charged for: invited or active. */
    val seats: Int = 0,
) {
    @Serializable
    data class Person(
        val id: String, val name: String, val roleTitle: String? = null,
        /** employed, chair_renter, apprentice or admin_only. */
        val employment: String = "employed",
        /** none, invited, active, comped or suspended. */
        val accountStatus: String = "none",
        val isBookable: Boolean = true, val hasLogin: Boolean = false, val isOwner: Boolean = false,
    ) {
        val statusLabel: String get() = accountStatusLabel(accountStatus)
        val isInvited: Boolean get() = accountStatus == "invited"

        /** "Barber · sees the shop’s money · chair renter · not in the diary". */
        val hint: String
            get() = listOfNotNull(roleTitle, "sees the shop’s money".takeIf { isOwner }, "chair renter".takeIf { employment == "chair_renter" }, "not in the diary".takeIf { !isBookable }).joinToString(" · ")
    }

    val summary: String get() = "${count(people.size, "person", "people")} · ${count(seats, "seat")}"
}

/** `GET /api/v1/shop/team/{id}`, and what every change to a person answers with. */
@Serializable
data class TeamPersonResponse(
    val person: Person,
    val outletIds: List<String> = emptyList(),
    val outlets: List<Outlet> = emptyList(),
    /** Whether whoever is looking may invite, remove and decide who sees the money. */
    val viewerIsOwner: Boolean = false,
    val isYou: Boolean = false,
) {
    @Serializable
    data class Person(
        val id: String, val name: String, val roleTitle: String? = null, val employment: String = "employed",
        val accountStatus: String = "none", val isBookable: Boolean = true, val hasLogin: Boolean = false, val isOwner: Boolean = false,
        /** Where the invitation went, while one is out. */
        val inviteEmail: String? = null,
    ) {
        val firstName: String get() = name.substringBefore(' ')
        val canSignIn: Boolean get() = accountStatus == "active" || accountStatus == "comped"
        val isSuspended: Boolean get() = accountStatus == "suspended"
        /** Taken off the team but their login still exists: inviting them could never work, so they are given it back instead. */
        val isComingBack: Boolean get() = hasLogin && isSuspended
        val statusLabel: String get() = accountStatusLabel(accountStatus)
        val employmentLabel: String get() = Employment.of(employment)?.label ?: employment
    }

    @Serializable
    data class Outlet(val id: String, val name: String, val city: String? = null, val postcode: String? = null) {
        /** "London, E2 7SJ" */
        val place: String get() = listOfNotNull(city, postcode).filter { it.isNotEmpty() }.joinToString(", ")
    }

    /** "Hackney Road, Dalston Lane", or that they work nowhere yet. */
    val worksAt: String get() = outlets.filter { it.id in outletIds }.joinToString(", ") { it.name }.ifEmpty { "Nowhere yet" }
}

/** `POST /api/v1/shop/team` and `PUT /api/v1/shop/team/{id}`. */
@Serializable
data class TeamPersonWrite(val name: String, val roleTitle: String? = null, val employment: String, val isBookable: Boolean, val outletIds: List<String>)

/** @param email null for somebody coming back: their login still exists, so none is needed. */
@Serializable data class InviteWrite(val email: String? = null)

@Serializable data class OwnerWrite(val owner: Boolean)

/** `POST /api/v1/shop/team/{id}/invite`: the person as they now are, and the sentence to show — chairtime's, so the app and the web say the same. */
@Serializable
data class InviteResponse(
    val person: TeamPersonResponse.Person, val outletIds: List<String> = emptyList(), val outlets: List<TeamPersonResponse.Outlet> = emptyList(),
    val viewerIsOwner: Boolean = false, val isYou: Boolean = false, val outcome: String = "", val message: String = "",
) {
    val asPerson: TeamPersonResponse get() = TeamPersonResponse(person, outletIds, outlets, viewerIsOwner, isYou)
}

/** The login and money panels, in the web's words. Nothing here names a price — the app never does. */
object TeamWords {
    fun loginTitle(p: TeamPersonResponse.Person): String = if (p.accountStatus == "active") "Has a login" else "Invite to log in"

    data class Login(val text: String, val canInvite: Boolean)

    /** What the login panel says, and whether it then offers the invitation. */
    fun login(r: TeamPersonResponse): Login {
        val p = r.person
        return when {
            p.canSignIn -> Login("${p.firstName} can sign in already. If they are locked out, they can reset their own password from the sign-in screen.", false)
            !r.viewerIsOwner -> Login("Only an owner can invite somebody to log in.", false)
            p.isComingBack -> Login("${p.firstName} still has their login from before. Giving it back puts them on the team again, and adds their seat to your plan from today.", true)
            else -> Login("Sends a link to set a password. The seat is charged from the moment you send it, not from when they accept.", true)
        }
    }

    fun inviteButton(p: TeamPersonResponse.Person): String =
        if (p.isComingBack) "Give them their login back" else if (p.accountStatus == "invited") "Send it again" else "Send an invitation"

    fun money(r: TeamPersonResponse): String {
        val who = if (r.isYou) "You" else r.person.firstName
        return if (r.person.isOwner) "$who can see everyone’s takings on the Money tab."
        else "$who ${if (r.isYou) "see" else "sees"} only ${if (r.isYou) "your" else "their"} own takings. Owners see the whole shop."
    }

    /** Null when they cannot be made an owner yet — nobody can log in as them. */
    fun moneyButton(r: TeamPersonResponse): String? {
        val p = r.person
        return when {
            p.isOwner -> if (r.isYou) "Stop seeing the shop’s money" else "Stop ${p.name} seeing the shop’s money"
            p.hasLogin && p.canSignIn -> "Let ${p.name} see the shop’s money"
            else -> null
        }
    }

    fun needsLoginFirst(p: TeamPersonResponse.Person): String = "Invite ${p.name} to log in first. Ownership on a record with no account is ownership nobody holds."

    const val STANDING_DOWN = "You would lose the Money tab for everyone but yourself, and would need another owner to give it back."
    const val REMOVING = "Suspends them and frees the seat. Their appointments and your takings keep their name, so last year still makes sense."
    const val SEATS = "Someone at more than one outlet is still one seat, split between them by the hours they work."
}

// endregion
// region Outlets

/** `GET /api/v1/shop/outlets`. */
@Serializable
data class OutletsResponse(val outlets: List<Outlet> = emptyList()) {
    @Serializable
    data class Outlet(
        val id: String, val name: String, val addressLine1: String? = null, val city: String? = null, val postcode: String? = null,
        val timezone: String = "Europe/London",
        /** Null means clients come here. */
        val servesRadiusMiles: Int? = null,
        val defaultTravelMinutes: Int = 30, val addressPrivate: Boolean = false,
    ) {
        val addressLine: String get() = listOfNotNull(addressLine1, city, postcode).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "No address" }
        val travelLabel: String get() = servesRadiusMiles?.let { "Travels $it miles" } ?: "Clients come here"
    }
}

/** What a visit costs by distance: "up to this many miles, this much". */
@Serializable data class TravelBand(val upToMiles: Int, val feePence: Pence)

/** `GET /api/v1/shop/outlets/{id}`, and what saving one answers with. */
@Serializable
data class OutletResponse(val outlet: OutletsResponse.Outlet, /** Ascending. None means travel is free. */ val travelBands: List<TravelBand> = emptyList()) {
    /** "Free up to 3 miles · £5 up to 8 miles · £10 up to 15 miles", or null with no ladder. */
    fun ladder(currency: String): String? = travelBands.takeIf { it.isNotEmpty() }?.joinToString(" · ") { band ->
        "${if (band.feePence.value == 0) "Free" else band.feePence.formatted(currency)} up to ${count(band.upToMiles, "mile")}"
    }
}

/** `POST /api/v1/shop/outlets` and `PUT /api/v1/shop/outlets/{id}`. */
@Serializable
data class OutletWrite(
    val name: String, val addressLine1: String? = null, val city: String? = null, val postcode: String? = null, val timezone: String,
    val servesRadiusMiles: Int? = null, val defaultTravelMinutes: Int, val addressPrivate: Boolean, val travelBands: List<Band> = emptyList(),
    /** keep, either or venue: the whole menu at once, as the web's outlet form offers. */
    val homeVisits: String = "keep",
) {
    @Serializable data class Band(val upToMiles: Int, val feePence: Int)
}

/** "I do home visits too", for the whole menu at once (chairtime `homeVisits`). */
enum class HomeVisits(val raw: String, val label: String) {
    Keep("keep", "Keep services as they are"),
    Either("either", "Offer every service here or at the client’s home"),
    Venue("venue", "Every service here only");

    companion object { fun of(raw: String?): HomeVisits = entries.firstOrNull { it.raw == raw } ?: Keep }
}

/** An outlet as somebody is typing it. */
data class OutletDraft(
    val name: String = "", val addressLine1: String = "", val city: String = "", val postcode: String = "", val timezone: String = "Europe/London",
    val servesRadiusMiles: String = "", val defaultTravelMinutes: String = "30", val addressPrivate: Boolean = false, val bands: List<BandDraft> = emptyList(),
    /** Applied on save, then left alone — the form always opens on "keep". */
    val homeVisits: HomeVisits = HomeVisits.Keep,
) {
    data class BandDraft(val miles: String = "", val fee: String = "")

    constructor(r: OutletResponse) : this(
        r.outlet.name, r.outlet.addressLine1.orEmpty(), r.outlet.city.orEmpty(), r.outlet.postcode.orEmpty(), r.outlet.timezone,
        r.outlet.servesRadiusMiles?.toString().orEmpty(), "${r.outlet.defaultTravelMinutes}", r.outlet.addressPrivate,
        r.travelBands.map { BandDraft("${it.upToMiles}", MoneyInput.pounds(it.feePence)) },
    )

    /** The zones the web offers, plus whatever this outlet already has. */
    val timezoneChoices: List<Pair<String, String>>
        get() = if (OutletWords.timezones.any { it.first == timezone }) OutletWords.timezones else OutletWords.timezones + (timezone to timezone.replace('_', ' '))

    @Throws(DraftProblem::class)
    fun write(): OutletWrite {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw DraftProblem("name", "A name is needed")
        val radius = ServiceDraft.whole(servesRadiusMiles, "servesRadiusMiles")
        val minutes = defaultTravelMinutes.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 } ?: throw DraftProblem("defaultTravelMinutes", "Travel time must be a whole number")

        // A row counts once it has a distance; its fee defaults to nothing, which is how a free zone close by is entered.
        val seen = mutableSetOf<Int>()
        val ladder = bands.mapIndexedNotNull { i, band ->
            val milesText = band.miles.trim()
            if (milesText.isEmpty()) return@mapIndexedNotNull null
            val miles = milesText.toIntOrNull()?.takeIf { it in 1..500 } ?: throw DraftProblem("band.$i.miles", "A travel band needs a whole number of miles, from 1 to 500")
            if (!seen.add(miles)) throw DraftProblem("band.$i.miles", "Two travel bands cannot end at the same distance")
            val fee = (if (band.fee.isBlank()) 0 else MoneyInput.pence(band.fee))?.takeIf { it in 0..100_000 } ?: throw DraftProblem("band.$i.fee", "A travel fee must be between £0 and £1000")
            OutletWrite.Band(miles, fee)
        }
        return OutletWrite(trimmed, addressLine1.trim().ifEmpty { null }, city.trim().ifEmpty { null }, postcode.trim().ifEmpty { null }, timezone, radius, minutes, addressPrivate, ladder.sortedBy { it.upToMiles }, homeVisits.raw)
    }

    companion object { const val MAX_BANDS = 5 }
}

object OutletWords {
    /** The four the web's form offers, by the places they cover. */
    val timezones = listOf("Europe/London" to "United Kingdom", "Europe/Dublin" to "Ireland", "Europe/Paris" to "France, Spain, Germany", "Europe/Lisbon" to "Portugal")

    const val TIMEZONE_HINT = "Opening hours are local to this outlet, so they stay right through a clock change."
    const val HOME_VISITS_HINT = "Changes every service on the menu when you save. A service can still be set on its own, under Where it happens."
    const val PRIVATE_LABEL = "Keep my street address private until someone books"
    const val PRIVATE_HINT = "Before booking, people see the area and the start of the postcode — Peckham, SE15 — and no map pin. The full address is in their confirmation."
    const val TRAVELLING = "Leave the distance blank if clients always come to you. Fill it in and clients booking a service marked “at theirs” give their address at checkout, and are told if it is further than you go."
    const val TRAVEL_TIME_HINT = "Kept free after an appointment at a client's address, so the next booking cannot start while you are still in the car."
    const val CHARGING = "Add a fee that grows with distance. Each row is “up to this many miles, this much”, and a client booking a visit is charged the first row their address fits under. For a free zone close to you, set its fee to £0 — for example free up to 3 miles, £5 up to 8, £10 up to 15. Leave every row blank to charge nothing for travel."
    /** For a new one only. It goes on the plan; what that costs is not the app's to say. */
    const val ON_THE_PLAN = "A new outlet is added to your shop’s plan."
}

// endregion
