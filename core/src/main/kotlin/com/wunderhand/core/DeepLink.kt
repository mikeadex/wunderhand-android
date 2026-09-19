package com.wunderhand.core

import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Somewhere in the app a link or a tapped notification points to.
 *
 * Three ways in, one answer: `wunderhand://appointment/<id>?shop=<tenant>`,
 * `https://wunderhand.com/diary/<id>` (the web's own address for an
 * appointment, so a link in an email opens the app where it is installed),
 * and the `appointmentId` a push carries.
 *
 * Everything that arrives here is somebody else's text — a link in a message,
 * the data of a notification — so nothing is a link unless it is exactly one:
 * an id is only ever a UUID, and a crafted one cannot steer the app anywhere else.
 */
sealed interface DeepLink {
    /** The shop the link is for, when it says: somebody in two shops may be looking at the other one. */
    val shopId: String?

    data class Appointment(val id: String, override val shopId: String?) : DeepLink

    /** A time that came free: "Offer the gap" on a cancellation. */
    data class Gap(val staffId: String, val from: Instant, val to: Instant, override val shopId: String?) : DeepLink

    /** As something the system can keep and hand back across a sign-in or a change of shop. */
    fun packed(): ArrayList<String> = when (this) {
        is Appointment -> arrayListOf("appointment", id, shopId.orEmpty())
        is Gap -> arrayListOf("gap", staffId, from.toString(), to.toString(), shopId.orEmpty())
    }

    companion object {
        const val SCHEME = "wunderhand"
        val webHosts = setOf("wunderhand.com", "www.wunderhand.com")

        private fun uuid(text: String?): String? = text?.trim()?.takeIf { it.length == 36 && runCatching { UUID.fromString(it) }.isSuccess }?.lowercase()
        private fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

        /** A link as text: the app's own scheme, or the web's address for an appointment over https. */
        fun from(raw: String?): DeepLink? {
            val uri = raw?.let { runCatching { URI(it.trim()) }.getOrNull() } ?: return null
            val parts = uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
            val shop = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("shop=") }?.removePrefix("shop=")
            return when (uri.scheme?.lowercase()) {
                // wunderhand://appointment/<id> — the host is the first word.
                SCHEME -> if (uri.host?.lowercase() == "appointment" && parts.size == 1) appointment(parts[0], shop) else null
                "https" -> if (uri.host?.lowercase() in webHosts && uri.userInfo == null && parts.size == 2 && parts[0] == "diary") appointment(parts[1], shop) else null
                else -> null
            }
        }

        /** What a push carries beside its words (`lib/push/notify.ts`). Anything that is not an appointment's id is not a link. */
        fun appointment(appointmentId: String?, shopId: String?): DeepLink? = uuid(appointmentId)?.let { Appointment(it, uuid(shopId)) }

        /**
         * "Offer the gap" on a cancelled push: whose time came free, and when.
         * Refused unless it is a real person's id and a sensible window — a day
         * at most, ending after it starts.
         */
        fun gap(staffId: String?, startsAt: String?, endsAt: String?, shopId: String?): DeepLink? {
            val staff = uuid(staffId) ?: return null
            val from = instant(startsAt) ?: return null
            val to = instant(endsAt) ?: return null
            if (to <= from || Duration.between(from, to) > Duration.ofHours(24)) return null
            return Gap(staff, from, to, uuid(shopId))
        }

        /** Back from [packed]. Checked again on the way out: saved state is somebody else's text too. */
        fun unpack(v: List<String>?): DeepLink? = when (v?.firstOrNull()) {
            "appointment" -> if (v.size == 3) appointment(v[1], v[2]) else null
            "gap" -> if (v.size == 5) gap(v[1], v[2], v[3], v[4]) else null
            else -> null
        }
    }
}

/**
 * What chairtime sends a phone (`lib/push/notify.ts`), as FCM hands it over:
 * a flat map of strings. **Data only** — the app draws the notification
 * itself, so it can choose the channel and offer "Offer the gap".
 *
 * The words are chairtime's, written for whoever is reading: a non-owner's
 * carry no price, and that is decided there, above the platform split. The
 * app never adds to them.
 */
data class PushPayload(
    val title: String,
    val body: String,
    /** booked, offer_accepted, cancelled, replied — or a word this build has not heard of, which is still news. */
    val kind: String,
    val open: DeepLink?,
    /** The time that came free, on a cancellation that carried it. */
    val gap: DeepLink?,
) {
    enum class Channel(val id: String, val title: String, val about: String) {
        Bookings("bookings", "Bookings", "A new booking, a gap filled, a client's reply."),
        Cancellations("cancellations", "Cancellations", "A client cancelled, and the time that came free."),
    }

    /** Two channels, so a shop can silence one in the phone's settings without losing the other. */
    val channel: Channel get() = if (kind == "cancelled") Channel.Cancellations else Channel.Bookings

    /** The same news twice replaces itself; different news about the same appointment does not. */
    val notificationId: Int get() = ((open as? DeepLink.Appointment)?.id.orEmpty() + "|" + kind).hashCode()

    /** A shop's notifications sit together. */
    val group: String? get() = open?.shopId ?: gap?.shopId

    companion object {
        const val OFFER_GAP = "OFFER_GAP"

        /** Null for anything with no words: a notification that says nothing is worse than none. */
        fun from(data: Map<String, String>): PushPayload? {
            val title = data["title"]?.trim().orEmpty()
            val body = data["body"]?.trim().orEmpty()
            if (title.isEmpty() || body.isEmpty()) return null
            val shop = data["tenantId"]
            return PushPayload(
                // Somebody else's text, on a lock screen: kept to a lock screen's length.
                title.take(120), body.take(400), data["kind"].orEmpty(),
                open = DeepLink.appointment(data["appointmentId"], shop),
                gap = DeepLink.gap(data["staffId"], data["startsAt"], data["endsAt"], shop),
            )
        }
    }
}

/** `POST /api/v1/devices`: this phone, for the shop now open. FCM's token, which is not hex and needs no environment. */
@Serializable
data class DeviceRegistration(val token: String, val appVersion: String, val platform: String = "android")

/** `POST /api/v1/devices/release`: in the body, not the address — a phone's token is not something for a server's access log. */
@Serializable
data class DeviceRelease(val token: String, val platform: String = "android")
