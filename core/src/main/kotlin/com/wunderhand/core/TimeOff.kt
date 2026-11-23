@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Time off (chairtime `app/(pro)/shop/time-off`, `lib/diary/time-off.ts`):
// whole days nobody can book, for one person or the whole shop. Anybody's,
// for their own days; an owner's for somebody else's, or everyone's.

/** Somebody days off can be given to, or who has them. */
@Serializable
data class TimeOffPerson(val id: String, val name: String) {
    val firstName: String get() = name.substringBefore(' ')
}

/**
 * One line of the list: a run of whole days for one person or several, or an
 * hour blocked from the diary. chairtime keeps a row a person a day and groups
 * them into runs, so taking one day back out of a fortnight leaves two lines.
 */
@Serializable
data class TimeOffEntry(
    /** Stable for a list; not an id of anything. */
    val key: String,
    val kind: String = "holiday",
    val note: String? = null,
    val wholeDay: Boolean = true,
    /** The first and last day, as the shop's calendar dates. The same day twice for hours. */
    val from: String,
    val to: String,
    /** Wall-clock `HH:MM` at the person's outlet, for hours; null for whole days. */
    val fromTime: String? = null,
    val toTime: String? = null,
    val people: List<TimeOffPerson> = emptyList(),
    /** It is everybody the diary shows, at a shop of more than one. */
    val everyone: Boolean = false,
    /** Appointments still to come inside it. They are not moved or cancelled. */
    val inside: Int = 0,
    /** How to take it back — exactly one is set: a request and its dates, or one block. */
    val seriesId: String? = null,
    val blockId: String? = null,
) {
    /** How many calendar days, both ends counted. */
    val days: Int get() = TimeOffWords.days(from, to)

    /**
     * Whose days to take back. One person's line names them, so a colleague
     * who shares the request keeps theirs; a line for several is the request's
     * days for all of them.
     */
    val staffIdToRemove: String? get() = people.singleOrNull()?.id?.takeIf { !everyone }
}

/** `GET /api/v1/time-off`, and the answer to taking days off or back. */
@Serializable
data class TimeOffResponse(
    /** Whether the viewer may give days off to somebody else, or everybody. */
    val mayChooseWho: Boolean = false,
    /** Who days off can be given to: the diary's people for an owner, the viewer alone otherwise. */
    val people: List<TimeOffPerson> = emptyList(),
    /** Longest stretch in one request, in days. */
    val maxDays: Int = 366,
    val entries: List<TimeOffEntry> = emptyList(),
) {
    /** An owner at a shop of more than one: there is somebody to choose, and a name worth saying on each line. */
    val choosesWho: Boolean get() = mayChooseWho && people.size > 1
    /** Whole days: a holiday, the shop shut. */
    val comingUp: List<TimeOffEntry> get() = entries.filter { it.wholeDay }
    /** An hour or an afternoon, blocked from the diary. */
    val blockedHours: List<TimeOffEntry> get() = entries.filter { !it.wholeDay }
}

/** `POST /api/v1/time-off`, and `/check` without the note. `who` is a person's id or `everyone`. */
@Serializable
data class TimeOffWrite(val who: String, val from: String, val to: String? = null, val note: String? = null)

/** `POST /api/v1/time-off/check`: what is already booked in those days. Nothing is changed by asking. */
@Serializable
data class TimeOffCheck(val days: Int = 0, val inside: List<Inside> = emptyList()) {
    @Serializable
    data class Inside(
        val id: String,
        val startsAt: Instant,
        val staffId: String,
        val staffName: String,
        val clientName: String? = null,
        val serviceName: String,
    )

    /** More than one person's bookings are in the list, so each says whose it is. */
    val several: Boolean get() = inside.map { it.staffId }.distinct().size > 1
}

/** The form: who, the first and last day, and what for. Dates are the shop's calendar dates. */
data class TimeOffDraft(val who: String, val from: String, val to: String, val note: String = "") {
    /** The last day is never before the first: moving the first past it takes it along. */
    fun withFrom(day: String): TimeOffDraft = copy(from = day, to = if (to < day) day else to)
    fun withTo(day: String): TimeOffDraft = copy(to = if (day < from) from else day)

    fun write(): TimeOffWrite = TimeOffWrite(who, from, to, note.trim().ifEmpty { null })

    companion object { const val EVERYONE = "everyone" }
}

object TimeOffWords {
    const val TITLE = "Time off"
    const val INTRO_SHOP = "Days nobody can book: a holiday, Christmas, a course. For one person, or the whole shop at once."
    const val INTRO_YOU = "Days nobody can book you: a holiday, Christmas, a course."
    const val COMING_UP = "Coming up"
    const val NONE = "No days off coming up."
    const val BLOCKED_HOURS = "Blocked hours"
    const val FOOTNOTE = "For an hour or an afternoon — lunch, the dentist — use Block off time in the diary. A day you never work is a working hours change."
    const val ADD = "Add time off"
    const val WHO = "Who"
    const val EVERYONE = "Everyone"
    const val FIRST_DAY = "First day off"
    const val LAST_DAY = "Last day off"
    const val WHAT_FOR = "What for"
    const val WHAT_FOR_EXAMPLE = "Christmas, Lisbon, a course"
    const val WHAT_FOR_HINT = "Optional. Only the team sees it."
    const val SAVE = "Save time off"
    const val ANYWAY = "Take the time off anyway"
    const val SAVED = "Saved."
    const val REMOVED = "Removed."
    const val REMOVE = "Remove"
    /** On the Shop index. */
    const val ROW_HINT_SHOP = "Holidays and days the shop is shut, for one person or everyone"
    const val ROW_HINT_YOU = "A holiday, or any days you cannot be booked"

    private val locale = Locale.UK
    private fun parse(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

    /** "Wed 3 Feb", and the year only when it is not this one: "Fri 2 Jan 2027". */
    fun day(iso: String, thisYear: Int = LocalDate.now().year): String {
        val d = parse(iso) ?: return iso
        // "Sept", as the rest of the app and the web write it. Java's en-GB says "Sep".
        val month = d.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').let { if (it == "Sep") "Sept" else it }
        val weekday = d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        return "$weekday ${d.dayOfMonth} $month" + if (d.year == thisYear) "" else " ${d.year}"
    }

    /** A run of calendar dates: one day, or "Mon 22 Dec – Fri 2 Jan 2027". */
    fun range(from: String, to: String, thisYear: Int = LocalDate.now().year): String =
        if (from == to) day(from, thisYear) else "${day(from, thisYear)} – ${day(to, thisYear)}"

    /** How many calendar days from one date to another, both counted. */
    fun days(from: String, to: String): Int {
        val a = parse(from) ?: return 1
        val b = parse(to) ?: return 1
        return ChronoUnit.DAYS.between(a, b).toInt() + 1
    }

    /** A card's first line: the days, or the day and its hours. */
    fun whenLine(entry: TimeOffEntry, thisYear: Int = LocalDate.now().year): String =
        if (entry.wholeDay) range(entry.from, entry.to, thisYear)
        else "${day(entry.from, thisYear)}, ${entry.fromTime.orEmpty()}–${entry.toTime.orEmpty()}"

    fun kind(kind: String): String = when (kind) {
        "admin" -> "Admin"
        "holiday" -> "Holiday"
        else -> "Break"
    }

    /** "Everyone", or first names: who a line is for. */
    fun who(entry: TimeOffEntry): String = if (entry.everyone) EVERYONE else entry.people.joinToString(", ") { it.firstName }

    /**
     * A card's second line. The note is what the shop calls it; the kind
     * stands in when there is none. Who, only where there is somebody else it
     * could have been.
     */
    fun whatLine(entry: TimeOffEntry, showWho: Boolean): String = listOfNotNull(
        entry.note?.takeIf { it.isNotBlank() } ?: kind(entry.kind),
        who(entry).takeIf { showWho && it.isNotEmpty() },
        "${entry.days} days".takeIf { entry.wholeDay && entry.days > 1 },
    ).joinToString(" · ")

    /** Bookings still inside the days, which time off neither moves nor cancels. */
    fun insideNote(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 booking is still in these days. It stays until you move or cancel it."
        else -> "$count bookings are still in these days. They stay until you move or cancel them."
    }

    fun removeConfirm(entry: TimeOffEntry): String = when {
        !entry.wholeDay -> "Remove this block"
        entry.days > 1 -> "Remove these days"
        else -> "Remove this day"
    }

    fun removeMessage(entry: TimeOffEntry): String = when {
        !entry.wholeDay -> "Clients will be able to book this time again."
        entry.days > 1 -> "Clients will be able to book these days again, in the working hours as they are set."
        else -> "Clients will be able to book this day again, in the working hours as they are set."
    }

    // Before saving over bookings

    /** How many of the bookings in the way are listed; the rest are counted. */
    const val SHOWN = 8

    fun checkHeading(count: Int): String =
        if (count == 1) "There is a booking in these days already" else "There are $count bookings in these days already"

    fun checkBody(count: Int): String =
        "Nothing has been saved yet. Taking the time off does not move or cancel " +
            (if (count == 1) "it, and the client is not told" else "them, and the clients are not told") +
            " — that is yours to do, from the diary."

    /** "Wed 3 Feb, 09:00 · Mina Okafor · Root tint · with Sam" */
    fun insideLine(booking: TimeOffCheck.Inside, clock: ShopClock, several: Boolean): String = listOfNotNull(
        "${day(clock.isoDate(booking.startsAt))}, ${clock.time(booking.startsAt)}",
        booking.clientName ?: "Walk-in",
        booking.serviceName,
        "with ${booking.staffName.substringBefore(' ')}".takeIf { several },
    ).joinToString(" · ")

    fun moreLine(count: Int, firstDay: String): String? =
        if (count > SHOWN) "And ${count - SHOWN} more, in the diary from ${day(firstDay)}." else null
}
