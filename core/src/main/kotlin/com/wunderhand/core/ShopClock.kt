package com.wunderhand.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dates and times as the shop sees them.
 *
 * Every screen draws in the shop's timezone, never the phone's. A barber
 * checking tomorrow from a holiday in Lisbon wants London's 09:00, and a day
 * is the shop's midnight to midnight — 23 hours long in March and 25 in
 * October, which is why nothing here adds 86,400 seconds to anything.
 */
class ShopClock(val zone: ZoneId) {
    /** The shop's clock, or London's if the identifier is not one this device
     *  knows — the one market chairtime runs in today. */
    constructor(identifier: String) : this(zoneOrLondon(identifier))

    private val locale = Locale.UK
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", locale)

    private fun at(instant: Instant): ZonedDateTime = instant.atZone(zone)

    /** "Sept", as the web and the iPhone write it. Java's en-GB says "Sep". */
    private fun shortMonth(date: ZonedDateTime): String {
        val name = date.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
        return if (name == "Sep") "Sept" else name
    }

    private fun shortWeekday(date: ZonedDateTime) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    private fun wideWeekday(date: ZonedDateTime) = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    private fun wideMonth(date: ZonedDateTime) = date.month.getDisplayName(TextStyle.FULL, locale)

    /** "14:30". Always 24-hour, whatever the phone is set to. */
    fun time(instant: Instant): String = hourMinute.format(at(instant))

    /** "WEDNESDAY 16 SEPTEMBER", the eyebrow over a day. */
    fun dayEyebrow(instant: Instant): String = weekdayDayMonth(instant).uppercase(locale)

    /** "Wednesday 16 September", the day an appointment falls on. */
    fun weekdayDayMonth(instant: Instant): String =
        at(instant).let { "${wideWeekday(it)} ${it.dayOfMonth} ${wideMonth(it)}" }

    /** "Wed 16 Sept", a date in a list of them. */
    fun shortDay(instant: Instant): String =
        at(instant).let { "${shortWeekday(it)} ${it.dayOfMonth} ${shortMonth(it)}" }

    /** "3 Oct", when somebody is next booked in. */
    fun dayMonth(instant: Instant): String = at(instant).let { "${it.dayOfMonth} ${shortMonth(it)}" }

    /** "3 Oct 2025", a visit in someone's history. */
    fun dayMonthYear(instant: Instant): String = at(instant).let { "${it.dayOfMonth} ${shortMonth(it)} ${it.year}" }

    /** "16 September 2034", how long notes are kept. */
    fun longDate(instant: Instant): String = at(instant).let { "${it.dayOfMonth} ${wideMonth(it)} ${it.year}" }

    /** "Wed 11:15", on the button that books it. */
    fun weekdayTime(instant: Instant): String = "${shortWeekday(at(instant))} ${time(instant)}"

    /** "Wed 30 Sept, 09:00", when a booking is for. */
    fun shortDayTime(instant: Instant): String = "${shortDay(instant)}, ${time(instant)}"

    /** "16 Sept, 14:30", when a message arrived. */
    fun dayMonthTime(instant: Instant): String = "${dayMonth(instant)}, ${time(instant)}"

    /** The shop's calendar date for an instant, as the API writes it: "2026-09-16". */
    fun isoDate(instant: Instant): String = at(instant).toLocalDate().toString()

    /** The hour of the shop's day, 0–23. */
    fun hourOf(instant: Instant): Int = at(instant).hour

    /** The instant the shop's day begins, and the next one's — half-open. */
    fun dayBounds(containing: Instant): DayBounds {
        val day: LocalDate = at(containing).toLocalDate()
        return DayBounds(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
    }

    fun isSameDay(a: Instant, b: Instant): Boolean = at(a).toLocalDate() == at(b).toLocalDate()

    private companion object {
        fun zoneOrLondon(identifier: String): ZoneId =
            try { ZoneId.of(identifier) } catch (_: Exception) { ZoneId.of("Europe/London") }
    }
}

/** A shop's day: from its midnight up to, and not including, the next. */
data class DayBounds(val start: Instant, val end: Instant) {
    operator fun contains(instant: Instant): Boolean = instant >= start && instant < end
}
