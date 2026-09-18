package com.wunderhand.app.app

import android.app.Application
import android.util.Log
import com.wunderhand.app.BuildConfig
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
    private val tokens = KeystoreTokenStore(app)

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
    )
}

class WunderhandApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.reachability.start()
    }
}
