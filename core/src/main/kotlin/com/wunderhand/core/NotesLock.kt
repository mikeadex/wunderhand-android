package com.wunderhand.core

import java.time.Duration
import java.time.Instant

/**
 * Whether medical notes may be shown without asking again.
 *
 * A phone in a shop is picked up by whoever is nearest, so special category
 * data is not left one tap away. Unlocking lasts long enough to check two
 * clients back to back — not long enough for the phone to be put down and
 * picked up by somebody else — and leaving the app ends it at once.
 */
data class NotesLock(val unlockedAt: Instant? = null) {
    fun isUnlocked(now: Instant): Boolean {
        val since = Duration.between(unlockedAt ?: return false, now)
        // A clock moved backwards is not a reason to stay open.
        return !since.isNegative && since < GRACE
    }

    fun unlocked(now: Instant) = NotesLock(now)

    /** Left the app, signed out, changed shop. */
    fun locked() = NotesLock(null)

    companion object {
        /** How long an unlock is good for. */
        val GRACE: Duration = Duration.ofSeconds(120)
    }
}

/** What the lock is called on this device, and what it says. */
object UnlockWords {
    /** Android does not say which — a fingerprint, a face — only that the
     *  phone has one. So it is "your fingerprint or face", or the screen lock. */
    enum class Method(val words: String) { Biometric("your fingerprint or face"), ScreenLock("your screen lock") }

    fun button(method: Method): String = "Unlock with ${method.words}"

    fun locked(clientName: String, method: Method): String =
        "${clientName.substringBefore(' ')}’s notes open with ${method.words}, so a phone left on the counter does not open them for anybody. Nothing is fetched until it does."

    /** The title of the system's prompt. */
    fun reason(clientName: String): String = "Open $clientName’s medical notes"

    const val DID_NOT_MATCH = "That did not match. Try again."
    const val UNAVAILABLE = "This phone could not check it is you just now. Try again."

    /** The phone itself is not locked, so neither can the notes be. */
    const val NO_SCREEN_LOCK = "This phone has no screen lock, so medical notes cannot be locked on it. Set one in Settings to keep them to you."
}
