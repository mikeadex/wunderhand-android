package com.wunderhand.app.features.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.isTheApps
import com.wunderhand.core.Me
import com.wunderhand.core.ShopCloseResult
import com.wunderhand.core.ShopClock
import com.wunderhand.core.ShopClosing
import com.wunderhand.core.ShopClosure
import com.wunderhand.network.ApiError
import com.wunderhand.network.LeavingApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Leaving: deleting a login, and closing a shop. Both are chairtime's
 * (`lib/auth/delete-login.ts`, `lib/shop/close.ts`) and both are on the web as
 * well. A store asks that an app which signs people in lets them leave from
 * inside it, and a shop that trusts us with its client list should find the
 * way out without writing to anybody.
 *
 * The password is typed again for each. It is held in memory while it is being
 * typed, never in saved state, sent once, and let go of whatever the answer —
 * and when the app leaves the front, since what this guards against is an
 * unlocked phone on a counter.
 */

// region Your login

data class AccountState(val password: String = "", val problem: String? = null, val isDeleting: Boolean = false)

/** Your login (chairtime `app/(pro)/shop/account`): per person, never the shop. */
class AccountViewModel(val me: Me, private val api: LeavingApi, private val signedOut: suspend () -> Unit) : ViewModel() {
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    val whereTheySignIn: String =
        if (me.shops.size <= 1) "You sign in as ${me.user.email}, at ${me.shop.name}."
        else "You sign in as ${me.user.email}, at ${me.shops.size} shops: ${me.shops.joinToString(", ") { it.name }}."

    /** What goes, in a sentence; and under it, what stays. */
    val whatGoes: String = "This deletes the email and password you sign in with, on the web and in the app, and takes you off the team at ${if (me.shops.size <= 1) "the shop" else "every shop"}."
    val whatStays: List<String> = listOf(
        "Your past appointments and takings stay in the shop's books under your name, as they would for anybody who left.",
        "The shop itself is not deleted. ${if (me.staff.isOwner) "It stays with its other owners." else "It belongs to its owners."}",
        "Nothing about clients is deleted: their records are the shop's.",
        "It cannot be undone. Coming back means being invited again.",
    )

    fun type(password: String) = _state.update { it.copy(password = password) }
    fun forgetPassword() = _state.update { it.copy(password = "") }

    fun delete(): Job? {
        val password = _state.value.password
        if (password.isEmpty() || _state.value.isDeleting) return null
        _state.update { it.copy(isDeleting = true) }
        return viewModelScope.launch {
            try {
                api.deleteLogin(password)
                _state.update { AccountState() }
                // The token died with the login; nothing here can be loaded again.
                signedOut()
            } catch (error: ApiError) {
                _state.update { AccountState(problem = error.message) }
            }
        }
    }

    override fun onCleared() = forgetPassword()
}

// endregion
// region Close this shop

enum class ClosingAct { Asking, Agreeing, Refusing, Withdrawing }

data class CloseShopState(
    val closing: ShopClosing? = null,
    /** What the last act came back with: newer than [closing] where it says anything. */
    val result: ShopCloseResult? = null,
    val failure: String? = null,
    val problem: String? = null,
    val password: String = "",
    val typed: String = "",
    val busy: ClosingAct? = null,
) {
    val isClosed: Boolean get() = result?.closed == true || closing?.isClosed == true
    /** The request waiting on the other owners, from whichever answer is newest. */
    val request: ShopClosure? get() = result?.closure ?: closing?.closure
    val deleteAfter get() = result?.deleteAfter ?: closing?.deleteAfter
    val canAsk: Boolean get() = password.isNotEmpty() && typed.isNotEmpty() && busy == null
}

/**
 * Close this shop (chairtime `app/(pro)/shop/close`): an owner's, and the one
 * act here that ends a business's records. A shop with more than one owner is
 * not one owner's to close: asking opens a request the others answer.
 */
class CloseShopViewModel(val me: Me, private val api: LeavingApi, private val handle: suspend (ApiError) -> Unit) : ViewModel() {
    val clock = ShopClock(me.shop.timezone)
    private var visit: Int? = null
    private val _state = MutableStateFlow(CloseShopState())
    val state: StateFlow<CloseShopState> = _state.asStateFlow()

    fun enter(visit: Int) {
        if (this.visit == visit) return
        this.visit = visit
        _state.value = CloseShopState()
        load()
    }

    fun load(): Job = viewModelScope.launch { look() }

    private suspend fun look() {
        try { api.shopClosing().let { c -> _state.update { it.copy(closing = c, failure = null) } } }
        catch (error: ApiError) { if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) } }
    }

    fun typePassword(value: String) = _state.update { it.copy(password = value) }
    fun typeConfirm(value: String) = _state.update { it.copy(typed = value) }
    fun forgetPassword() = _state.update { it.copy(password = "") }

    /** The typed address is chairtime's to check, not the app's. */
    fun close(): Job? = if (!_state.value.canAsk) null else acting(ClosingAct.Asking) { s ->
        val result = api.closeShop(s.password, s.typed.trim())
        _state.update { it.copy(result = result, typed = "") }
    }

    fun agree(): Job? = if (_state.value.password.isEmpty()) null else acting(ClosingAct.Agreeing) { s -> api.agreeToClose(s.password).let { r -> _state.update { it.copy(result = r) } } }

    // The request is over; what the screen shows next is the server's.
    fun refuse(): Job? = acting(ClosingAct.Refusing) { api.refuseToClose(); _state.update { it.copy(result = null) }; look() }
    fun withdraw(): Job? = acting(ClosingAct.Withdrawing) { api.withdrawClose(); _state.update { it.copy(result = null) }; look() }

    /** One way of doing any of them: busy while it runs, the password let go of afterwards either way, and a refusal said in chairtime's own words. */
    private fun acting(what: ClosingAct, body: suspend (CloseShopState) -> Unit): Job? {
        val before = _state.value
        if (before.busy != null) return null
        _state.update { it.copy(busy = what) }
        return viewModelScope.launch {
            try {
                body(before)
                _state.update { it.copy(problem = null) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else {
                    _state.update { it.copy(problem = error.message) }
                    // A refusal often means somebody else moved first; show what is true now.
                    look()
                }
            }
            _state.update { it.copy(busy = null, password = "") }
        }
    }

    override fun onCleared() = forgetPassword()
}

// endregion
