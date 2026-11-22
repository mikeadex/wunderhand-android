package com.wunderhand.app.app

import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiError
import com.wunderhand.network.Pending
import com.wunderhand.network.SignIn
import com.wunderhand.network.TwoFactorCode
import com.wunderhand.network.TwoFactorRefusal
import com.wunderhand.network.WunderhandApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether anybody is signed in, and as whom — the state every screen hangs off. */
sealed interface Phase {
    /** Deciding: a stored token is being checked. */
    data object Launching : Phase
    data object SignedOut : Phase
    /** The password was right, and this login asks for a code as well.
     *  Nothing is signed in yet and no token is stored. */
    data class NeedsCode(val pending: Pending) : Phase
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
    /** Signing out, or changing shop: anything unlocked for the last one is locked again. */
    private val onLeaving: () -> Unit = {},
    /** On the way out, while the session still stands: this phone comes off chairtime's list of where to send news. */
    private val beforeSignOut: suspend (WunderhandApi) -> Unit = {},
) {
    private val _phase = MutableStateFlow<Phase>(Phase.Launching)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Who is signed in, once somebody is. */
    val me: Me?
        get() = when (val p = _phase.value) {
            is Phase.SignedIn -> p.me
            is Phase.ChoosingShop -> p.me
            else -> null
        }

    /** Something the app could not do that was nobody's fault and may pass:
     *  no signal, or chairtime mid-deploy. Shown as a bar over whatever is on
     *  screen, never as a screen of its own. */
    private val _trouble = MutableStateFlow<String?>(null)
    val trouble: StateFlow<String?> = _trouble.asStateFlow()

    /** Something for the sign-in screen to say the next time it opens: a
     *  two-step sign-in that was spent or ran out of time, which can only
     *  begin again at the password. */
    private val _signInNote = MutableStateFlow<String?>(null)
    val signInNote: StateFlow<String?> = _signInNote.asStateFlow()

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

    /**
     * The email and the password. Throws the refusal, for the sign-in screen
     * to say.
     *
     * A login with two-step sign-in on is not signed in by a right password:
     * it goes to [Phase.NeedsCode], which is the code screen.
     */
    suspend fun signIn(email: String, password: String) {
        settings.setChosenShopId(null)
        _signInNote.value = null
        when (val outcome = client.signIn(email.trim(), password)) {
            SignIn.SignedIn -> refresh()
            is SignIn.NeedsCode -> _phase.value = Phase.NeedsCode(outcome.pending)
        }
    }

    /**
     * The code from the authenticator app, or one of the backup codes.
     * Throws the refusal, for the code screen to say.
     *
     * A sign-in that is spent or has lapsed can never be finished, so that one
     * refusal is handled here rather than on the screen: it puts them back on
     * the password with the reason, instead of leaving somebody typing codes
     * into something that will not take another.
     */
    suspend fun signIn(code: String, from: Pending, kind: TwoFactorCode) {
        try {
            client.signIn(code, from, kind)
        } catch (refusal: ApiError.TwoFactor) {
            if (refusal.refusal != TwoFactorRefusal.StartAgain) throw refusal
            startSignInAgain(refusal.message)
            return
        }
        refresh()
    }

    /** Back to the email and password — by their own choice, or because the
     *  half-done sign-in is no longer good for anything. */
    fun startSignInAgain(note: String? = null) {
        _signInNote.value = note
        _phase.value = Phase.SignedOut
    }

    suspend fun choose(shopId: String) {
        onLeaving()
        // Another shop's diary is not this one's to show while it loads.
        cache.clear()
        settings.setChosenShopId(shopId)
        refresh()
    }

    /** Back to "Which shop?", from the shop somebody is in. */
    suspend fun switchShop() {
        onLeaving()
        cache.clear()
        settings.setChosenShopId(null)
        refresh()
    }

    suspend fun signOut() {
        onLeaving()
        cache.clear()
        lastMe = null
        // Never a reason not to sign out: it has its own patience, and its own way of failing quietly.
        runCatching { beforeSignOut(client) }
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
