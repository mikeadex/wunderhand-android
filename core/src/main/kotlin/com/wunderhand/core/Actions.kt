@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** How an appointment ends, from the shop's side. */
@Serializable
enum class CloseOutcome {
    @SerialName("completed") Completed,
    @SerialName("no_show") NoShow,
    @SerialName("cancelled") Cancelled;

    /** The word chairtime knows it by. */
    val raw: String
        get() = when (this) {
            Completed -> "completed"
            NoShow -> "no_show"
            Cancelled -> "cancelled"
        }
}

/** `POST /api/v1/appointments/{id}/close` */
@Serializable
data class CloseResponse(
    /** Left as the word it arrived as: the app knows what it asked for, and
     *  an outcome added later must not make a finished action look failed. */
    val outcome: String,
    /** What a cancellation returned and kept — null for done or a no-show,
     *  and for anyone who may not see this appointment's money. */
    val refund: Refund? = null,
) {
    @Serializable
    data class Refund(val refundPence: Pence, val keptPence: Pence)
}

/** `GET /api/v1/appointments/{id}/slots`: where an appointment could move to. */
@Serializable
data class SlotsResponse(val currentStartsAt: Instant, val days: List<SlotDay>) {
    @Serializable
    data class SlotDay(val isoDate: String, val label: String, val slots: List<Slot>)

    @Serializable
    data class Slot(val start: Instant, val end: Instant, val closesGapExactly: Boolean = false)
}

@Serializable
data class RepeatStarted(val booked: Int, val skipped: Int = 0)

@Serializable
data class RepeatStopped(val cancelled: Int = 0)

/** Time to block out, as a pro means it: wall-clock at their outlet. */
@Serializable
data class BlockRequest(
    val staffId: String,
    val kind: Kind,
    /** `YYYY-MM-DD` */
    val date: String,
    /** `HH:MM` */
    val from: String,
    val to: String,
    val note: String? = null,
) {
    @Serializable
    enum class Kind(val label: String) {
        @SerialName("break") Break("Break"),
        @SerialName("admin") Admin("Admin"),
        @SerialName("holiday") Holiday("Holiday"),
    }
}

@Serializable
data class BlockCreated(val id: String, val date: String)

/**
 * When an appointment's day-of actions open (chairtime `lib/diary/arrival.ts`).
 * The server enforces these; the app only uses them so it does not offer a
 * button that would then be refused.
 */
object Arrival {
    const val EARLY_MINUTES = 30L

    /** Mark done and check out open half an hour before the start. */
    fun hasArrived(startsAt: Instant, now: Instant): Boolean = Duration.between(now, startsAt) <= Duration.ofMinutes(EARLY_MINUTES)

    /** A no-show only once the start time has come. */
    fun hasStarted(startsAt: Instant, now: Instant): Boolean = startsAt <= now
}

/**
 * A drag on a diary grid, turned into minutes on the shop's booking grid
 * (chairtime's DayGrid: `Math.round(minutes / slot) * slot`), so a dragged
 * appointment lands where the booking engine would also have offered.
 */
object DragSnap {
    fun minutes(translation: Double, perMinute: Double, slotMinutes: Int): Int {
        if (perMinute <= 0 || slotMinutes <= 0) return 0
        return (translation / perMinute / slotMinutes).roundToInt() * slotMinutes
    }
}

/** The sentences the appointment's sheet says after something was done. */
object ActionWords {
    fun closed(outcome: CloseOutcome, refund: CloseResponse.Refund?, currency: String): String = when (outcome) {
        CloseOutcome.Completed -> "Marked done."
        CloseOutcome.NoShow -> "Recorded as a no-show."
        CloseOutcome.Cancelled ->
            if (refund != null && refund.refundPence.value > 0) "Cancelled. ${refund.refundPence.formatted(currency)} deposit refunded." else "Cancelled."
    }

    fun consentRecorded(version: Int?): String = version?.let { "Consent recorded against version $it of the form." } ?: "Consent recorded."

    fun repeatStarted(started: RepeatStarted): String {
        val booked = when (started.booked) {
            0 -> "Repeating, but nothing more could be booked yet."
            1 -> "Booked the next one."
            else -> "Booked the next ${started.booked}."
        }
        return if (started.skipped > 0) "$booked Some dates could not be — they are listed below." else booked
    }

    fun repeatStopped(stopped: RepeatStopped): String =
        if (stopped.cancelled > 0) "Stopped, and cancelled ${stopped.cancelled} booked after this." else "Stopped. Anything already booked is still booked."

    fun moved(to: Instant, clock: ShopClock): String = "Moved to ${clock.time(to)} on ${clock.weekdayDayMonth(to)}."

    /** Most likely somebody took it while the screen was open. */
    const val SLOT_TAKEN_WHILE_LOOKING = "That time was taken while you were looking. Nothing has moved — the times below are current."

    /**
     * Blocking time that overlaps something already there. chairtime refuses
     * it with the sentence it uses for a double booking — "Nothing has been
     * booked" — which is true and beside the point for somebody blocking a
     * lunch break.
     */
    const val BLOCK_OVERLAPS = "Something is already in the diary then. Pick another time, or move what is there first."

    /** Under a closed appointment, in place of its actions. */
    fun closedLine(status: String, completedAt: Instant?, startsAt: Instant, clock: ShopClock): String = when (status) {
        "completed" -> completedAt?.let { done ->
            "Done at ${clock.time(done)}" + (if (clock.isSameDay(done, startsAt)) "" else " on ${clock.shortDay(done)}") + "."
        } ?: "Done."
        "no_show" -> "Recorded as a no-show."
        "expired" -> "The hold lapsed before it was paid for."
        else -> "Cancelled."
    }
}
