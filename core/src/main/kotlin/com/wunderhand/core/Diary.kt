@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant

/** A stretch of time, half-open: `start` is in it, `end` is not. */
@Serializable
data class Span(val start: Instant, val end: Instant) {
    val minutes: Double get() = Duration.between(start, end).toMillis() / 60_000.0
}

/** A line of somebody's day: an appointment, or unsold time long enough to sell. */
@Serializable(with = DiaryRowSerializer::class)
sealed interface DiaryRow {
    val startsAt: Instant
}

/** One appointment as the day shows it (chairtime `DiaryAppointment`). */
@Serializable
data class DiaryAppointment(
    val id: String,
    val bookingId: String,
    override val startsAt: Instant,
    val endsAt: Instant,
    val minutes: Int,
    val status: String,
    val clientName: String? = null,
    val clientVisitCount: Int? = null,
    val serviceName: String,
    /** Null for a colleague's appointment unless the viewer owns the shop. */
    val pricePence: Pence? = null,
    val replyCount: Int = 0,
    /** Part of a series that is still repeating. */
    val repeats: Boolean = false,
    /** Stretches inside the appointment when the pro is not busy — a tint
     *  developing. Drawn hatched, and sellable. */
    val freeInside: List<Span> = emptyList(),
    /** A deposit was asked for and Stripe could not take it. */
    val depositMissing: Boolean = false,
    val depositPaidPence: Pence? = null,
    val atClient: Boolean? = null,
    val clientPostcode: String? = null,
    /** The outlet it is at, at a shop with more than one. Null for a booking
     *  made before outlets were recorded. */
    val outletId: String? = null,
    val outletName: String? = null,
    /** What is left to take once any deposit is off. Worked out by chairtime
     *  (`lib/money/bill.ts`); null wherever the price is. */
    val toTakePence: Pence? = null,
    val kind: String = "appointment",
) : DiaryRow {
    val displayName: String get() = clientName ?: "Walk-in"
    val isAtClient: Boolean get() = atClient ?: false

    fun isInTheChair(now: Instant): Boolean = startsAt <= now && now < endsAt
    fun isPast(now: Instant): Boolean = endsAt <= now
}

/** Unsold time between appointments, long enough to sell. */
@Serializable
data class DiaryGap(
    override val startsAt: Instant,
    val endsAt: Instant,
    val minutes: Int,
    val kind: String = "gap",
) : DiaryRow

/** By its `kind`. Anything that is not a gap is read as an appointment, so a
 *  kind added later fails on its own fields rather than on its name. */
object DiaryRowSerializer : JsonContentPolymorphicSerializer<DiaryRow>(DiaryRow::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<DiaryRow> =
        if (element.jsonObject["kind"]?.jsonPrimitive?.content == "gap") DiaryGap.serializer() else DiaryAppointment.serializer()
}

/** Time blocked out: a break, admin, a holiday. */
@Serializable
data class DiaryBreak(
    val id: String,
    val kind: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val note: String? = null,
) {
    val label: String
        get() = note?.takeIf { it.isNotEmpty() } ?: when (kind) {
            "admin" -> "Admin"
            "holiday" -> "Holiday"
            else -> "Break"
        }
}

/** One person's day. */
@Serializable
data class DiaryDay(
    val date: Instant,
    /** When they are rostered, clipped to the day. */
    val open: List<Span>,
    /** Appointments and gaps, in time order. */
    val rows: List<DiaryRow>,
    val breaks: List<DiaryBreak> = emptyList(),
    val bookedCount: Int,
    val freeMinutes: Double,
    /** Null where the viewer may not see it. */
    val takingsPence: Pence? = null,
    val isClosed: Boolean,
    val slotIntervalMinutes: Int,
) {
    val appointments: List<DiaryAppointment> get() = rows.filterIsInstance<DiaryAppointment>()
    val gaps: List<DiaryGap> get() = rows.filterIsInstance<DiaryGap>()
}

@Serializable
data class DiaryMember(val id: String, val name: String, val initials: String, val day: DiaryDay) {
    val firstName: String get() = name.substringBefore(' ')
}

@Serializable
data class TeamDay(
    val date: Instant,
    val members: List<DiaryMember>,
    val bookedCount: Int,
    val takingsPence: Pence? = null,
    val freeMinutes: Double,
    val gapCount: Int,
    val isClosed: Boolean,
    val slotIntervalMinutes: Int,
)

/** A day of the week around the one shown, and how full it is. */
@Serializable
data class WeekDay(
    val isoDate: String,
    val date: Instant,
    val isOpen: Boolean,
    val booked: Int,
    val takingsPence: Pence? = null,
    val freeMinutes: Double,
    val openMinutes: Double,
    /** Share of rostered time that is sold, 0 to 1. */
    val utilisation: Double,
)

@Serializable
data class StatusNote(val headline: String, val body: String)

@Serializable
data class UncollectedDeposits(
    val bookings: Int,
    /** Null for somebody who may not see the shop's money. */
    val pence: Pence? = null,
)

@Serializable
data class DiaryAlerts(val status: StatusNote? = null, val uncollectedDeposits: UncollectedDeposits? = null)

/** `GET /api/v1/diary?date=`: everything the diary screen draws. */
@Serializable
data class DiaryResponse(
    /** The day shown, as the shop's calendar date. */
    val date: String,
    /** Today where the shop is. */
    val today: String,
    val dayStart: Instant,
    val dayEnd: Instant,
    val team: TeamDay,
    /** Monday to Sunday around `date`. */
    val week: List<WeekDay>,
    val waitingCount: Int = 0,
    val alerts: DiaryAlerts = DiaryAlerts(),
)
