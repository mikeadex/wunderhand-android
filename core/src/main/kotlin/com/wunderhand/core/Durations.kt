package com.wunderhand.core

/** Lengths of time in the words the web uses (`lib/format.ts`). */
object Durations {
    /** "45m", "1h", "1h 30m" — on a diary row, where space is short. */
    fun short(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** "45 min", "1h", "1h 30" — in a sentence. */
    fun label(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "${h}h"
            else -> "${h}h $m"
        }
    }
}
