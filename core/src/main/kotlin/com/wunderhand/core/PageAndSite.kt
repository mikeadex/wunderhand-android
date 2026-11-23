package com.wunderhand.core

import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Your page and Your website (chairtime `app/(pro)/shop/profile` and
// `app/(pro)/shop/site`): the two Shop rows that opened the web until 1.3.
// Every write to either answers with the whole screen again.

// region Your page

/** `GET /api/v1/shop/page`, and the answer to every change made to it. */
@Serializable
data class PageResponse(
    val published: Boolean = false,
    /** The booking link: what goes in a bio. */
    val link: String = "",
    /** What a page is thinner without, in the server's words. */
    val missing: List<String> = emptyList(),
    /** False where photographs cannot be kept: uploads would not stick. */
    val storage: Boolean = true,
    val headline: String? = null,
    val about: String? = null,
    /** The handle, without its @. */
    val instagram: String? = null,
    val website: String? = null,
    val phone: String? = null,
    val trades: List<String> = emptyList(),
    val tradeChoices: List<String> = emptyList(),
    val coverUrl: String? = null,
    val logoUrl: String? = null,
    val photos: List<Photo> = emptyList(),
    val team: List<Person> = emptyList(),
    val outlets: List<Outlet> = emptyList(),
    /** What a stranger is shown in place of a private street. */
    val exampleArea: String = "",
    val limits: Limits = Limits(),
) {
    /** A photograph of the shop's work. [url] is null where the picture itself has gone; it is still there to remove. */
    @Serializable
    data class Photo(val id: String, val url: String? = null, val caption: String? = null, val staffName: String? = null)

    @Serializable
    data class Person(val id: String, val name: String = "")

    /** An outlet, and what a stranger sees of where it is as things stand. */
    @Serializable
    data class Outlet(val id: String, val name: String = "", val addressPrivate: Boolean = false, val shown: String = "")

    @Serializable
    data class Limits(val headline: Int = 120, val about: Int = 2000, val phone: Int = 30, val caption: Int = 140)
}

/** `PUT /api/v1/shop/page`: every line replaced. One left out is cleared. */
@Serializable
data class PageWrite(
    val headline: String? = null,
    val about: String? = null,
    val instagram: String? = null,
    val website: String? = null,
    val phone: String? = null,
    val trades: List<String> = emptyList(),
)

@Serializable
data class PublishedWrite(val published: Boolean)

@Serializable
data class AddressPrivateWrite(val addressPrivate: Boolean)

/** The page's words as they are being typed. */
data class PageDraft(
    val headline: String = "",
    val about: String = "",
    val instagram: String = "",
    val website: String = "",
    val phone: String = "",
    val trades: Set<String> = emptySet(),
) {
    constructor(page: PageResponse) : this(
        page.headline.orEmpty(), page.about.orEmpty(), page.instagram.orEmpty(), page.website.orEmpty(), page.phone.orEmpty(), page.trades.toSet(),
    )

    /** No line longer than the server takes: the field stops, and nothing is refused later for its length. */
    fun within(limits: PageResponse.Limits) = copy(
        headline = headline.take(limits.headline), about = about.take(limits.about), phone = phone.take(limits.phone),
        // Typed with or without its @; kept without, as the server keeps it.
        instagram = instagram.trimStart().removePrefix("@"),
    )

    /** In the order the page offers the trades, so the same choice is always the same request. */
    fun write(choices: List<String>): PageWrite {
        fun line(text: String) = text.trim().ifEmpty { null }
        return PageWrite(line(headline), line(about), line(instagram), line(website), line(phone), choices.filter { it in trades } + trades.filter { it !in choices }.sorted())
    }
}

object PageWords {
    const val TITLE = "Your page"
    const val LIVE = "Live"
    const val NOT_PUBLISHED = "Not published"
    const val INTRO = "What somebody sees who has not met you — the link for your Instagram bio, and what a client reads before deciding."
    const val NO_STORAGE = "Photograph storage is not switched on for this installation, so uploads will not stick. Everything else on this page works."
    const val HEADLINE = "What you do"
    const val HEADLINE_HINT = "One line. It is the first thing anybody reads, and the line that has to work in a search result."
    const val ABOUT = "About"
    const val ABOUT_HINT = "Who you are, how you work, what a first visit is like. A few sentences beats a paragraph."
    const val TRADES = "Trades"
    const val TRADES_HINT = "What you actually do. This is what a client filters on when they do not know you by name."
    const val COVER = "Cover photograph"
    const val COVER_HINT = "Wide, and the thing people see first. Your best work, not your shopfront."
    const val LOGO = "Logo"
    const val LOGO_HINT = "Optional. Shown at the top of your website instead of your name. A PNG with a transparent background looks best."
    const val ELSEWHERE = "Where else to find you"
    const val ELSEWHERE_HINT = "Optional, and worth filling in — most people check an Instagram before they book."
    const val SAVE = "Save"
    const val SAVED = "Saved."
    const val ADDRESS = "Your address on your page"
    const val LINK = "Your link"
    const val LINK_LIVE = "Put this in your bio. It shows your page, and books from the same place."
    const val LINK_NOT_LIVE = "This books already. Publishing adds your photos, your story and your team above it."
    const val PUBLISH = "Publish my page"
    const val TAKE_DOWN = "Take the page down"
    const val PUBLISHED = "Your page is live. Anyone with the link can see it."
    const val TAKEN_DOWN = "Taken down. The booking link still works — only the page is hidden."
    const val ADDRESS_SAVED = "Saved. Your page shows the change now."
    const val WORK_HINT = "This is what people actually decide on. Say who did it where more than one of you works — a client who liked a fade wants that barber, not the shop."
    const val CAPTION = "What is it? (optional)"
    const val WHO = "Who did it? (optional)"
    const val ADD_PHOTO = "Add photograph"
    const val PHOTO_ADDED = "Photograph added."
    const val PHOTO_REMOVED = "Photograph removed."
    const val REMOVE_PHOTO = "Remove this photograph"
    const val UNREADABLE = "That picture could not be read. Try another."
    const val TEAM_NOTE = "Photographs of your team only appear if that person has agreed — each of them has a switch on their own record. Somebody renting a chair has not consented to being on your page by virtue of being in the diary."

    /** "hair removal" as the web's chips show it: "Hair Removal". */
    fun trade(raw: String): String = raw.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.UK) } }

    fun addressSays(exampleArea: String) =
        "When this is on, your page and search show only the area — $exampleArea — and the full address goes in the confirmation once someone books."

    /** One outlet is "my street"; among several, each is named. */
    fun addressLabel(outlet: PageResponse.Outlet, count: Int) =
        if (count > 1) "Keep ${outlet.name}’s street private" else "Keep my street address private until someone books"

    fun addressHint(outlet: PageResponse.Outlet) = "${if (outlet.addressPrivate) "On" else "Off"} · strangers see ${outlet.shown}"

    fun linkSays(published: Boolean) = if (published) LINK_LIVE else LINK_NOT_LIVE

    /** What the page would be better for, or nothing when it has everything. */
    fun worthAdding(page: PageResponse): String? = page.missing.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let {
        if (page.published) "Still worth adding: $it." else "You can publish now — worth adding: $it."
    }

    fun workTitle(count: Int) = "Your work — $count photograph${if (count == 1) "" else "s"}"
}

// endregion
// region Your website

/** `GET /api/v1/shop/site`, and the answer to every change made to it. */
@Serializable
data class SiteResponse(
    /** False until the page is published: the site is the same information, and has nothing to show before. */
    val ready: Boolean = false,
    /** The design in use: one of [designs]' keys. */
    val template: String = "",
    val designs: List<Design> = emptyList(),
    val sides: Sides = Sides(),
    /**
     * Whether this shop's site answers on a domain of its own. False is the
     * careful reading of an answer that does not say: the screen then shows
     * nothing about domains at all.
     */
    val ownAddress: Boolean = false,
    val domains: List<Domain> = emptyList(),
) {
    @Serializable
    data class Design(
        val key: String, val name: String = "", val blurb: String = "", val group: String = "", val groupTitle: String = "",
        val imageUrl: String? = null,
        /** A page of the web showing this design with the shop in it: an owner's, so opened signed in. */
        val previewPath: String = "",
    )

    @Serializable
    data class Sides(
        /** Whether the design in use shows sides at all. */
        val designShows: Boolean = true,
        /** Whether there are enough of them, each with something to book, for the site to show them. */
        val showing: Boolean = true,
        val max: Int = 4, val nameMax: Int = 40, val lineMax: Int = 120,
        val sides: List<Side> = emptyList(),
        val categories: List<Category> = emptyList(),
        val photos: List<Photo> = emptyList(),
    ) {
        val isFull: Boolean get() = sides.size >= max
        fun side(id: String?): Side? = sides.firstOrNull { it.id == id }
    }

    @Serializable
    data class Side(val id: String, val name: String = "", val line: String? = null, val portfolioImageId: String? = null, val categoryIds: List<String> = emptyList())

    /** A category of the menu, and the side it is filed under, if any. */
    @Serializable
    data class Category(val id: String, val name: String = "", val sideId: String? = null)

    @Serializable
    data class Photo(val id: String, val url: String? = null, val caption: String? = null)

    /**
     * A domain being connected. [state] says what there is to do: `waiting`
     * for DNS, `finishing` the connection, `pointing` (ours, but its DNS is
     * elsewhere still) or `live`. One this build has not heard of shows its
     * [status] and [message] and offers nothing.
     */
    @Serializable
    data class Domain(
        val id: String, val host: String = "", val state: String = "", val status: String = "", val message: String? = null,
        val records: List<Record> = emptyList(), val checkedAt: String? = null, val lastSeen: String? = null,
    ) {
        val isWaiting: Boolean get() = state == "waiting"
    }

    /** A DNS record to add where the domain was bought. */
    @Serializable
    data class Record(val type: String = "", val name: String = "", val fqdn: String = "", val zone: String = "", val value: String = "", val why: String = "")

    /** The designs under their headings, in the order they came. */
    val designGroups: List<Pair<String, List<Design>>>
        get() = designs.map { it.groupTitle }.distinct().map { title -> title to designs.filter { it.groupTitle == title } }
}

@Serializable
data class DesignWrite(val template: String)

@Serializable
data class SideAdd(val name: String)

/** `PUT /api/v1/shop/site/sides/{id}`: the side as it should now be, its categories whole. */
@Serializable
data class SideWrite(val name: String, val line: String? = null, val portfolioImageId: String? = null, val categoryIds: List<String> = emptyList())

@Serializable
data class SideMove(val direction: String)

@Serializable
data class DomainAdd(val domain: String)

/** A side as it is being changed. */
data class SideDraft(val name: String = "", val line: String = "", val photoId: String? = null, val categoryIds: Set<String> = emptySet()) {
    constructor(side: SiteResponse.Side) : this(side.name, side.line.orEmpty(), side.portfolioImageId, side.categoryIds.toSet())

    fun within(sides: SiteResponse.Sides) = copy(name = name.take(sides.nameMax), line = line.take(sides.lineMax))

    @Throws(DraftProblem::class)
    fun write(sides: SiteResponse.Sides): SideWrite {
        val called = name.trim()
        if (called.isEmpty()) throw DraftProblem("name", SiteWords.NEEDS_NAME)
        // In the menu's order, and only what the menu still has.
        return SideWrite(called, line.trim().ifEmpty { null }, photoId, sides.categories.map { it.id }.filter { it in categoryIds })
    }
}

object SiteWords {
    const val TITLE = "Your website"
    const val INTRO = "A site of your own, on your own address, built from what is already in here — your services, your photographs, your hours."
    const val NOT_READY = "Publish your page first and this fills in. Your website is the same information, laid out for a visitor rather than a booking."
    const val DESIGN = "The design"
    const val PREVIEW = "See it with your shop in it"
    const val USE = "Use this design"
    const val IN_USE = "In use"
    const val DESIGN_SAVED = "Design saved."
    const val SIDES = "Sides of your business"
    const val SIDES_SAYS = "If you do more than one kind of work — hair and make-up, say — split your menu into sides, and your site gives each one a card of its own."
    const val DESIGN_HIDES = "Mila and Maison show these. Other designs will soon."
    const val NOT_SHOWING = "Your site shows sides once two of them each have a service clients can book online."
    const val NAME = "Name"
    const val LINE = "Line (optional)"
    const val CATEGORIES = "Categories"
    const val NO_CATEGORIES = "Your menu has no categories with services in them yet."
    const val PHOTO = "Photo"
    const val NO_PHOTO = "None"
    const val NO_PHOTOS = "Add photos to your portfolio and you can pick one here. Without one, the side uses a photo from its own services."
    const val SAVE_SIDE = "Save this side"
    const val REMOVE = "Remove"
    const val SIDE_SAVED = "Side saved."
    const val SIDE_ADDED = "Side added."
    const val SIDE_REMOVED = "Side removed."
    const val ADD_SIDE = "Add a side"
    const val ADD_SIDE_HINT = "Its categories and photo come next"
    const val ADD_IT = "Add it"
    const val NEEDS_NAME = "A side needs a name"
    const val ADDRESS = "Your address"
    const val NO_DOMAINS = "Already have a domain? Connect it and your site answers on it. You keep the address, and everything Google already knows about it stays where it is."
    const val ADD_DOMAIN = "Add a domain"
    const val DOMAIN_EXAMPLE = "olafbeauty.com"
    const val DOMAIN_HINT = "Without the https://"
    const val DOMAIN_ADDED = "Domain added."
    const val DOMAIN_REMOVED = "Domain removed."
    const val CHECK = "Check the DNS now"
    const val FINISH = "Finish the connection"
    const val WAIT = "DNS usually updates within a few minutes, occasionally an hour. Nothing breaks while you wait — your existing site keeps answering until the records change."
    const val LOOK_AGAIN = "Look again"
    const val COPY = "Copy"
    const val COPIED = "Copied"

    fun nameHint(sides: SiteResponse.Sides) = "Up to ${sides.nameMax} characters"
    fun lineHint(sides: SiteResponse.Sides) = "One sentence under the name, up to ${sides.lineMax} characters"
    fun full(sides: SiteResponse.Sides) = "${sides.max} sides is the most a site shows. Remove one to add another."
    /** What the first side is likely to be called, and the second. */
    fun sideExample(sides: SiteResponse.Sides) = if (sides.sides.isEmpty()) "Hair" else "Make-up"

    /** A category filed under another side says so: ticking it here takes it from there. */
    fun elsewhere(category: SiteResponse.Category, side: SiteResponse.Side, sides: SiteResponse.Sides): String? =
        sides.side(category.sideId)?.takeIf { it.id != side.id }?.let { "In ${it.name} — ticking it moves it here" }

    /** Under a record's name: where it really goes, said the way a DNS panel will ask for it. */
    fun nameNote(record: SiteResponse.Record) = if (record.name == "@") "(${record.zone} itself)" else "(in full: ${record.fqdn})"

    /** The one thing there is to press for a domain, or nothing. */
    fun button(domain: SiteResponse.Domain): String? = when (domain.state) { "waiting" -> CHECK; "finishing" -> FINISH; else -> null }

    private val CHECKED = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)

    /** "Last checked 14 Nov, 09:05. Nothing found at that name yet." — only once it has been checked, and only while waiting. */
    fun lastChecked(domain: SiteResponse.Domain, zone: ZoneId): String? {
        if (!domain.isWaiting) return null
        val at = domain.checkedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val found = domain.lastSeen?.takeIf { it.isNotBlank() }?.let { "What is there now: $it" } ?: "Nothing found at that name yet."
        return "Last checked ${CHECKED.format(at.atZone(zone))}. $found"
    }

    fun removeTitle(domain: SiteResponse.Domain) = "Remove ${domain.host}?"
    const val REMOVE_SAYS = "Your site stops answering on it. The domain stays yours, and its DNS records are yours to take out."
}

// endregion
// region Pictures the server names

/**
 * A picture's address as this device can reach it.
 *
 * chairtime builds some addresses from its own idea of where it is. On a
 * developer's machine that is `localhost`, which on a phone or an emulator is
 * the phone. Such an address is moved to wherever the app reaches the server.
 * Everywhere else — production, the image store — it comes back untouched.
 */
object ServerImage {
    private val LOCAL = setOf("localhost", "127.0.0.1")

    fun reachable(url: String?, server: String): String? {
        if (url == null) return null
        return runCatching {
            val picture = URI(url)
            val base = URI(server)
            if (picture.host !in LOCAL || base.host == null) url
            // Put together by hand: the path and the query go across exactly as they were written.
            else "${base.scheme}://${base.rawAuthority}${picture.rawPath.orEmpty()}${picture.rawQuery?.let { "?$it" }.orEmpty()}"
        }.getOrDefault(url)
    }
}

// endregion
