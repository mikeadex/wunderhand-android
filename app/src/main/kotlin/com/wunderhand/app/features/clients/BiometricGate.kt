package com.wunderhand.app.features.clients

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.wunderhand.core.UnlockWords
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The phone's own check that it is you: a fingerprint or a face where the
 * phone has one, the screen lock — PIN, pattern, password — where it does not
 * or they fail. The app never sees which, or any of it: only yes or no.
 */
class BiometricGate : TimedGate() {
    /** The prompt is the system's, shown over whichever activity is in front. */
    private var activity = WeakReference<FragmentActivity>(null)
    fun attach(activity: FragmentActivity) { this.activity = WeakReference(activity) }

    private val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override val method: UnlockWords.Method
        get() {
            val here = activity.get() ?: return UnlockWords.Method.ScreenLock
            val hasBiometric = BiometricManager.from(here).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            return if (hasBiometric) UnlockWords.Method.Biometric else UnlockWords.Method.ScreenLock
        }

    override suspend fun unlock(reason: String): NotesGate.Outcome {
        if (isUnlocked()) return NotesGate.Outcome.Unlocked
        val here = activity.get() ?: return NotesGate.Outcome.Failed(UnlockWords.UNAVAILABLE)

        when (BiometricManager.from(here).canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            // Nothing enrolled, with the screen lock allowed, means there is no screen lock either.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> return NotesGate.Outcome.Unprotected
            else -> return NotesGate.Outcome.Failed(UnlockWords.UNAVAILABLE)
        }

        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(here, ContextCompat.getMainExecutor(here), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    opened()
                    if (continuation.isActive) continuation.resume(NotesGate.Outcome.Unlocked)
                }

                // A finger that did not match leaves the prompt up to try again: not an answer yet.

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (!continuation.isActive) return
                    continuation.resume(
                        when (code) {
                            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> NotesGate.Outcome.Cancelled
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> NotesGate.Outcome.Unprotected
                            else -> NotesGate.Outcome.Failed(UnlockWords.UNAVAILABLE)
                        },
                    )
                }
            })
            // No negative button: with the screen lock allowed, the system supplies the way out.
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(reason).setAllowedAuthenticators(allowed).build())
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

val LocalNotesGate = staticCompositionLocalOf<NotesGate> { error("RootScreen provides this") }
