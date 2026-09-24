package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `GET /api/v1/me`: who is signed in, the shop they are acting for, and what
 * that shop may do.
 *
 * Mirrors `MeResponse` in chairtime's `lib/api/v1/schemas.ts`, and is decoded
 * in tests from the fixture that file's own test writes, so the two cannot
 * drift apart quietly.
 */
@Serializable
data class Me(
    val user: User,
    val staff: Staff,
    val shop: Shop,
    val shops: List<ShopSummary>,
) {
    @Serializable
    data class User(val id: String, val email: String)

    @Serializable
    data class Staff(
        val id: String,
        val name: String,
        /** Sees the whole shop's money and may do the owner-only acts. */
        val isOwner: Boolean,
    )

    @Serializable
    data class Shop(
        val id: String,
        val name: String,
        val slug: String,
        /** IANA name. Every date and time in the app is drawn in this zone,
         *  never the phone's — a pro on holiday abroad still sees the shop's day. */
        val timezone: String,
        val currency: String,
        val status: TenantStatus,
        val capabilities: Capabilities,
        /** How many outlets: the app names one only when there is a choice. */
        val outlets: Int? = null,
    ) {
        val hasSeveralOutlets: Boolean get() = (outlets ?: 1) > 1
    }

    /** One of the shops this person works at. Most people have one. */
    @Serializable
    data class ShopSummary(val id: String, val name: String, val slug: String, val staffId: String)

    /** More than one shop, and none chosen: the app has to ask. */
    val worksAtSeveral: Boolean get() = shops.size > 1
}

/** What a shop in its current state may still do (chairtime `lib/platform/status.ts`). */
@Serializable
data class Capabilities(
    val signIn: Boolean,
    val publicBooking: Boolean,
    val proBooking: Boolean,
    val takePayments: Boolean,
    val messaging: Boolean,
    val addSeats: Boolean,
)

/**
 * A shop's standing. A status this build does not know yet is kept as the
 * word it arrived as, rather than refused, so a new state on the server does
 * not stop an older app from opening.
 */
@Serializable(with = TenantStatusSerializer::class)
sealed interface TenantStatus {
    val raw: String

    data object Setup : TenantStatus { override val raw = "setup" }
    data object Active : TenantStatus { override val raw = "active" }
    data object TrialEnded : TenantStatus { override val raw = "trial_ended" }
    data object PastDue : TenantStatus { override val raw = "past_due" }
    data object Suspended : TenantStatus { override val raw = "suspended" }
    data object Closed : TenantStatus { override val raw = "closed" }
    data class Unknown(override val raw: String) : TenantStatus

    companion object {
        private val known = listOf(Setup, Active, TrialEnded, PastDue, Suspended, Closed)
        fun of(raw: String): TenantStatus = known.firstOrNull { it.raw == raw } ?: Unknown(raw)
    }
}

object TenantStatusSerializer : KSerializer<TenantStatus> {
    override val descriptor = PrimitiveSerialDescriptor("TenantStatus", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): TenantStatus = TenantStatus.of(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: TenantStatus) = encoder.encodeString(value.raw)
}
