@file:UseSerializers(InstantSerializer::class)

package com.wunderhand.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

// Clients (chairtime `app/(pro)/clients`).

/** The list's filters, as the web's chips name them. */
enum class ClientFilter(val raw: String, val label: String) {
    All("all", "All"), Regulars("regulars", "Regulars"), Due("due", "Due a rebook"), Lapsed("lapsed", "Lapsed"), NoShows("no_shows", "No-shows"),
    /** Added from an appointment that had nobody on it (see [AttachClientRequest]). */
    WalkIns("walk_ins", "Walk-ins"),
}

/**
 * `POST /api/v1/appointments/{id}/client`: somebody for an appointment that had
 * nobody on it. A walk-in who gave their name at the chair becomes a client, so
 * the visit counts and there is somebody to book again. Either an existing
 * client, or a new one made from what they said.
 */
@Serializable
data class AttachClientRequest(val clientId: String? = null, val name: String? = null, val phone: String? = null, val email: String? = null) {
    companion object {
        fun existing(clientId: String) = AttachClientRequest(clientId = clientId)
        fun new(name: String, phone: String = "", email: String = "") = AttachClientRequest(name = name, phone = phone.ifBlank { null }, email = email.ifBlank { null })
    }
}

/** The first two words' initials, as the web's avatars have always shown them. */
fun initialsOf(name: String): String = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** A person on the list. */
@Serializable
data class ClientRow(
    val id: String,
    val name: String,
    val phone: String? = null,
    val visitCount: Int = 0,
    /** What they have spent here: an owner's to see, null for anyone else. */
    val spendPence: Pence? = null,
    val noShowCount: Int = 0,
    /** How they came to be on the list: "walk_in", "online", "import", "manual" — or null, from before it was kept. */
    val source: String? = null,
    val averageIntervalDays: Double? = null,
    val lastVisitAt: Instant? = null,
    val nextAppointmentAt: Instant? = null,
    /** Past their own usual gap since they were last in. */
    val overdue: Boolean = false,
) {
    val initials: String get() = initialsOf(name)

    /** "7 visits · £56" — the missed count is its own, red, part. */
    fun visitsAndSpend(currency: String): String {
        val visits = "$visitCount ${if (visitCount == 1) "visit" else "visits"}"
        return spendPence?.takeIf { it.value > 0 }?.let { "$visits · ${it.formatted(currency)}" } ?: visits
    }

    /** "Booked" when something is coming; "Due" when nothing is and they are past their gap. */
    val tag: String? get() = if (nextAppointmentAt != null) "Booked" else if (overdue) "Due" else null
}

/** `GET /api/v1/clients` */
@Serializable
data class ClientsResponse(
    val clients: List<ClientRow>,
    val counts: Map<String, Int> = emptyMap(),
    /** The list stops here; anybody past it is found by searching. */
    val limit: Int = 200,
) {
    fun count(filter: ClientFilter): Int = counts[filter.raw] ?: 0
    val isCapped: Boolean get() = clients.size >= limit
}

/** `GET /api/v1/clients/{id}` */
@Serializable
data class ClientProfileResponse(
    val client: Profile,
    val due: Boolean = false,
    val regular: Boolean = false,
    /** Two or more no-shows: online bookings are paid in full. */
    val paysInFull: Boolean = false,
    /** Whether medical notes exist — the profile never carries what they say. */
    val hasHealthRecord: Boolean = false,
    val history: List<Visit> = emptyList(),
    val projects: List<Piece> = emptyList(),
) {
    @Serializable
    data class Profile(
        val id: String,
        val name: String,
        val phone: String? = null,
        val visitCount: Int = 0,
        /** An owner's to see; null for anyone else, as are the visits' prices. */
        val spendPence: Pence? = null,
        val noShowCount: Int = 0,
        val averageIntervalDays: Double? = null,
        val lastVisitAt: Instant? = null,
        val nextAppointmentAt: Instant? = null,
        val overdue: Boolean = false,
        val email: String? = null,
        /** "1991-03-04", for the form. */
        val dateOfBirth: String? = null,
        val notes: String? = null,
        val standingFormula: String? = null,
    )

    @Serializable
    data class Visit(val id: String, val startsAt: Instant, val serviceName: String, val pricePence: Pence? = null, val status: String) {
        /** Crossed through: it did not happen. */
        val isMissed: Boolean get() = status == "no_show" || status == "cancelled"

        /** The web's tag for anything but an ordinary booking. */
        val tag: String?
            get() = when (status) {
                "no_show" -> "No-show"
                "cancelled" -> "Cancelled"
                "unconfirmed" -> "Unconfirmed"
                "in_progress" -> "In the chair"
                else -> null
            }
    }

    /** A body of work in progress — a sleeve over several sittings. */
    @Serializable
    data class Piece(
        val id: String, val name: String, val sittingsDone: Int = 0, val sittingsBooked: Int = 0,
        val unbookedPence: Pence? = null,
        /** An owner's to see; null for anyone else. */
        val paidPence: Pence? = null,
    ) {
        fun line(currency: String): String {
            var line = "$sittingsDone done"
            if (sittingsBooked > 0) line += ", $sittingsBooked booked"
            unbookedPence?.takeIf { it.value > 0 }?.let { line += " · ${it.formatted(currency)} still to book" }
            return line
        }
    }

    /** What is coming, soonest first. */
    fun upcoming(now: Instant): List<Visit> = history.filter { it.startsAt > now }.reversed()
    /** What happened, most recent first. */
    fun past(now: Instant): List<Visit> = history.filter { it.startsAt <= now }

    /** "Usually every 5 weeks", from their average gap. */
    val usualWeeks: Int? get() = client.averageIntervalDays?.let { max(1, (it / 7).roundToInt()) }

    data class Status(val text: String, val isDue: Boolean)

    /** The eyebrow over the name, as the web's profile has it. */
    fun status(clock: ShopClock): Status = when {
        client.nextAppointmentAt != null -> Status("Booked in ${clock.dayMonth(client.nextAppointmentAt)}", false)
        due -> Status("Due a rebook", true)
        regular -> Status("Regular", false)
        client.visitCount == 0 -> Status("New client", false)
        else -> Status("Client", false)
    }
}

/** The client form: `POST /api/v1/clients` and `PUT /api/v1/clients/{id}`. */
@Serializable
data class ClientInput(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    /** "1991-03-04", or empty. */
    val dateOfBirth: String = "",
    val notes: String = "",
    val standingFormula: String = "",
) {
    constructor(profile: ClientProfileResponse.Profile) : this(
        profile.name, profile.phone.orEmpty(), profile.email.orEmpty(), profile.dateOfBirth.orEmpty(), profile.notes.orEmpty(), profile.standingFormula.orEmpty(),
    )
}

@Serializable
data class ClientSaved(val id: String)

/** `GET /api/v1/clients/{id}/health` — medical notes, and who has opened them. */
@Serializable
data class HealthResponse(
    val clientName: String,
    /** False when chairtime has no encryption key: nothing can be stored. */
    val configured: Boolean = true,
    /** A record exists but could not be read. Nothing has been changed. */
    val unreadable: Boolean = false,
    val fields: List<Field> = emptyList(),
    val record: Map<String, String?>? = null,
    val updatedAt: Instant? = null,
    val updatedByName: String? = null,
    val retainUntil: Instant? = null,
    val consents: List<Consent> = emptyList(),
    val accessLog: List<Access> = emptyList(),
) {
    @Serializable
    data class Field(val key: String, val label: String, val hint: String = "")

    @Serializable
    data class Consent(
        val id: String, val title: String, val grantedAt: Instant, val standing: String, val recordedBy: String? = null,
        /** The words signed; null when they were not kept. */
        val body: String? = null,
    )

    @Serializable
    data class Access(val at: Instant, val who: String, val what: String)

    fun value(key: String): String = record?.get(key).orEmpty()

    /** Anything written in any field. */
    val hasContent: Boolean get() = record?.values?.any { !it.isNullOrEmpty() } ?: false

    val canEdit: Boolean get() = configured && !unreadable
}

@Serializable
data class HealthSaved(val saved: Boolean = true, val retainUntil: Instant? = null)

/** The sentences the client screens say. */
object ClientWords {
    fun people(count: Int): String = "$count ${if (count == 1) "person" else "people"}"

    /** Saying "200 people" at a shop with nine hundred made everybody past the limit look missing. */
    fun capped(limit: Int): String = "Showing the first $limit by name. Search to find anyone else — everybody is still here."

    fun bookedIn(at: Instant, clock: ShopClock): String = "Booked in for ${clock.shortDayTime(at)}."

    fun healthSaved(retainUntil: Instant?, clock: ShopClock): String =
        "Saved and encrypted." + (retainUntil?.let { " Kept until ${clock.longDate(it)}." } ?: "")

    const val HEALTH_ERASED = "Erased. The record is gone, not hidden — only the fact that it was deleted remains."
    const val HEALTH_OFF = "Medical notes are switched off because no encryption key is set. This data has to be encrypted before it is stored, so Wunderhand would rather hold none of it than hold it in the clear."
    const val HEALTH_UNREADABLE = "This record could not be read. Nothing has been changed."
    const val CONSENT_NOT_KEPT = "This was recorded before the wording was kept, so the exact text cannot be produced. If it matters — a claim, an insurer, a request — treat it as unevidenced and take it again."
}
