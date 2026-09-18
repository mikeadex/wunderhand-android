@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

// The waitlist, and filling a gap from it (chairtime `app/(pro)/waitlist`, `app/(pro)/gaps`).

/** Somebody waiting. */
@Serializable
data class WaitingRow(
    val id: String,
    val clientId: String,
    val clientName: String,
    val clientPhone: String? = null,
    val serviceId: String,
    val serviceName: String,
    val staffId: String? = null,
    val staffName: String? = null,
    val earliest: Instant? = null,
    /** The end of the last day that is any use to them. */
    val latest: Instant? = null,
    /** "Weekdays, evenings" — how a pro reads it back. */
    val flexibilityLabel: String = "",
    val status: String = "waiting",
    val waitingSince: Instant,
    /** Already looking at an offer; left out of new broadcasts. */
    val hasLiveOffer: Boolean = false,
) {
    /** "Skin fade · Kit Alvarez · thu, fri, evenings" */
    val detail: String get() = "$serviceName · ${staffName ?: "anyone"} · ${flexibilityLabel.lowercase()}"
}

/** `GET /api/v1/waitlist` */
@Serializable
data class WaitlistResponse(val waiting: List<WaitingRow> = emptyList()) {
    val offeredCount: Int get() = waiting.count { it.hasLiveOffer }
    val longestWaitingSince: Instant? get() = waiting.minOfOrNull { it.waitingSince }
}

/** `GET /api/v1/waitlist/options`: what the "Who is waiting?" form offers. */
@Serializable
data class WaitlistOptions(val services: List<Option> = emptyList(), val staff: List<Option> = emptyList()) {
    @Serializable data class Option(val id: String, val name: String)
}

/** `POST /api/v1/waitlist` */
@Serializable
data class WaitlistJoinRequest(
    val clientId: String,
    val serviceId: String,
    val staffId: String? = null,
    /** "2026-10-01", or null for as soon as possible. */
    val earliest: String? = null,
    /** "2026-10-14", or null. The day named is included. */
    val latest: String? = null,
    /** 0–6, Sunday first. Empty means any day. */
    val days: List<Int> = emptyList(),
    /** m, a, e. Empty means any time. */
    val parts: List<String> = emptyList(),
)

@Serializable data class WaitlistJoined(val id: String)

/** The days and times of day a client can come, as the form offers them (chairtime `app/(pro)/waitlist/new/page.tsx`). */
object Flexibility {
    /** Monday first on the form; the value is JavaScript's weekday, Sunday 0. */
    val days: List<Pair<Int, String>> = listOf(1 to "Monday", 2 to "Tuesday", 3 to "Wednesday", 4 to "Thursday", 5 to "Friday", 6 to "Saturday", 0 to "Sunday")

    data class Part(val value: String, val label: String, val sublabel: String)
    val parts: List<Part> = listOf(Part("m", "Mornings", "Before noon"), Part("a", "Afternoons", "Noon to five"), Part("e", "Evenings", "After five"))
}

/** `GET /api/v1/gaps`: who could take a window, ranked — whoever leaves the least of it unsold first, then whoever has waited longest. */
@Serializable
data class GapResponse(
    val staffId: String,
    val from: Instant,
    val to: Instant,
    val gapMinutes: Int,
    /** How many to offer it to at once. */
    val suggested: Int = 0,
    val waitingCount: Int = 0,
    val candidates: List<Candidate> = emptyList(),
) {
    @Serializable
    data class Candidate(
        val entryId: String, val clientId: String, val clientName: String, val serviceName: String,
        /** When they would be booked: the first time the engine found the service fits. */
        val startsAt: Instant,
        val waitingSince: Instant, val leftoverMinutes: Int = 0, val closesExactly: Boolean = false,
    ) {
        /** "closes it exactly", or "leaves 45m". */
        val fit: String get() = if (closesExactly) "closes it exactly" else "leaves ${Durations.short(leftoverMinutes)}"
    }

    /** The ones ticked to begin with: the best fits, as many as are suggested. */
    val preselected: Set<String> get() = candidates.take(suggested).map { it.entryId }.toSet()
}

/** `POST /api/v1/gaps/offer`. The instants are written by the client, to the millisecond, as chairtime reads them. */
@Serializable data class OfferRequest(val staffId: String, val from: String, val to: String, val entryIds: List<String>)

/** What went out, and what still has to go out by hand. */
@Serializable
data class OfferSent(
    val broadcastId: String,
    /** Whether the emails went anywhere real. */
    val emailWorking: Boolean = false,
    val sent: List<Link> = emptyList(),
) {
    @Serializable
    data class Link(
        val name: String, val phone: String? = null, val email: String? = null,
        /** Live for a day; opens exactly one offer. */
        val url: String,
    ) {
        val firstName: String get() = name.substringBefore(' ')
    }

    val emailed: List<Link> get() = sent.filter { it.email != null }
    val toText: List<Link> get() = sent.filter { it.email == null }
}

/** How long somebody has waited, in the web's words. */
object WaitWords {
    private fun days(since: Instant, now: Instant) = Math.floorDiv(Duration.between(since, now).seconds, 86_400L).toInt()
    private fun weeks(days: Int) = (days / 7.0).roundToInt()

    /** On the list: "today", "1 day", "9 days", "3 wks". */
    fun waited(since: Instant, now: Instant = Instant.now()): String = days(since, now).let { d ->
        when {
            d < 1 -> "today"
            d == 1 -> "1 day"
            d < 14 -> "$d days"
            else -> "${weeks(d)} wks"
        }
    }

    /** Beside a candidate for a gap: "joined today", "waiting 3 weeks". */
    fun joined(since: Instant, now: Instant = Instant.now()): String = days(since, now).let { d ->
        when {
            d < 1 -> "joined today"
            d == 1 -> "waiting 1 day"
            d < 14 -> "waiting $d days"
            else -> "waiting ${weeks(d)} weeks"
        }
    }

    /** "4 waiting", and how many of them are already looking at an offer. */
    fun summary(list: WaitlistResponse): String = listOfNotNull(
        "${list.waiting.size} waiting", "${list.offeredCount} offered".takeIf { list.offeredCount > 0 },
    ).joinToString(" · ")

    /**
     * The text message that carries an offer by hand, for somebody chairtime has
     * no email for. The link is theirs alone and live for a day, so it goes in
     * whole; the words are short enough to be one message.
     */
    fun text(link: OfferSent.Link, shopName: String): String = "Hi ${link.firstName}, a slot has come free at $shopName — take it here: ${link.url}"
}
