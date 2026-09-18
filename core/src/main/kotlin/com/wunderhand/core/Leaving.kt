@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/*
 * Leaving: deleting a login, and closing a shop.
 *
 * Both are chairtime's (`lib/auth/delete-login.ts`, `lib/shop/close.ts`) and
 * both are on the web as well — which is what Google Play asks for: deletion
 * in the app, and at a public web address. A shop that trusts us with its
 * client list should find the way out without writing to anybody.
 */

/** What deleting a login left behind: the shops it was on the team at. */
@Serializable
data class LoginDeleted(val deleted: Boolean = true, /** How many staff rows were detached — the shops they are no longer on. */ val shopsLeft: Int = 0)

/** A shop with more than one owner is not one owner's to close: the request waits for the others, and any of them can end it (chairtime #20). */
@Serializable
data class ShopClosure(
    val id: String,
    val requestedBy: Who,
    val requestedAt: Instant,
    /** After this the request lapses and the shop stays open. */
    val expiresAt: Instant,
    /** The other owners who can sign in, and who has agreed. */
    val partners: List<Partner> = emptyList(),
    val mine: Standing = Standing.None,
) {
    @Serializable data class Who(val staffId: String, val name: String)

    /** Another owner, and whether they have said yes. */
    @Serializable data class Partner(val staffId: String, val name: String, val agreed: Boolean = false)

    /** What the person looking at it is to the request. */
    @Serializable(with = StandingSerializer::class)
    enum class Standing(val raw: String) {
        /** They asked. */
        Requester("requester"),
        /** Their word is wanted, and they have not given it. */
        Partner("partner"),
        /** They have agreed, and it waits on somebody else. */
        Agreed("agreed"),
        /** Neither — and a word this build does not know reads as this: nobody's to act on. */
        None("none"),
    }

    val waitingOn: List<Partner> get() = partners.filter { !it.agreed }
}

/** A standing this build has not heard of is nobody's to act on: no button, not a guess. */
object StandingSerializer : KSerializer<ShopClosure.Standing> {
    override val descriptor = PrimitiveSerialDescriptor("Standing", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): ShopClosure.Standing = decoder.decodeString().let { raw -> ShopClosure.Standing.entries.firstOrNull { it.raw == raw } ?: ShopClosure.Standing.None }
    override fun serialize(encoder: Encoder, value: ShopClosure.Standing) = encoder.encodeString(value.raw)
}

/** What closing this shop would mean, asked before it is asked for. */
@Serializable
data class ShopClosing(
    /** The shop's address, which is what closing asks to be typed out. */
    val slug: String,
    val status: String,
    /** How long the records stay after closing. */
    val graceDays: Int,
    /** How long a request waits for the other owners before it lapses. Optional,
     *  with `closure`, because a server older than chairtime #20 says nothing
     *  about requests — and a screen that worked yesterday should not break
     *  on the answer it gets today. */
    val requestDays: Int? = null,
    /** When they go, for a shop already closed. */
    val deleteAfter: Instant? = null,
    /** Appointments still to come. Closing does not tell those clients. */
    val upcoming: Int = 0,
    /** The request waiting on the other owners, if there is one. */
    val closure: ShopClosure? = null,
) {
    val isClosed: Boolean get() = status == "closed"
}

/** What asking to close, or agreeing to, comes back with: either the shop is closed, or a request is open and waiting on somebody. */
@Serializable
data class ShopCloseResult(val closed: Boolean = false, val deleteAfter: Instant? = null, val upcoming: Int? = null, val closure: ShopClosure? = null)

@Serializable data class PasswordWrite(val password: String)
@Serializable data class CloseShopWrite(val password: String, val confirm: String)

/** The sentences about leaving, where a number changes them. */
object LeavingWords {
    /** What is still in the diary when a shop is closed. Closing tells no client anything, which is the part worth saying out loud. */
    fun upcoming(count: Int): String = when (count) {
        0 -> "Nothing is booked ahead."
        1 -> "1 appointment is booked ahead. Closing does not tell that client — cancel it first, or ring them."
        else -> "$count appointments are booked ahead. Closing does not tell those clients — cancel them first, or ring them."
    }

    /** When the records go, for a shop about to be closed. */
    fun recordsGo(inDays: Int): String =
        "Your records are deleted $inDays days later: clients, appointments, consent forms and medical notes. Until then you can sign in, read it all and export it."

    /** When the records go, for a shop already closed. */
    fun recordsGo(on: Instant, clock: ShopClock): String =
        "Everything here is deleted on ${clock.longDate(on)}: clients, appointments, consent forms and medical notes. Until then you can sign in, read it and export it."

    /** Who a request is still waiting on. */
    fun waitingOn(partners: List<ShopClosure.Partner>): String {
        val names = partners.map { it.name }
        return when (names.size) {
            0 -> "Everybody has agreed."
            1 -> "Waiting on ${names[0]}."
            else -> "Waiting on ${names.dropLast(1).joinToString(", ")} and ${names.last()}."
        }
    }

    /** When a request lapses if nobody answers. */
    fun lapses(on: Instant, clock: ShopClock): String = "If nobody answers by ${clock.longDate(on)} the request lapses and the shop stays open."

    /** What deleting a login leaves, said afterwards. */
    fun leftBehind(shops: Int): String = when (shops) {
        0 -> "Your login is gone."
        1 -> "Your login is gone, and you are off the team at that shop."
        else -> "Your login is gone, and you are off the team at $shops shops."
    }
}
