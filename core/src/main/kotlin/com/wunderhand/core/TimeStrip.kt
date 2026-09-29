package com.wunderhand.core

import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The day strip (chairtime `components/diary/ProTimePicker.tsx`): seven days
 * across, the chosen one's times under Morning, Afternoon and Evening. Both
 * time lists — a new booking's priced times and where an appointment could
 * move — are drawn by it, so the two share what they need to say.
 *
 * The labels are the shop's, sent by the server in its own time zone: the
 * phone may be in another, and "Tue 29" must be the shop's Tuesday. An older
 * server sends none, and the date itself stands in.
 */
interface StripSlot {
    val start: Instant
    val closesGapExactly: Boolean
    val period: String?
}

interface StripDay<S : StripSlot> {
    val isoDate: String
    val dow: String?
    val dom: String?
    val month: String?
    val slots: List<S>
}

object TimeStrip {
    /** In the order the day runs. */
    val periods = listOf("morning", "afternoon", "evening")

    fun title(period: String): String = when (period) { "morning" -> "Morning"; "afternoon" -> "Afternoon"; else -> "Evening" }

    private fun parse(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

    /** "Tue" */
    fun dow(day: StripDay<*>): String = day.dow ?: parse(day.isoDate)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.UK) ?: day.isoDate

    /** "29" */
    fun dom(day: StripDay<*>): String = day.dom ?: parse(day.isoDate)?.dayOfMonth?.toString() ?: day.isoDate

    fun month(day: StripDay<*>): String = day.month ?: parse(day.isoDate)?.month?.getDisplayName(TextStyle.FULL, Locale.UK) ?: ""

    /** "September", or "September – October" when the strip crosses a month end. */
    fun monthHeading(days: List<StripDay<*>>): String = days.map(::month).distinct().joinToString(" – ")

    /** `from` for "Previous week": seven days before the first day shown, never before
     *  today — and null when the first day already is today, so there is nothing earlier. */
    fun previousFrom(firstIso: String, today: String): String? =
        if (firstIso <= today) null else maxOf(IsoDay.shift(firstIso, -7), today)

    /** Which day the strip opens on: the one asked for when it is among these days —
     *  the chosen time's, or an appointment's own — else the first. */
    fun openingDay(days: List<StripDay<*>>, preferring: String?): String? =
        preferring?.takeIf { p -> days.any { it.isoDate == p } } ?: days.firstOrNull()?.isoDate

    /** Its part of the day, by the server's word — or by the shop's clock, from an older server. */
    fun period(slot: StripSlot, clock: ShopClock): String = slot.period ?: clock.hourOf(slot.start).let { h -> if (h < 12) "morning" else if (h < 17) "afternoon" else "evening" }

    /** The day's times under their headings, in the order the day runs; a part of the day with nothing in it is left out. */
    fun <S : StripSlot> grouped(slots: List<S>, clock: ShopClock): List<Pair<String, List<S>>> =
        periods.mapNotNull { p -> slots.filter { period(it, clock) == p }.takeIf { it.isNotEmpty() }?.let { p to it } }
}
