package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * The little the app keeps on the phone so it can open without an answer.
 *
 * A shop opening up in a signal hole still needs to know who is coming at
 * nine. Two things make that possible: who was signed in, and the last day
 * they loaded. Both are read only when chairtime cannot be reached — never
 * in front of a fresh answer, and never silently, because the screen says
 * how old what it shows is.
 *
 * **What is kept.** Who this is, the shop they work for, and one diary day
 * per shop: names, times, services, the day's sums. It lives in the app's private files, which Android
 * encrypts at rest and which the backup rules leave out, and all of it is
 * thrown away at sign-out and when the shop changes. Medical notes are never
 * part of it — they are never kept on the phone at all.
 *
 * The token still decides what the app may fetch. This is only what it last
 * saw, shown while it waits to be told again.
 */
class OfflineCache(private val directory: File) {
    private val whoFile get() = File(directory, "me.json")

    /** Writing is never worth telling anybody about: the screen in front of
     *  them is fine, and the next load will try again. */
    private fun <T> write(serializer: KSerializer<T>, value: T, to: File) {
        runCatching {
            directory.mkdirs()
            // Written beside it and moved into place, so a read never finds half a file.
            val draft = File(directory, "${to.name}.tmp")
            draft.writeText(ChairtimeJson.encodeToString(serializer, value))
            if (!draft.renameTo(to)) {
                to.delete()
                draft.renameTo(to)
            }
        }
    }

    private fun <T> read(serializer: KSerializer<T>, from: File): T? =
        runCatching { ChairtimeJson.decodeFromString(serializer, from.readText()) }.getOrNull()

    /** Who was signed in, so a launch without signal opens on their diary
     *  rather than on a screen about the network. */
    fun save(me: Me) = write(Me.serializer(), me, whoFile)

    fun savedMe(): Me? = read(Me.serializer(), whoFile)

    /** A kept day, and when it was loaded — for the words over a stale screen. */
    @Serializable
    data class SavedDay(@Serializable(with = InstantSerializer::class) val at: Instant, val response: DiaryResponse)

    // The shop's id is a UUID from chairtime, so it is already a safe file name.
    private fun dayFile(shopId: String) = File(directory, "diary-$shopId.json")

    /** Keep this day. */
    fun save(response: DiaryResponse, at: Instant, shopId: String) =
        write(SavedDay.serializer(), SavedDay(at, response), dayFile(shopId))

    /** The day kept for this shop, when it is the day being asked for. A day
     *  kept yesterday is no use this morning, so it is ignored rather than shown. */
    fun savedDay(shopId: String, date: String): SavedDay? =
        read(SavedDay.serializer(), dayFile(shopId))?.takeIf { it.response.date == date }

    /** At sign-out, and when the shop changes: nothing of one shop's day is
     *  left on a phone that has moved to another. */
    fun clear() {
        directory.deleteRecursively()
    }
}
