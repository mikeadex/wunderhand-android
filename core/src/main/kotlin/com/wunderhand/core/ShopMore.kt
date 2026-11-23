@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate

// The owner's other Shop screens (chairtime `app/(pro)/shop/consent`,
// `/pricing`, `/promotions`) and a person's own calendar link (`/calendar`).

private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** "20% off" · "£5 off" · "£18 flat": what a price rule or an offer does to a price. */
private fun change(kind: String, value: Int, currency: String): String = when (kind) {
    "percent" -> "$value% off"
    "fixed" -> "${Pence(value).formatted(currency)} off"
    else -> "${Pence(value).formatted(currency)} flat"
}

// ── Consent forms ───────────────────────────────────────────────────────

/** `GET /api/v1/shop/consent`: every kind, written or not, in the order the web lists them. */
@Serializable
data class ConsentResponse(val documents: List<ConsentDocument> = emptyList()) {
    /** Kinds with no wording yet: consent cannot be recorded for those. */
    val missing: Int get() = documents.count { !it.isWritten }
}

/**
 * One kind's live wording. `version` 0 and an empty `body` mean nothing has
 * been written. `PUT /api/v1/shop/consent/{kind}` answers with the same, plus
 * `unchanged` when the words sent were the live ones.
 */
@Serializable
data class ConsentDocument(
    val kind: String = "",
    val version: Int = 0,
    val body: String = "",
    val updatedAt: Instant? = null,
    val signatures: Int = 0,
    val unchanged: Boolean = false,
) {
    val isWritten: Boolean get() = version > 0

    /** "Version 3 · signed 12 times", or "Nothing set". */
    val versionLine: String
        get() = if (!isWritten) "Nothing set" else "Version $version · signed ${count(signatures, "time")}"

    /** "Save as version 4": every save is a new version, the old one kept. */
    val saveLabel: String get() = "Save as version ${version + 1}"

    /** What the web says once it is saved. */
    val savedNote: String
        get() = if (unchanged) ConsentWords.SAME
        else "Saved as version $version. Anyone who signed the previous wording is still recorded against that text, not this one."
}

/** `PUT /api/v1/shop/consent/{kind}`. */
@Serializable
data class ConsentWrite(val body: String)

object ConsentWords {
    const val INTRO = "The words your clients agree to, kept exactly as they read on the day they signed. Paste in your own — the ones from your insurer, your trade body, or your licence. We do not write these, and you should not use wording that has not been checked by whoever covers you."
    const val SAME = "No change — those are the same words as the live version."
    const val MISSING = "Consent cannot be recorded for a kind with no wording. That is deliberate — a record that cannot produce what was agreed to is not evidence of anything, and a shop that believes otherwise finds out at the worst moment."
    const val PLACEHOLDER = "Paste the wording your clients will sign."
    const val KEEPS = "Saving keeps the old version. Everyone who has already signed stays recorded against the words they actually saw."
    const val FOOT = "Nothing here is ever deleted. Every version stays for as long as somebody’s signature points at it, which is what lets you produce, years later, the exact words a client agreed to."
    /** chairtime refuses anything shorter, so an emptied box cannot quietly switch consent off. */
    const val SHORTEST = 20
    const val TOO_SHORT = "Not saved. Consent wording has to say something — and clearing it would stop consent being recordable at all, quietly."

    fun missingTitle(missing: Int, of: Int): String =
        if (missing >= of) "No consent wording is set up"
        else if (missing == 1) "1 of these has no wording yet" else "$missing of these have no wording yet"

    fun title(kind: String): String = when (kind) {
        "medical_history" -> "Medical history"
        "patch_test" -> "Patch test"
        "aftercare" -> "Aftercare"
        "age_verification" -> "Age"
        "photo_release" -> "Photographs"
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun about(kind: String): String = when (kind) {
        "medical_history" -> "Allergies, medication, skin conditions, anything that changes what is safe. This is health data, so the answers are encrypted and the consent to hold them has to be explicit and separate."
        "patch_test" -> "Usually set by the colour manufacturer rather than by you, and usually the wording your insurer expects to see. Patch tests expire, so a consent from two years ago is not consent."
        "aftercare" -> "What the client agrees to do afterwards, and what happens if they do not. The document a dispute about healing turns on."
        "age_verification" -> "How you checked, and what they declared. For tattooing this is a legal requirement rather than a policy, whatever anybody says at the desk."
        "photo_release" -> "Whether their work can go on your feed, and where. Worth being precise about, because “for the portfolio” and “for an advert” are different permissions."
        else -> ""
    }

    /** "Change the wording", or "Add aftercare wording" where there is none. */
    fun open(document: ConsentDocument): String =
        if (document.isWritten) "Change the wording" else "Add ${title(document.kind).lowercase()} wording"
}

// ── Quiet times ─────────────────────────────────────────────────────────

/**
 * `GET /api/v1/shop/quiet-times`, and what every change to it answers with:
 * how much of each part of the week sold, the parts worth pricing lower, and
 * the price rules that are live.
 */
@Serializable
data class QuietTimesResponse(
    val demand: List<Demand> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val rules: List<PriceRule> = emptyList(),
) {
    /** One part of one weekday (0 = Sunday): `m`orning, `a`fternoon or `e`vening. */
    @Serializable data class Demand(val weekday: Int = 0, val part: String = "m", val soldPercent: Int = 0)

    /** A discount to try. `wouldEarnPence` is the unsold time at the shop's average take, not a promise. */
    @Serializable
    data class Suggestion(val weekday: Int = 0, val part: String = "m", val percent: Int = 0, val wouldEarnPence: Pence = Pence(0)) {
        val title: String get() = QuietWords.title(weekday, part)
        val button: String get() = "Try $percent% off then"
    }

    @Serializable
    data class PriceRule(
        val id: String = "",
        val name: String = "",
        /** Empty means every day. */
        val weekdays: List<Int> = emptyList(),
        val from: String = "",
        val to: String = "",
        val kind: String = "percent",
        val value: Int = 0,
        val showSaving: Boolean = true,
        val startsOn: String? = null,
        val endsOn: String? = null,
    ) {
        /** "20% off · Wed · 00:00–11:59". */
        fun line(currency: String): String = listOfNotNull(
            change(kind, value, currency),
            weekdays.takeIf { it.isNotEmpty() }?.joinToString(", ") { QuietWords.shortDay(it) },
            "$from–$to",
        ).joinToString(" · ")

        val savingLine: String get() = if (showSaving) "Shows as a saving" else "Shows as a plain price"
    }

    /** The weekdays there is anything to show for, Monday first, Sunday last. */
    val days: List<Int> get() = demand.map { it.weekday }.distinct().sortedBy { (it + 6) % 7 }

    fun sold(weekday: Int, part: String): Demand? = demand.firstOrNull { it.weekday == weekday && it.part == part }
    fun suggestion(weekday: Int, part: String): Suggestion? = suggestions.firstOrNull { it.weekday == weekday && it.part == part }

    /** The web's opening line, by what there is to say. */
    val intro: String
        get() = when {
            demand.isEmpty() -> QuietWords.NOTHING_YET
            suggestions.isEmpty() -> QuietWords.NOTHING_STANDS_OUT
            else -> QuietWords.EMPTIER
        }

    /** "About £260 of chair time a week goes unsold", or null when there is nothing to total. */
    fun unsold(currency: String): String? {
        val pence = suggestions.sumOf { it.wouldEarnPence.value }
        val pounds = Math.round(pence / 100.0).toInt()
        return if (pounds <= 0) null else "About ${Pence(pounds * 100).formatted(currency)} of chair time a week goes unsold"
    }

    val rulesTitle: String get() = if (rules.isEmpty()) "No price rules yet" else count(rules.size, "price rule")
}

/** `POST /api/v1/shop/quiet-times`: take a suggestion. */
@Serializable
data class QuietTimeAccept(val weekday: Int, val part: String, val percent: Int)

/** `PATCH /api/v1/shop/quiet-times/{id}`. */
@Serializable
data class QuietTimeUpdate(val showSaving: Boolean)

object QuietWords {
    const val NOTHING_YET = "Nothing to go on yet. Once there are a few weeks of bookings this will show which hours are not selling."
    const val NOTHING_STANDS_OUT = "Nothing stands out. Your quiet hours are close enough to the rest of the week that dropping prices would give away money you were going to earn anyway."
    const val EMPTIER = "These hours are emptier than the rest of your week. Pricing them lower fills chairs you are currently opening for nothing."
    const val ADDED = "Added. New bookings in that window get the lower price; anything already booked keeps the price it was made at."
    const val STOPPED = "Stopped. Bookings already taken at that price are untouched."
    const val ROUGH = "Rough, at your average take per hour. It is the size of the problem, not a promise about what you would earn."
    const val WORTH = "Worth a look, emptiest first"
    const val STARTING_POINT = "The percentage is a starting point, not a calculation — there is no way to know what a price change does until you try it. Come back in a month and this screen will tell you whether it worked."
    const val NO_RULES = "Nothing changes what your services cost. Take a suggestion above when one shows."
    const val WEEK = "Your week"
    const val WEEK_HINT = "How much of each part of the day has been selling. The darker, the fuller; an outline marks hours worth a look."
    const val SHOW_SAVING = "Show the saving"
    const val SHOW_SAVING_HINT = "Clients see the usual price crossed out beside the lower one."
    const val STOP = "Stop it"

    val parts = listOf("m" to "Morning", "a" to "Afternoon", "e" to "Evening")

    private val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    fun day(weekday: Int): String = days.getOrElse(weekday) { "" }
    fun shortDay(weekday: Int): String = day(weekday).take(3)

    /** "mornings" · "afternoons" · "evenings". */
    fun part(part: String): String = when (part) { "m" -> "mornings"; "a" -> "afternoons"; else -> "evenings" }

    /** "Tuesday mornings". */
    fun title(weekday: Int, part: String): String = "${day(weekday)} ${part(part)}"
}

// ── Offers ──────────────────────────────────────────────────────────────

/** `GET /api/v1/shop/offers`, and what starting or stopping one answers with. */
@Serializable
data class OffersResponse(val offers: List<Offer> = emptyList()) {
    val running: List<Offer> get() = offers.filter { !it.stopped }
    val stopped: List<Offer> get() = offers.filter { it.stopped }
}

/** A discount that brings somebody in or back, with what it has done so far. */
@Serializable
data class Offer(
    val id: String = "",
    val name: String = "",
    val code: String? = null,
    /** `anyone`, `first_visit`, `lapsed` or `returning`. */
    val audience: String = "anyone",
    val lapsedAfterDays: Int? = null,
    val kind: String = "percent",
    val value: Int = 0,
    val quietOnly: Boolean = false,
    /** The days it applies on: 0 is Sunday, 6 is Saturday. None means every day. */
    val weekdays: List<Int> = emptyList(),
    val startsOn: String? = null,
    val endsOn: String? = null,
    val redemptions: Int = 0,
    val maxRedemptions: Int? = null,
    val givenAwayPence: Pence = Pence(0),
    /** New clients it brought in, and how many of those have been back since. */
    val firstVisits: Int = 0,
    val cameBack: Int = 0,
    val stopped: Boolean = false,
) {
    /** "25% off · First visit · code NEWYEAR · Tue, Wed". */
    fun line(currency: String): String = listOfNotNull(
        change(kind, value, currency),
        OfferWords.audience(audience),
        code?.let { "code $it" },
        OfferWords.days(weekdays),
        "quiet mornings only".takeIf { quietOnly },
        endsOn?.let { "ends ${OfferWords.date(it)}" },
    ).joinToString(" · ")

    /** "3/19", or a dash where it has brought nobody new. */
    val cameBackValue: String get() = if (firstVisits > 0) "$cameBack/$firstVisits" else "—"

    /** What the numbers mean, where it has brought anybody in. */
    fun verdict(currency: String): String? = when {
        firstVisits <= 0 -> null
        cameBack <= 0 -> "${count(firstVisits, "new client")}, none back yet. Too early to say, unless it has been a while."
        else -> "$cameBack of $firstVisits new ${if (firstVisits == 1) "client has" else "clients have"} been back since — about ${Pence(givenAwayPence.value / cameBack).formatted(currency)} each to acquire."
    }

    /** "4 of 20 claimed", where there is a limit. */
    val claimed: String? get() = maxRedemptions?.let { "$redemptions of $it claimed" }
}

/** `POST /api/v1/shop/offers`. Amounts are pence for `fixed` and `absolute`, a number for `percent`. */
@Serializable
data class OfferWrite(
    val name: String,
    val code: String? = null,
    val audience: String,
    val lapsedAfterDays: Int? = null,
    val kind: String,
    val value: Int,
    val quietOnly: Boolean = false,
    val startsOn: String? = null,
    val endsOn: String? = null,
    /** 0 is Sunday, 6 is Saturday. None means every day. */
    val weekdays: List<Int> = emptyList(),
    /** Stop after this many uses; left out, there is no ceiling. */
    val maxRedemptions: Int? = null,
)

/** What somebody has typed into "Start one". [write] turns it into what chairtime wants, or says which field it could not read. */
data class OfferDraft(
    val name: String = "",
    val audience: String = "first_visit",
    val lapsedAfterDays: String = "",
    val kind: String = "percent",
    val amount: String = "",
    val code: String = "",
    val quietOnly: Boolean = false,
    val endsOn: String = "",
    /** The days ticked, 0 for Sunday to 6 for Saturday. None ticked is every day. */
    val weekdays: Set<Int> = emptySet(),
    val maxRedemptions: String = "",
) {
    fun toggled(weekday: Int): OfferDraft = copy(weekdays = if (weekday in weekdays) weekdays - weekday else weekdays + weekday)

    @Throws(DraftProblem::class)
    fun write(): OfferWrite {
        val called = name.trim()
        if (called.isEmpty()) throw DraftProblem("name", "Give it a name")
        if (called.length > 80) throw DraftProblem("name", "Keep the name to 80 characters")

        val days = if (audience != "lapsed") null else {
            val t = lapsedAfterDays.trim().ifEmpty { "${OfferWords.A_WHILE}" }
            t.toIntOrNull()?.takeIf { it in 1..3650 } ?: throw DraftProblem("lapsedAfterDays", "A whole number of days")
        }

        val value = if (kind == "percent") {
            amount.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 1..90 }
                ?: throw DraftProblem("value", "A percentage from 1 to 90")
        } else {
            MoneyInput.pence(amount)?.takeIf { it >= 1 } ?: throw DraftProblem("value", "An amount in pounds")
        }

        val word = code.trim().uppercase().ifEmpty { null }
        if (word != null && word.length > 40) throw DraftProblem("code", "Keep the code to 40 characters")

        val ends = endsOn.trim().ifEmpty { null }?.let {
            runCatching { LocalDate.parse(it).toString() }.getOrNull() ?: throw DraftProblem("endsOn", "A date like 2027-01-31")
        }
        val cap = maxRedemptions.trim().ifEmpty { null }?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..100_000 } ?: throw DraftProblem("maxRedemptions", "A whole number, or leave it empty")
        }
        return OfferWrite(called, word, audience, days, kind, value, quietOnly, null, ends, OfferWords.week.filter { it in weekdays }, cap)
    }
}

object OfferWords {
    const val EYEBROW = "Offers"
    const val TITLE = "Winning clients"
    const val INTRO = "Money spent to bring somebody in or bring them back. Quiet times are the other half — that fills the diary from what you already have, and costs nothing."
    const val ADDED = "Live now. It comes off the price after any quiet-time rate, and only one offer ever applies to a booking."
    const val STOPPED = "Stopped. Bookings already made keep the price they were made at."
    const val NONE = "Nothing running"
    const val NONE_SAYS = "Worth knowing before you start one: an offer valid all week gives money away on the hours that were going to sell anyway. Aiming it at your quiet mornings costs you nothing you were going to earn."
    const val START = "Start one"
    const val NAME = "What to call it"
    const val NAME_EXAMPLE = "First cut with us"
    const val WHO = "Who it is for"
    const val WHO_HINT = "First visit is an acquisition cost. Regulars is a thank you. They are different decisions."
    const val A_WHILE_LABEL = "How long counts as a while (days)"
    const val A_WHILE = 120
    const val HOW_MUCH = "How much"
    const val AMOUNT = "Amount"
    const val AMOUNT_HINT = "A number for a percentage, otherwise pounds."
    const val CODE = "Code (optional)"
    const val CODE_EXAMPLE = "NEWYEAR"
    const val CODE_HINT = "Leave blank and it applies on its own to anyone who qualifies."
    const val QUIET_ONLY = "Quiet mornings only"
    const val QUIET_ONLY_HINT = "An offer valid all week gives away money on the hours that were going to sell anyway."
    const val STOP_AFTER = "Stop after this many (optional)"
    const val STOP_AFTER_HINT = "An offer with no ceiling is one nobody has costed."
    const val DAYS = "Days it applies"
    const val DAYS_HINT = "None ticked means every day — which also means giving money away on your busiest ones."
    const val ENDS = "Ends on (optional)"
    const val ENDS_EXAMPLE = "2027-01-31"
    const val ONLY_ONE = "Only one offer applies to a booking, and it comes off after any quiet-time price. Check Quiet times first: filling an empty hour costs you nothing, and an offer aimed at those hours costs you far less than one that runs all week."
    const val START_IT = "Start it"
    const val STOP = "Stop it"

    /** The form's choices, first visit first as the web has them. */
    val audiences = listOf(
        "first_visit" to "First visit — people who have never been",
        "lapsed" to "Not been in a while",
        "returning" to "Regulars",
        "anyone" to "Anyone",
    )

    val kinds = listOf("percent" to "A percentage off", "fixed" to "An amount off", "absolute" to "A flat price")

    /** Monday first, as the form lists them; chairtime counts Sunday as 0. */
    val week = listOf(1, 2, 3, 4, 5, 6, 0)

    /** "Tue, Wed", Monday first; nothing where it runs every day. */
    fun days(weekdays: List<Int>): String? =
        week.filter { it in weekdays }.takeIf { it.isNotEmpty() }?.joinToString(", ") { QuietWords.shortDay(it) }

    fun audience(audience: String): String = when (audience) {
        "first_visit" -> "First visit"
        "lapsed" -> "Not been in a while"
        "returning" -> "Regulars"
        else -> "Anyone"
    }

    /** "31 Jan 2027" from "2027-01-31"; the text itself if it is not a date. */
    fun date(iso: String): String = runCatching {
        val d = LocalDate.parse(iso)
        "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)} ${d.year}"
    }.getOrDefault(iso)
}

// ── A person's calendar link ────────────────────────────────────────────

/**
 * `GET`, `POST` and `DELETE /api/v1/me/calendar`: a person's own diary as a
 * link a calendar app follows. `url` and `webcalUrl` come back once, when the
 * link is made; chairtime keeps no copy it could show again.
 */
@Serializable
data class CalendarLinkResponse(
    val feed: Feed? = null,
    val url: String? = null,
    val webcalUrl: String? = null,
    val refreshMinutes: Int = 30,
    val words: Words = Words(),
) {
    @Serializable data class Feed(val createdAt: Instant? = null, val lastFetchedAt: Instant? = null)
    @Serializable data class Words(val intro: String = "", val heading: String = "", val points: List<String> = emptyList(), val careful: String = "")

    val hasLink: Boolean get() = feed != null

    /** "Made 22 Nov, 10:25 · no calendar has fetched it yet". */
    fun status(clock: ShopClock): String? = feed?.let { f ->
        listOfNotNull(
            f.createdAt?.let { "Made ${clock.dayMonthTime(it)}" },
            f.lastFetchedAt?.let { "last fetched by a calendar ${clock.dayMonthTime(it)}" } ?: "no calendar has fetched it yet",
        ).joinToString(" · ")
    }
}

object CalendarWords {
    const val TITLE = "Your calendar"
    const val ROW_HINT = "Your appointments in your phone’s calendar, kept in step"
    const val HAS = "You have a calendar link"
    const val MAKE = "Make a calendar link"
    const val MAKE_NEW = "Make a new link"
    const val REPLACES = "The link you already have stops working the moment you make a new one."
    const val READY = "Your link is ready"
    const val ONCE = "Add it now. It is shown this once — if you lose it, make a new one, which also switches this one off."
    const val GOOGLE = "Add to Google Calendar"
    const val OUTLOOK = "Add to Outlook"
    const val COPY_HINT = "Or copy it into any calendar app:"
    const val COPY = "Copy"
    const val COPIED = "Copied"
    const val SHARE = "Share"
    const val STOP = "Stop sharing my calendar"
    const val STOP_TITLE = "Stop sharing your calendar?"
    const val STOP_SAYS = "The link stops working straight away. Any calendar that follows it stops updating, and you can make a new link whenever you like."
    const val NEW_TITLE = "Make a new link?"
    const val LINK_LABEL = "Calendar link"

    private fun encoded(text: String) = java.net.URLEncoder.encode(text, "UTF-8")

    /** Google's "add by URL" page, as the web links it. */
    fun google(webcalUrl: String): String = "https://calendar.google.com/calendar/render?cid=${encoded(webcalUrl)}"

    /** Outlook's "add from web" page, as the web links it. */
    fun outlook(url: String): String = "https://outlook.live.com/calendar/0/addfromweb?url=${encoded(url)}"
}
