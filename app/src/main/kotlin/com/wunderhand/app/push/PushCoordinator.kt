package com.wunderhand.app.push

import com.wunderhand.core.DeepLink
import com.wunderhand.network.DevicesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a phone's push token comes from. Firebase's, behind a door a test can stand in. */
interface PushTokens {
    /** False on a build that has never been told where Firebase is: push is off, and nothing else notices. */
    val isConfigured: Boolean
    /** This phone's token, asked for only once somebody has said yes to notifications. Null when there is none to be had. */
    suspend fun token(): String?
    /** Signing out: the token is thrown away, so nothing more can reach this phone for whoever just left. */
    suspend fun forget()
}

/**
 * This phone's pushes: telling chairtime where to send them, taking the phone
 * back off the list on the way out, and turning a tapped notification or a
 * link into somewhere to go.
 *
 * One for the process, because that is what it describes — an app has one
 * token, whoever is signed in.
 *
 * @param mayNotify whether the phone will show a notification at all: the
 *   permission, and the switch in the system's settings. chairtime is not
 *   told about a phone that would only swallow what it is sent.
 */
class PushCoordinator(private val tokens: PushTokens, private val appVersion: String, private val mayNotify: () -> Boolean) {
    /** A link waiting to be followed. It waits through sign-in and a change of shop; the diary clears it. */
    private val _pendingLink = MutableStateFlow<DeepLink?>(null)
    val pendingLink: StateFlow<DeepLink?> = _pendingLink.asStateFlow()

    /** Something arrived while the app was open: whatever is on screen may be out of date. */
    private val _news = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val news: SharedFlow<Unit> = _news.asSharedFlow()

    val isConfigured: Boolean get() = tokens.isConfigured

    private val mutex = Mutex()
    private var api: DevicesApi? = null
    private var shopId: String? = null
    private var token: String? = null
    /** The token and shop chairtime last heard about, so it hears once. */
    private var registered: String? = null

    fun follow(link: DeepLink?) { if (link != null) _pendingLink.value = link }
    fun followed() { _pendingLink.value = null }
    fun arrived() { _news.tryEmit(Unit) }

    /**
     * The diary has loaded — for this person, at this shop. Safe to call on
     * every load: chairtime hears once for each token and shop, and again only
     * when one of them changes.
     */
    suspend fun diaryLoaded(api: DevicesApi, shopId: String) = mutex.withLock {
        this.api = api
        this.shopId = shopId
        register()
    }

    /** Firebase minted a new token: the old one is dead, and chairtime should have this one. */
    suspend fun tokenChanged(fresh: String) = mutex.withLock {
        token = fresh
        register()
    }

    private suspend fun register() {
        val api = api ?: return
        val shopId = shopId ?: return
        if (!tokens.isConfigured || !mayNotify()) return
        val token = token ?: tokens.token()?.also { token = it } ?: return
        val key = "$token|$shopId"
        if (key == registered) return
        // Not worth a word on screen: the next diary load tries again.
        runCatching { api.registerDevice(token, appVersion) }.onSuccess { registered = key }
    }

    /**
     * Signing out: stop the pushes while the session can still say so — at
     * every shop, which is chairtime's doing — and then throw the token away,
     * so that even if chairtime could not be reached, nothing more arrives on
     * this phone for whoever has just left it.
     */
    suspend fun signingOut(api: DevicesApi) = mutex.withLock {
        val known = token
        if (known != null && registered != null) runCatching { api.releaseDevice(known) }
        if (known != null || registered != null) runCatching { tokens.forget() }
        token = null
        registered = null
        this.api = null
        shopId = null
        _pendingLink.value = null
    }
}
