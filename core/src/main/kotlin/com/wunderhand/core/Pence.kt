package com.wunderhand.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * An amount of money in the currency's minor unit, as the API sends it.
 *
 * The app shows prices; it never works one out. Arithmetic on money lives in
 * chairtime, and this type is deliberately short of operators so that a
 * screen cannot quietly start adding deposits up for itself.
 */
@Serializable
@JvmInline
value class Pence(val value: Int) : Comparable<Pence> {
    override fun compareTo(other: Pence): Int = value.compareTo(other.value)

    /** "£28" for a whole amount, "£37.40" otherwise — `money` in chairtime's `lib/format.ts`. */
    fun formatted(currency: String): String {
        val digits = if (value % 100 == 0) 0 else 2
        val format = NumberFormat.getCurrencyInstance(Locale.UK).apply {
            this.currency = runCatching { Currency.getInstance(currency) }.getOrDefault(Currency.getInstance("GBP"))
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return format.format(BigDecimal(value).movePointLeft(2))
    }
}
