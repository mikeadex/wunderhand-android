@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/** `GET /api/v1/appointments/{id}`: one appointment as its sheet shows it. */
@Serializable
data class AppointmentResponse(
    val appointment: AppointmentDetail,
    /** Whether the viewer may see this appointment's money at all. */
    val seesMoney: Boolean,
    /** The bill's sums; null when the viewer may not see it. */
    val bill: Bill? = null,
    val replies: List<ClientReply> = emptyList(),
    val series: SeriesView? = null,
    /** The shop's current medical history wording, when consent is still needed. */
    val consentWording: ConsentWording? = null,
    /** What the "Book this again?" picker offers. */
    @SerialName("repeat") val repeatOptions: RepeatOptions? = null,
    /**
     * Whether the viewer may move, mark or edit this appointment, and whether
     * they may cancel it. Hints for what to draw: chairtime still refuses.
     * A response from before these existed reads as allowed.
     */
    val mayChange: Boolean = true,
    val mayCancel: Boolean = true,
) {
    /** "This is Ade Balogun’s appointment", where it is not the viewer's to change. */
    val notYoursTitle: String
        get() = "This is ${appointment.staffName?.takeIf { it.isNotBlank() } ?: "a colleague"}’s appointment"
}

object ChangeWords {
    /** What follows the name: whose it is to change. */
    const val NOT_YOURS = "Theirs or an owner’s to move, mark or cancel."
    const val ASK_OWNER = "To cancel this, ask an owner."
}

/** `GET /api/v1/appointments/{id}/history`: what has happened to the booking, oldest first as chairtime sends it. */
@Serializable
data class HistoryResponse(val lines: List<Line> = emptyList()) {
    @Serializable
    data class Line(
        val at: Instant,
        val text: String = "",
        val kind: String = "",
        /** How many of the same thing this line stands for. */
        val count: Int = 1,
    ) {
        /** The line that says where the record begins carries no time of its own. */
        val showsTime: Boolean get() = kind != "history_start"
    }
}

@Serializable
data class ConsentWording(val version: Int, val body: String)

@Serializable
data class RepeatOptions(
    /** Every how many weeks a series may repeat. */
    val intervals: List<Int>,
    /** The interval nearest how often this client actually comes. */
    val suggestedWeeks: Int,
    /** How many appointments a series keeps booked ahead. */
    val keepAhead: Int,
) {
    companion object {
        fun label(weeks: Int): String = if (weeks == 1) "Every week" else "Every $weeks weeks"
    }
}

@Serializable
data class AppointmentDetail(
    val id: String,
    val bookingId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val minutes: Int,
    val status: String,
    val clientId: String? = null,
    val clientName: String? = null,
    val clientVisitCount: Int? = null,
    val serviceId: String,
    val serviceName: String,
    val staffId: String,
    val staffName: String? = null,
    val completedAt: Instant? = null,
    val pricePence: Pence? = null,
    val depositMissing: Boolean = false,
    val depositPaidPence: Pence? = null,
    val atClient: Boolean? = null,
    val clientAddress: String? = null,
    val clientPostcode: String? = null,
    val travelMinutes: Int? = null,
    /** What the journey added to the bill; it is inside [totalPence]. Null where the viewer may not see the money. */
    val travelFeePence: Pence? = null,
    /** The outlet it is at, at a shop with more than one. */
    val outletId: String? = null,
    val outletName: String? = null,
    /** The service needs a signed consent form and none is on record. */
    val needsConsent: Boolean = false,
    val project: Project? = null,
    val standingFormula: String? = null,
    val clientNotes: String? = null,
    val averageIntervalDays: Double? = null,
    val depositState: String = "none",
    val depositPence: Pence? = null,
    val currency: String,
    val totalPence: Pence? = null,
    val listPricePence: Pence? = null,
    val promotionPence: Pence = Pence(0),
    val promotionName: String? = null,
    val lineItems: List<LineItem> = emptyList(),
) {
    @Serializable
    data class Project(val id: String, val name: String, val sittingNumber: Int, val isFinalBooked: Boolean, val depositPence: Pence)

    @Serializable
    data class LineItem(val name: String, val pricePence: Pence? = null)

    val displayName: String get() = clientName ?: "Walk-in"
    val isAtClient: Boolean get() = atClient ?: false

    /** Done, cancelled, a no-show or a hold that lapsed. */
    val isClosed: Boolean get() = status in setOf("completed", "cancelled", "no_show", "expired")

    fun isInTheChair(now: Instant): Boolean =
        startsAt <= now && now < endsAt && status !in setOf("completed", "cancelled", "no_show")
}

/** What the bill comes to (chairtime `lib/money/bill.ts`). */
@Serializable
data class Bill(
    val chargePence: Pence, val discountPence: Pence, val depositPaid: Boolean, val toTakePence: Pence,
    /** The travel fee inside [chargePence], for a line of its own. Nothing for a booking at the shop, or from a chairtime that does not send it. */
    val travelPence: Pence = Pence(0),
) {
    /** One row of the sheet's bill, above its total. */
    data class Line(val label: String, val value: String, val muted: Boolean)

    /**
     * The rows as the web sheet lists them (chairtime `components/diary/AppointmentDetail.tsx`): what was had, anything
     * taken off, the journey, then a deposit already paid. They add up to the total beneath them.
     */
    fun lines(appt: AppointmentDetail): List<Line> = buildList {
        val currency = appt.currency
        for (item in appt.lineItems) {
            // With a discount shown beneath it, the service is at its list price — otherwise the lines would not add up.
            val price = if (discountPence.value > 0 && appt.lineItems.size == 1) appt.listPricePence ?: item.pricePence else item.pricePence
            add(Line(item.name, price?.formatted(currency).orEmpty(), muted = false))
        }
        if (discountPence.value > 0) add(Line(appt.promotionName ?: "Off-peak price", "−${discountPence.formatted(currency)}", muted = true))
        // The journey is in the total, so it gets a line: without one a £40 service came to £47 and nothing said why.
        if (travelPence.value > 0) add(Line(TRAVEL, travelPence.formatted(currency), muted = false))
        val deposit = appt.depositPence
        if (depositPaid && deposit != null) add(Line("Deposit paid online", "−${deposit.formatted(currency)}", muted = true))
    }

    companion object { const val TRAVEL = "Travel" }
}

@Serializable
data class ClientReply(
    val id: String,
    val fromEmail: String,
    val subject: String? = null,
    val body: String? = null,
    val receivedAt: Instant,
)

/** The repeating booking an appointment belongs to. */
@Serializable
data class SeriesView(
    val id: String,
    val intervalWeeks: Int,
    val active: Boolean,
    val endedReason: String? = null,
    val localTime: String,
    val staffName: String,
    val upcoming: List<Occurrence> = emptyList(),
    val after: List<Occurrence> = emptyList(),
    val skipped: List<Skip> = emptyList(),
) {
    @Serializable
    data class Occurrence(val appointmentId: String, val startsAt: Instant)

    @Serializable
    data class Skip(val at: Instant, val reason: String)
}
