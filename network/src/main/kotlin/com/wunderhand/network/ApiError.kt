package com.wunderhand.network

import com.wunderhand.core.ChairtimeJson
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every way a call to chairtime can fail, as something a screen can act on.
 *
 * The server's `code` decides the case; its `message` is shown as written,
 * because chairtime already words refusals the way the web shows them.
 */
sealed class ApiError(override val message: String) : Exception(message) {
    /** No live session. The app forgets its token and shows sign-in. */
    class Unauthorized(message: String) : ApiError(message)
    /** Sign-in refused: wrong details, a malformed email, too many tries. */
    class SignInRefused(message: String) : ApiError(message)
    /** The shop asked for is not one this person works at any more. */
    class NotMember(message: String) : ApiError(message)
    /** The shop is an owner's to change. The screen shows the owner-only note. */
    class NotOwner(message: String) : ApiError(message)
    class ShopInactive(message: String) : ApiError(message)
    class NotFound(message: String) : ApiError(message)
    class Validation(message: String, val field: String?) : ApiError(message)
    /** The database refused an overlap. A dragged booking goes back. */
    class SlotTaken(message: String) : ApiError(message)
    class TooEarly(message: String) : ApiError(message)
    /** A booking's rule refused it — an age limit, or a consultation first. */
    class Ineligible(val rule: String, message: String) : ApiError(message)
    /** Rung through the till already; a second press cannot count the money twice. */
    class AlreadySettled(message: String) : ApiError(message)
    /** Done, a no-show or cancelled already — perhaps on another screen. */
    class AlreadyClosed(message: String) : ApiError(message)
    /** This build is older than the API will serve. */
    class UpgradeRequired(message: String) : ApiError(message)
    /** No connection, or the server could not be reached. */
    class Offline : ApiError(OFFLINE_MESSAGE)
    /** chairtime is up but cannot answer this second: a deploy going out, a
     *  gateway between us, a moment of load. Worth trying again on its own. */
    class Unavailable(message: String, val retryAfter: Int?) : ApiError(message)
    /** The server failed, or answered with something this build cannot read. */
    class Server(message: String) : ApiError(message)

    /** Trouble that may well be gone a second later, so the app tries again
     *  before it says anything, and keeps what is already on screen. */
    val isPassing: Boolean get() = this is Offline || this is Unavailable

    /** How long the server asked us to wait, when it said — but never long
     *  enough to look like the app has hung. */
    internal val askedToWait: Duration?
        get() = (this as? Unavailable)?.retryAfter?.takeIf { it > 0 }?.let { minOf(it, 5).seconds }

    companion object {
        const val OFFLINE_MESSAGE = "No signal. Check your connection and try again."
        const val UNAVAILABLE_MESSAGE = "Wunderhand is busy for a moment. Trying again."
        const val SERVER_MESSAGE = "Something went wrong on our side. Try again in a moment."

        /** The rules a booking can be refused by: an age limit, no date of birth
         *  to check one against, or a consultation that has to come first. */
        private val eligibilityCodes = setOf("too_young", "no_date_of_birth", "missing_prerequisite")

        /** The statuses that mean "ask again", whatever the body says — a gateway
         *  or a deploy answers with its own page, not chairtime's JSON. */
        internal fun passing(status: Int, retryAfter: String?): ApiError? =
            if (status in setOf(502, 503, 504)) Unavailable(UNAVAILABLE_MESSAGE, retryAfter?.trim()?.toIntOrNull()) else null

        /** A `/api/v1` refusal, by its code. */
        internal fun from(status: Int, body: String): ApiError {
            val detail = runCatching { ChairtimeJson.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
                ?: return if (status == 401) Unauthorized("You have been signed out. Sign in again.") else Server(SERVER_MESSAGE)
            return when (detail.code) {
                "unauthorized" -> Unauthorized(detail.message)
                "not_member" -> NotMember(detail.message)
                "not_owner" -> NotOwner(detail.message)
                "shop_inactive" -> ShopInactive(detail.message)
                "not_found" -> NotFound(detail.message)
                "validation" -> Validation(detail.message, detail.field)
                "slot_taken" -> SlotTaken(detail.message)
                "too_early" -> TooEarly(detail.message)
                "already_settled" -> AlreadySettled(detail.message)
                "already_closed" -> AlreadyClosed(detail.message)
                "upgrade_required" -> UpgradeRequired(detail.message)
                in eligibilityCodes -> Ineligible(detail.code, detail.message)
                else -> Server(detail.message)
            }
        }

        /** A sign-in refusal from Better Auth, worded as the web's sign-in page
         *  words it. One message whether the email is unknown or the password is
         *  wrong — telling them apart hands out a list of who has an account. */
        internal fun signIn(status: Int, body: String): ApiError {
            val code = runCatching { ChairtimeJson.decodeFromString(AuthErrorBody.serializer(), body).code }.getOrNull()
            return when {
                status == 429 -> SignInRefused("Too many attempts in a short time. Wait a few minutes and try again.")
                code == "INVALID_EMAIL" -> SignInRefused("That does not look like an email address. Check it and try again.")
                status == 401 || code == "INVALID_EMAIL_OR_PASSWORD" -> SignInRefused("That email and password do not match. Try again.")
                else -> Server(SERVER_MESSAGE)
            }
        }

        /** Nothing came back at all: no route, no DNS, a timeout, a dropped
         *  connection. To somebody holding the phone these are all "no signal". */
        internal fun transport(@Suppress("UNUSED_PARAMETER") error: IOException): ApiError = Offline()
    }
}

/** The body chairtime's `/api/v1` sends with a refusal. */
@Serializable
internal data class ErrorBody(val error: Detail) {
    @Serializable
    data class Detail(val code: String, val message: String, val field: String? = null)
}

/** The body Better Auth sends with a refusal: `{ "code", "message" }`. */
@Serializable
internal data class AuthErrorBody(val code: String? = null, val message: String? = null)
