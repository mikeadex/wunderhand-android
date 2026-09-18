package com.wunderhand.app.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the phone has a way out to the network.
 *
 * Used for two things only: to word a failure honestly — "no signal" and
 * "could not reach Wunderhand" send a shop to different places — and to load
 * again by itself the moment the signal comes back, so walking out of a
 * basement stockroom is enough to bring the diary up to date.
 *
 * It is never used to decide whether to try: the phone can believe it is
 * online in a hotel lobby that leads nowhere, so the app asks anyway and
 * listens to the answer.
 */
class Reachability(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Starts true: a screen loading for the first time should not accuse the
     *  phone of being offline before the system has said anything. */
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** Bumped whenever the signal comes back, for screens to watch. */
    private val _cameBack = MutableStateFlow(0)
    val cameBack: StateFlow<Int> = _cameBack.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        // The callback only speaks up when there is a network, so an aeroplane-mode
        // launch has to be read directly.
        _isOnline.value = connectivity.activeNetwork != null
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = set(true)
            override fun onLost(network: Network) = set(false)
        })
    }

    private fun set(online: Boolean) {
        if (_isOnline.value == online) return
        _isOnline.value = online
        if (online) _cameBack.update { it + 1 }
    }
}

/** The one [Reachability], for any screen that words a failure or reloads when the signal returns. */
val LocalReachability = staticCompositionLocalOf<Reachability> { error("RootScreen provides this") }
