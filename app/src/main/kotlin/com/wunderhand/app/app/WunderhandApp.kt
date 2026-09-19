package com.wunderhand.app.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.wunderhand.app.BuildConfig
import com.wunderhand.app.features.clients.BiometricGate
import com.wunderhand.app.push.FcmTokens
import com.wunderhand.app.push.PushCoordinator
import com.wunderhand.app.push.PushNotifier
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiClient
import java.io.File

/**
 * Everything that lives as long as the app does, made by hand.
 *
 * There is no dependency-injection framework here on purpose: the whole graph
 * is these few lines, and a framework would be the largest thing in the app.
 */
class AppContainer(app: Application) {
    val reachability = Reachability(app)
    /** The lock in front of medical notes. One for the process: leaving the app locks every screen that could show them. */
    val notesGate = BiometricGate()
    private val tokens = KeystoreTokenStore(app)

    /** This phone's pushes. One for the process: an app has one token, whoever is signed in. */
    val notifier = PushNotifier(app)
    val fcm = FcmTokens(app)
    val push = PushCoordinator(fcm, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", notifier::mayNotify)

    val model = AppModel(
        settings = DataStoreSettings(app),
        // Private to the app, encrypted at rest by Android, and out of every backup.
        cache = OfflineCache(File(app.noBackupFilesDir, "offline")),
        builtInServer = BuildConfig.API_BASE_URL,
        connect = { baseUrl ->
            ApiClient(
                baseUrl = baseUrl,
                tokens = tokens,
                build = BuildConfig.VERSION_NAME,
                log = { if (BuildConfig.DEBUG) Log.w("Wunderhand", it) },
            )
        },
        // Signed out, or another shop: whoever unlocked the notes did so for the last one.
        onLeaving = notesGate::close,
        // Off chairtime's list while the session can still say so, and the token thrown away.
        beforeSignOut = { api -> notifier.clear(); push.signingOut(api) },
    )
}

class WunderhandApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.reachability.start()
        container.notifier.ensureChannels()
        // Sent to the background: the unlock goes with it, however soon they are back.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) container.notesGate.close()
        })
    }
}
