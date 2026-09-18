package com.wunderhand.core

import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// region The agenda

/** One line of the phone's agenda: an appointment, or a gap shared by
 *  everyone free at exactly that time (chairtime `components/diary/Agenda.tsx`). */
sealed interface AgendaItem {
    val id: String
    val startsAt: Instant

    data class Booked(val appointment: DiaryAppointment, val who: String) : AgendaItem {
        override val id get() = appointment.id
        override val startsAt get() = appointment.startsAt
    }

    data class Free(val gap: DiaryGap, val who: List<String>, val staffIds: List<String>) : AgendaItem {
        override val id get() = "gap-${gap.startsAt.epochSecond}-${staffIds.joinToString(",")}"
        override val startsAt get() = gap.startsAt
    }
}

object Agenda {
    /** The day as one list for the people shown: every appointment, and the
     *  gaps folded so three people free from 15:00 to 16:00 is one line.
     *  A gap and a booking at the same minute: the booking first. */
    fun items(members: List<DiaryMember>): List<AgendaItem> {
        val appointments = members.flatMap { m -> m.day.appointments.map { AgendaItem.Booked(it, m.name) } }
        val gaps = foldGaps(members.flatMap { m -> m.day.gaps.map { Triple(it, m.name, m.id) } })
        // sortedWith is stable, so lines at the same minute keep the order the team is listed in.
        return (appointments + gaps).sortedWith(compareBy<AgendaItem> { it.startsAt }.thenBy { it is AgendaItem.Free })
    }

    /** Identical gaps across the team, as one naming everyone free
     *  (chairtime `foldGaps` in `lib/diary/geometry.ts`). */
    internal fun foldGaps(gaps: List<Triple<DiaryGap, String, String>>): List<AgendaItem.Free> =
        gaps.groupBy { (gap, _, _) -> gap.startsAt to gap.endsAt }
            .map { (_, same) -> AgendaItem.Free(same.first().first, same.map { it.second }, same.map { it.third }) }
            .sortedBy { it.startsAt }
}

// endregion
// region The summary line

/** "13 booked · £412 · 2 gaps left" for the people on screen. */
data class DaySummary(
    val booked: Int,
    /** Null unless every person shown carries their takings. */
    val takingsPence: Pence?,
    val gaps: Int,
    val isClosed: Boolean,
) {
    constructor(members: List<DiaryMember>) : this(
        booked = members.sumOf { it.day.bookedCount },
        takingsPence = members.map { it.day.takingsPence }.let { all -> if (all.any { it == null }) null else Pence(all.sumOf { it!!.value }) },
        gaps = members.sumOf { it.day.gaps.size },
        isClosed = members.all { it.day.isClosed },
    )

    val gapsLabel: String?
        get() = when (gaps) {
            0 -> null
            1 -> "1 gap left"
            else -> "$gaps gaps left"
        }
}

/** The week's totals over the list of days (chairtime `WeekView.tsx`). */
data class WeekSummary(
    val booked: Int,
    val takingsPence: Pence?,
    /** Average utilisation of the open days, 0 to 1. */
    val utilisation: Double,
) {
    constructor(days: List<WeekDay>) : this(
        booked = days.sumOf { it.booked },
        takingsPence = days.map { it.takingsPence }.let { all -> if (all.any { it == null }) null else Pence(all.sumOf { it!!.value }) },
        // Closed days do not drag the average down.
        utilisation = days.filter { it.isOpen }.let { open -> if (open.isEmpty()) 0.0 else open.sumOf { it.utilisation } / open.size },
    )
}

// endregion
// region Grid geometry

object GridGeometry {
    /** From the earliest anyone starts to the latest anyone finishes, widened
     *  to whole hours; `fallback` for a day nobody works (chairtime `gridRange`). */
    fun range(open: List<Span>, fallback: Span): Span {
        val first = open.minOfOrNull { it.start } ?: return fallback
        val last = open.maxOf { it.end }
        val hour = 3600.0
        return Span(
            Instant.ofEpochSecond((floor(first.epochSecond / hour) * hour).toLong()),
            Instant.ofEpochSecond((ceil(last.epochSecond / hour) * hour).toLong()),
        )
    }

    /** How much of a block's text fits: two lines, one, or only a mark. */
    enum class Density { Full, Single, Tick }

    fun density(minutes: Int): Density = when {
        minutes >= 28 -> Density.Full
        minutes >= 15 -> Density.Single
        else -> Density.Tick
    }

    enum class OffDutyPosition { Before, After, Between, AllDay }

    data class OffDuty(val span: Span, val position: OffDutyPosition)

    /** The parts of the range somebody is not rostered for, each labelled by
     *  where it falls (chairtime `offDuty`). */
    fun offDuty(open: List<Span>, range: Span): List<OffDuty> {
        val first = open.minOfOrNull { it.start } ?: return listOf(OffDuty(range, OffDutyPosition.AllDay))
        val last = open.maxOf { it.end }
        return Intervals.subtract(listOf(range), open).filter { it.end > it.start }.map { span ->
            OffDuty(span, when {
                span.end <= first -> OffDutyPosition.Before
                span.start >= last -> OffDutyPosition.After
                else -> OffDutyPosition.Between
            })
        }
    }
}

object Intervals {
    /** `from` with every stretch of `remove` cut out of it. */
    fun subtract(from: List<Span>, remove: List<Span>): List<Span> =
        remove.fold(from) { pieces, cut ->
            pieces.flatMap { piece ->
                if (cut.start >= piece.end || cut.end <= piece.start) listOf(piece)
                else buildList {
                    if (cut.start > piece.start) add(Span(piece.start, cut.start))
                    if (cut.end < piece.end) add(Span(cut.end, piece.end))
                }
            }
        }
}

// endregion
// region Calendar dates

/**
 * A shop's calendar date as the API writes it, `YYYY-MM-DD`, and the
 * arithmetic on it. A date, not an instant, so a clock change never moves it
 * by a day (chairtime's `shiftIsoDate` gets the same by anchoring at midday UTC).
 */
object IsoDay {
    private val locale = Locale.UK

    private fun parse(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

    fun shift(iso: String, days: Int): String = parse(iso)?.plusDays(days.toLong())?.toString() ?: iso

    /** "WED" */
    fun weekdayShort(iso: String): String = parse(iso)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, locale)?.uppercase(locale) ?: iso

    /** "Wednesday" */
    fun weekdayLong(iso: String): String = parse(iso)?.dayOfWeek?.getDisplayName(TextStyle.FULL, locale) ?: iso

    /** 16 */
    fun dayOfMonth(iso: String): Int = parse(iso)?.dayOfMonth ?: 0

    /** "Wednesday 16 Sept" */
    fun weekdayDayMonth(iso: String): String {
        val date = parse(iso) ?: return iso
        val month = date.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').let { if (it == "Sep") "Sept" else it }
        return "${weekdayLong(iso)} ${date.dayOfMonth} $month"
    }
}

// endregion
// region Words

object DiaryWords {
    /** "9th", "21st", "112th". */
    fun ordinal(n: Int): String {
        val v = n % 100
        val suffix = when {
            v in 11..13 -> "th"
            v % 10 == 1 -> "st"
            v % 10 == 2 -> "nd"
            v % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** "9th visit · usually every 6 weeks", as the appointment sheet says it. */
    fun about(visitCount: Int?, hasClient: Boolean, averageIntervalDays: Double?): String = buildList {
        if (visitCount != null && visitCount > 1) add("${ordinal(visitCount)} visit") else if (hasClient) add("First visit")
        if (averageIntervalDays != null && averageIntervalDays > 0) {
            add("usually every ${max(1, (averageIntervalDays / 7).roundToInt())} weeks")
        }
    }.joinToString(" · ")

    enum class StatusTone { Ink, Red, Grey, Plain }

    data class Status(val label: String, val tone: StatusTone)

    /** The chip on an appointment sheet. */
    fun status(status: String, inTheChair: Boolean): Status {
        if (inTheChair) return Status("In the chair", StatusTone.Ink)
        return when (status) {
            "confirmed" -> Status("Confirmed", StatusTone.Plain)
            "unconfirmed" -> Status("Unconfirmed", StatusTone.Red)
            "in_progress" -> Status("In the chair", StatusTone.Ink)
            "completed" -> Status("Done", StatusTone.Grey)
            "cancelled" -> Status("Cancelled", StatusTone.Grey)
            "no_show" -> Status("No-show", StatusTone.Red)
            "held" -> Status("Held", StatusTone.Grey)
            "expired" -> Status("Expired", StatusTone.Grey)
            // A status this build has not heard of is still said, in its own words.
            else -> Status(status.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, StatusTone.Grey)
        }
    }

    /** Why a repeat stopped, in the sheet's words. */
    fun seriesEnded(reason: String?, staffName: String): String = when (reason) {
        "unbookable" -> "Stopped repeating — the last 3 dates could not be booked"
        "staff_left" -> "Stopped repeating — $staffName is no longer taking bookings"
        "service_archived" -> "Stopped repeating — this service is no longer offered"
        "client_deleted" -> "Stopped repeating — the client was removed"
        else -> "Stopped repeating"
    }

    /** Why a date in a series could not be booked. */
    fun skipReason(reason: String, staffName: String): String = when (reason) {
        "taken" -> "$staffName already has somebody then"
        "time_off" -> "$staffName is off that day"
        "closed" -> "outside working hours"
        else -> "a rule on this service is not met, such as a patch test"
    }
}

// endregion
