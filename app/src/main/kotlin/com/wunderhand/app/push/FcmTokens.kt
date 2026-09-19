package com.wunderhand.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.wunderhand.app.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firebase's push tokens, set up by hand.
 *
 * Not through the google-services plugin, which fails the build when
 * google-services.json is missing: where Firebase is comes from
 * `firebase.properties` (scripts/firebase-config.sh), and a build without it
 * has push off and is otherwise the same app.
 *
 * Nothing here runs until somebody is signed in and has said yes to
 * notifications. Firebase's own start-up — which would mint a token at first
 * launch, for nobody — is switched off in the manifest.
 */
class FcmTokens(private val context: Context) : PushTokens {
    override val isConfigured: Boolean = listOf(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_SENDER_ID).all { it.isNotBlank() }

    private fun messaging(): FirebaseMessaging? {
        if (!isConfigured) return null
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build(),
            )
        }
        return FirebaseMessaging.getInstance()
    }

    /** For the messaging service, which Android may start in a process where nothing else has run yet. */
    fun ready(): Boolean = runCatching { messaging() != null }.getOrDefault(false)

    override suspend fun token(): String? {
        val messaging = runCatching { messaging() }.getOrNull() ?: return null
        return suspendCancellableCoroutine { next ->
            // No Play services, no signal: no token, and the next diary load asks again.
            messaging.token.addOnCompleteListener { task -> next.resume(task.result.takeIf { task.isSuccessful }?.takeIf { it.isNotBlank() }) }
        }
    }

    override suspend fun forget() {
        val messaging = runCatching { messaging() }.getOrNull() ?: return
        suspendCancellableCoroutine { next -> messaging.deleteToken().addOnCompleteListener { next.resume(Unit) } }
    }
}
