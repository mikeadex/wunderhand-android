package com.wunderhand.app.features.money

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.isTheApps
import com.wunderhand.core.CheckoutResponse
import com.wunderhand.core.Me
import com.wunderhand.core.MoneyInput
import com.wunderhand.core.MoneyResponse
import com.wunderhand.core.MoneyWords
import com.wunderhand.core.Owing
import com.wunderhand.core.ShopClock
import com.wunderhand.core.TillMethod
import com.wunderhand.core.TillRequest
import com.wunderhand.network.ApiError
import com.wunderhand.network.MoneyApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

// region The till

data class TillState(
    val view: CheckoutResponse? = null,
    val failure: String? = null,
    val problem: String? = null,
    val extra: String = "",
    val extraNote: String = "",
    val tip: String = "",
    val method: TillMethod = TillMethod.Cash,
    val isSettling: Boolean = false,
) {
    /**
     * What to ask for, moving as the extra and the tip are typed. Null while
     * either is not an amount — a wrong figure said confidently is worse than none.
     */
    val owing: Owing?
        get() {
            val v = view ?: return null
            return v.owing.with(MoneyInput.pence(extra) ?: return null, MoneyInput.pence(tip) ?: return null)
        }
}

/**
 * The till (chairtime `app/(pro)/checkout/[id]/page.tsx`).
 *
 * Not a payment screen. Wunderhand has no card terminal and does not want one:
 * deposits go straight to the shop's own Stripe when the booking is made, and
 * the rest is handed over in the room. This records what was settled and marks
 * the appointment done — which is the part a shop needs, because the
 * alternative is remembering.
 */
class TillViewModel(
    val bookingId: String,
    private val api: MoneyApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        saved.get<ArrayList<String>>("typed")?.takeIf { it.size == 4 }?.let { t -> TillState(extra = t[0], extraNote = t[1], tip = t[2], method = TillMethod.entries.firstOrNull { it.raw == t[3] } ?: TillMethod.Cash) } ?: TillState(),
    )
    val state: StateFlow<TillState> = _state.asStateFlow()

    init { viewModelScope.launch { look() } }

    fun load(): Job = viewModelScope.launch { look() }

    private suspend fun look() {
        try { api.checkout(bookingId).let { v -> _state.update { it.copy(view = v, failure = null) } } }
        catch (error: ApiError) { if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) } }
    }

    /** Half a bill typed should survive the phone ringing. */
    fun edit(change: (TillState) -> TillState) {
        _state.update(change)
        _state.value.let { saved["typed"] = arrayListOf(it.extra, it.extraNote, it.tip, it.method.raw) }
    }

    /** Pressed once, sent once: the client never asks twice for money, and chairtime refuses a second ring of the same bill. */
    fun settle(onSettled: suspend () -> Unit): Job? {
        val s = _state.value
        if (s.isSettling || s.view == null || s.view.isSettled) return null
        val extra = MoneyInput.pence(s.extra)
        val tip = MoneyInput.pence(s.tip)
        if (extra == null || tip == null) {
            _state.update { it.copy(problem = "Enter an amount like 12 or 12.50.") }
            return null
        }
        _state.update { it.copy(isSettling = true) }
        return viewModelScope.launch {
            try {
                api.settle(bookingId, TillRequest(extra, s.extraNote.trim().ifEmpty { null }, tip, s.method.raw))
                _state.update { it.copy(problem = null) }
                look()
                onSettled()
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else {
                    _state.update { it.copy(problem = error.message) }
                    // A refusal usually means something changed elsewhere: show it as it is now.
                    look()
                }
            }
            _state.update { it.copy(isSettling = false) }
        }
    }
}

// endregion
// region Money

data class MoneyState(val response: MoneyResponse? = null, val failure: String? = null, val isRefreshing: Boolean = false)

/**
 * The Money tab (chairtime `app/(pro)/money/page.tsx`). The owner sees the
 * shop; anybody else sees their own — and that is chairtime's to decide and to
 * send, not the app's to filter: nobody's phone is handed figures it then hides.
 */
class MoneyViewModel(me: Me, private val api: MoneyApi, private val handle: suspend (ApiError) -> Unit, private val now: () -> Instant = Instant::now) : ViewModel() {
    val clock = ShopClock(me.shop.timezone)
    private val _state = MutableStateFlow(MoneyState())
    val state: StateFlow<MoneyState> = _state.asStateFlow()

    init { load() }

    fun load(byHand: Boolean = false): Job = viewModelScope.launch {
        if (byHand) _state.update { it.copy(isRefreshing = true) }
        try { api.money().let { r -> _state.update { it.copy(response = r, failure = null) } } }
        catch (error: ApiError) { if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) } }
        _state.update { it.copy(isRefreshing = false) }
    }

    /** "SEPTEMBER SO FAR" — or the whole month's name once it is nearly over. */
    val monthName: String get() = clock.longDate(now()).split(" ").getOrElse(1) { "This month" }
    /** "2026-09": the row of month-by-month that is still being earned. */
    val thisMonthKey: String get() = clock.isoDate(now()).take(7)
    private val dayOfMonth: Int get() = clock.isoDate(now()).takeLast(2).toIntOrNull() ?: 1

    /** This month against the same span of last month, like for like. */
    fun comparison(r: MoneyResponse): MoneyWords.Comparison = MoneyWords.comparison(r.monthTook, r.lastMonthToDateTook, r.lastMonthTook, dayOfMonth, r.currency)
}

// endregion
