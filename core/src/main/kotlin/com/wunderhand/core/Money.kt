@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

// The till and the money screen (chairtime `app/(pro)/checkout`, `app/(pro)/money`).

/** What is still owed at the end of an appointment (chairtime `lib/money/checkout.ts`). */
@Serializable
data class Owing(
    /** The booking's agreed total. */
    val subtotalPence: Pence = Pence(0),
    /** Deposits taken and not sent back. */
    val paidBeforePence: Pence = Pence(0),
    val extraPence: Pence = Pence(0),
    val tipPence: Pence = Pence(0),
    /** Owed for the work, never below zero. */
    val balancePence: Pence = Pence(0),
    /** What to ask for, tip included. */
    val duePence: Pence = Pence(0),
    /** Deposits covered more than the bill: the shop's to give back. */
    val overpaidPence: Pence = Pence(0),
) {
    /**
     * What to ask for once an extra and a tip are typed — chairtime's own sum
     * (`lib/money/checkout.ts`), done here only so the figure moves as it is
     * typed. What is recorded is what chairtime works out, never this.
     */
    fun with(extraPence: Int, tipPence: Int): Owing {
        val owed = subtotalPence.value + extraPence - paidBeforePence.value
        val balance = maxOf(0, owed)
        return copy(extraPence = Pence(extraPence), tipPence = Pence(tipPence), balancePence = Pence(balance), duePence = Pence(balance + tipPence), overpaidPence = Pence(maxOf(0, -owed)))
    }
}

/** What was recorded when it was rung through. */
@Serializable
data class Receipt(
    val subtotalPence: Pence = Pence(0), val paidBeforePence: Pence = Pence(0), val extraPence: Pence = Pence(0), val extraNote: String? = null,
    val tipPence: Pence = Pence(0),
    /** Handed over at the counter, tip included. */
    val takenPence: Pence = Pence(0),
    val method: String = "cash",
)

/** `GET /api/v1/checkout/{bookingId}` */
@Serializable
data class CheckoutResponse(
    val bookingId: String, val appointmentId: String, val clientName: String? = null, val staffName: String = "", val serviceName: String = "",
    val startsAt: Instant, val currency: String = "GBP", val owing: Owing = Owing(), val settledAt: Instant? = null, val receipt: Receipt? = null,
    /** From when it can be checked out: 30 minutes before the start. */
    val openFrom: Instant,
) {
    val displayName: String get() = clientName ?: "Walk-in"
    val isSettled: Boolean get() = settledAt != null
    fun hasArrived(now: Instant): Boolean = now >= openFrom
}

enum class TillMethod(val raw: String, val label: String) { Cash("cash", "Cash"), Card("card", "Card machine"), Other("other", "Something else") }

/** `POST /api/v1/checkout/{bookingId}`: Mark paid and finish. */
@Serializable data class TillRequest(val extraPence: Int = 0, val extraNote: String? = null, val tipPence: Int = 0, val method: String = TillMethod.Cash.raw)

@Serializable data class SettledResponse(val settledAt: Instant, val receipt: Receipt)

/** `GET /api/v1/money` (chairtime `lib/money/takings.ts`). */
@Serializable
data class MoneyResponse(
    /** "shop" for an owner; "mine" for anybody else, who is sent their own figures and nobody else's. */
    val scope: String = "mine",
    val shopName: String = "", val currency: String = "GBP",
    val month: Period = Period(), val lastMonth: Period = Period(), val lastMonthToDate: Period = Period(),
    val yearToDate: Period = Period(), val lastYearToDate: Period = Period(), val today: Period = Period(), val week: Period = Period(),
    val byMonth: List<MonthRow> = emptyList(), val byPerson: List<PersonRow> = emptyList(), val byService: List<ServiceRow> = emptyList(),
    val moreServices: Boolean = false, val till: Till = Till(), val deposits: Deposits = Deposits(), val discounts: Discounts = Discounts(),
    val averagePence: Pence = Pence(0),
) {
    @Serializable data class Period(val earnedPence: Pence = Pence(0), val bookedPence: Pence = Pence(0), val appointments: Int = 0, val clients: Int = 0, val walkIns: Int = 0)
    @Serializable data class TillPeriod(val settlements: Int = 0, val extraPence: Pence = Pence(0), val tipPence: Pence = Pence(0), val takenPence: Pence = Pence(0))

    @Serializable
    data class Till(
        val today: TillPeriod = TillPeriod(), val week: TillPeriod = TillPeriod(), val month: TillPeriod = TillPeriod(), val lastMonth: TillPeriod = TillPeriod(),
        val lastMonthToDate: TillPeriod = TillPeriod(), val yearToDate: TillPeriod = TillPeriod(), val lastYearToDate: TillPeriod = TillPeriod(),
        val byMethod: List<Method> = emptyList(), val everUsed: Boolean = false,
    ) {
        @Serializable data class Method(val method: String, val pence: Pence = Pence(0), val settlements: Int = 0)
    }

    /** @param month "2026-06" */
    @Serializable data class MonthRow(val month: String, val earnedPence: Pence = Pence(0), val extraPence: Pence = Pence(0), val appointments: Int = 0) {
        val tookPence: Int get() = earnedPence.value + extraPence.value
    }
    @Serializable data class PersonRow(val staffId: String, val name: String, val earnedPence: Pence = Pence(0), val appointments: Int = 0)
    @Serializable data class ServiceRow(val name: String, val earnedPence: Pence = Pence(0), val appointments: Int = 0)
    @Serializable data class Deposits(val collectedPence: Pence = Pence(0), val collectedCount: Int = 0, val uncollectedPence: Pence = Pence(0), val uncollectedCount: Int = 0)
    @Serializable data class Discounts(val listPence: Pence = Pence(0), val agreedPence: Pence = Pence(0), val givenPence: Pence = Pence(0), val bookings: Int = 0)

    val isShop: Boolean get() = scope == "shop"

    val monthTook: Int get() = took(month, till.month)
    val yearTook: Int get() = took(yearToDate, till.yearToDate)
    val lastMonthToDateTook: Int get() = took(lastMonthToDate, till.lastMonthToDate)
    val lastMonthTook: Int get() = took(lastMonth, till.lastMonth)
    val lastYearToDateTook: Int get() = took(lastYearToDate, till.lastYearToDate)

    /** Nothing completed yet: nothing to total. */
    val nothingYet: Boolean get() = yearToDate.appointments == 0 && month.bookedPence.value == 0

    companion object {
        /**
         * Service work plus anything sold in the chair — what the shop actually
         * took, composed here as the web's page composes it, so every total on
         * the screen is built the same way as the ones it is compared against.
         */
        fun took(period: Period, till: TillPeriod): Int = period.earnedPence.value + till.extraPence.value
    }
}

/** The money screen's sentences (chairtime `components/money/pieces.tsx`). */
object MoneyWords {
    enum class Tone { Level, Up, Down }

    /** @param finished "Last month finished at £4,120." — only part-way through a month. */
    data class Comparison(val text: String, val tone: Tone, val finished: String? = null)

    /** Halves away from nothing, as JavaScript's and Swift's rounding of a percentage do. */
    private fun percent(now: Int, then: Int): Int = ((now - then).toDouble() / then * 100).let { (if (it < 0) -1 else 1) * floor(abs(it) + 0.5).toInt() }

    /**
     * This month against the same span of last month, like for like. Four days
     * into September against a finished August would read "down 89%", true of
     * the arithmetic and false about the shop.
     */
    fun comparison(now: Int, then: Int, wholeLastMonth: Int, dayOfMonth: Int, currency: String): Comparison {
        if (then <= 0) return Comparison("First month with figures — nothing to compare against yet.", Tone.Level)
        val pct = percent(now, then)
        val flat = abs(pct) < 1
        val partial = dayOfMonth < 28
        val text = (if (flat) "Level" else "${if (pct > 0) "Up" else "Down"} ${abs(pct)}%") + if (partial) " on the same point last month" else " on last month"
        return Comparison(text, if (flat) Tone.Level else if (pct > 0) Tone.Up else Tone.Down, "Last month finished at ${Pence(wholeLastMonth).formatted(currency)}.".takeIf { partial && wholeLastMonth > 0 })
    }

    /** "Up 12% on last year", "Level on last year". Null with nothing to measure against. */
    fun change(now: Int, then: Int, suffix: String): String? {
        if (then <= 0) return null
        val pct = percent(now, then)
        return if (abs(pct) < 1) "Level $suffix" else "${if (pct > 0) "Up" else "Down"} ${abs(pct)}% $suffix"
    }

    /** "Jun" for "2026-06". */
    fun monthLabel(key: String): String = key.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 && it[1] in 1..12 }
        ?.let { Month.of(it[1]).getDisplayName(TextStyle.SHORT, Locale.UK).take(3) } ?: key

    /** "June" for "2026-06", for a screen reader: "Jun" is read as a word. */
    fun monthName(key: String): String = key.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 && it[1] in 1..12 }
        ?.let { Month.of(it[1]).getDisplayName(TextStyle.FULL, Locale.UK) } ?: key

    /** "cash", "card", "other" as the till writes them; anything else shown as itself. */
    fun methodLabel(method: String): String = when (method) { "cash" -> "Cash"; "card" -> "Card"; "other" -> "Other"; else -> method }

    fun appointments(n: Int): String = "$n ${if (n == 1) "appointment" else "appointments"}"
}
