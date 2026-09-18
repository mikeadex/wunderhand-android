package com.wunderhand.app.app

import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiError
import com.wunderhand.network.WunderhandApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether anybody is signed in, and as whom — the state every screen hangs off. */
sealed interface Phase {
    /** Deciding: a stored token is being checked. */
    data object Launching : Phase
    data object SignedOut : Phase
    /** Signed in to more than one shop and none chosen yet. */
    data class ChoosingShop(val me: Me) : Phase
    data class SignedIn(val me: Me) : Phase
    /** Signed in, but chairtime could not be reached. The token is kept. */
    data class Unreachable(val message: String) : Phase
    data class UpgradeRequired(val message: String) : Phase

    /** A phase with the shop's own work on it, rather than a message about
     *  why there is none. Worth keeping through a passing failure. */
    val isAScreenWorthKeeping: Boolean get() = this is SignedIn || this is ChoosingShop
}

/**
 * The session. One for the whole app, outliving any one screen or rotation.
 *
 * @param connect makes the client for a server — a function, because a debug
 *   build can be pointed at another one.
 */
class AppModel(
    private val settings: Settings,
    val cache: OfflineCache,
    private val builtInServer: String,
    private val connect: (baseUrl: String) -> WunderhandApi,
) {
    private val _phase = MutableStateFlow<Phase>(Phase.Launching)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Something the app could not do that was nobody's fault and may pass:
     *  no signal, or chairtime mid-deploy. Shown as a bar over whatever is on
     *  screen, never as a screen of its own. */
    private val _trouble = MutableStateFlow<String?>(null)
    val trouble: StateFlow<String?> = _trouble.asStateFlow()

    /** A screen already saying it — the diary's bar over a day it kept, which
     *  says the same thing and more. Two bars about one lost connection read
     *  as two problems. */
    val aScreenIsSayingIt = MutableStateFlow(false)

    private val _server = MutableStateFlow(builtInServer)
    /** Where this build is talking to. */
    val server: StateFlow<String> = _server.asStateFlow()

    var client: WunderhandApi = connect(builtInServer)
        private set

    /** The last answer about who this is. Kept so a moment without signal
     *  does not throw somebody out of a diary they were reading — and kept on
     *  the phone as well, so a launch in a signal hole opens on their diary
     *  rather than on a screen about the network. */
    private var lastMe: Me? = null
    private val whoWeCanFallBackOn: Me? get() = lastMe ?: cache.savedMe()

    // region Lifecycle

    suspend fun start(allowServerOverride: Boolean) {
        if (allowServerOverride) {
            settings.serverOverride()?.let { useClientFor(it) }
        }
        if (!client.hasToken) {
            _phase.value = Phase.SignedOut
            return
        }
        refresh()
    }

    /** Ask chairtime who this is and which shop they are acting for. */
    suspend fun refresh() {
        val chosen = settings.chosenShopId()
        client.tenantId = chosen
        try {
            val me = client.me()
            lastMe = me
            cache.save(me)
            _trouble.value = null
            _phase.value = phaseFor(me, chosen)
        } catch (error: ApiError) {
            handle(error)
        }
    }

    private fun phaseFor(me: Me, chosen: String?): Phase =
        if (me.worksAtSeveral && chosen == null) Phase.ChoosingShop(me) else Phase.SignedIn(me)

    /** Throws the refusal, for the sign-in screen to say. */
    suspend fun signIn(email: String, password: String) {
        settings.setChosenShopId(null)
        client.signIn(email.trim(), password)
        refresh()
    }

    suspend fun choose(shopId: String) {
        // Another shop's diary is not this one's to show while it loads.
        cache.clear()
        settings.setChosenShopId(shopId)
        refresh()
    }

    /** Back to "Which shop?", from the shop somebody is in. */
    suspend fun switchShop() {
        cache.clear()
        settings.setChosenShopId(null)
        refresh()
    }

    suspend fun signOut() {
        cache.clear()
        lastMe = null
        client.signOut()
        settings.setChosenShopId(null)
        _trouble.value = null
        _phase.value = Phase.SignedOut
    }

    /** What any screen does with a refusal it cannot handle itself. */
    suspend fun handle(error: ApiError) {
        val chosen = settings.chosenShopId()
        when {
            error is ApiError.Unauthorized -> {
                settings.setChosenShopId(null)
                _phase.value = Phase.SignedOut
            }
            error is ApiError.NotMember && chosen != null -> {
                // The remembered shop no longer has them. Fall back to their first.
                settings.setChosenShopId(null)
                refresh()
            }
            error is ApiError.NotMember -> _phase.value = Phase.SignedOut
            error is ApiError.UpgradeRequired -> _phase.value = Phase.UpgradeRequired(error.message)
            error.isPassing && whoWeCanFallBackOn != null -> {
                /* The client has already tried again a few times. Rather than
                 * take the app away for something that may be gone in a second,
                 * keep the screen — or open the one this phone last saw — and
                 * say so in a bar over it. */
                val me = whoWeCanFallBackOn
                if (me != null && !_phase.value.isAScreenWorthKeeping) {
                    lastMe = me
                    _phase.value = phaseFor(me, chosen)
                }
                _trouble.value = error.message
            }
            else -> {
                lastMe = null
                _phase.value = Phase.Unreachable(error.message)
            }
        }
    }

    /** Ask again, from the bar over the screen. */
    suspend fun tryAgain() = refresh()

    // endregion
    // region Development

    /** Point a debug build at a different dev server. Signs out, because a
     *  token from one server means nothing to another. */
    suspend fun useServer(raw: String) {
        client.signOut()
        val url = raw.trim().ifEmpty { builtInServer }
        settings.setServerOverride(url.takeIf { it != builtInServer })
        useClientFor(url)
        settings.setChosenShopId(null)
        cache.clear()
        lastMe = null
        _phase.value = Phase.SignedOut
    }

    private fun useClientFor(url: String) {
        runCatching { connect(url) }.onSuccess {
            client = it
            _server.value = url
        }
    }

    // endregion
}
