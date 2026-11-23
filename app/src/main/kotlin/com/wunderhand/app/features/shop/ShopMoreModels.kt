package com.wunderhand.app.features.shop

import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.core.CalendarLinkResponse
import com.wunderhand.core.ConsentDocument
import com.wunderhand.core.ConsentResponse
import com.wunderhand.core.ConsentWords
import com.wunderhand.core.DraftProblem
import com.wunderhand.core.Offer
import com.wunderhand.core.OfferDraft
import com.wunderhand.core.OfferWords
import com.wunderhand.core.OffersResponse
import com.wunderhand.core.QuietTimesResponse
import com.wunderhand.core.QuietWords
import com.wunderhand.network.ApiError
import com.wunderhand.network.ShopApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The owner's other screens: consent wording, quiet times and offers — and a
// person's own calendar link, which is anybody's.

// region Consent forms

data class ConsentState(val response: ConsentResponse? = null, val failure: String? = null)

/** Consent forms (chairtime `app/(pro)/shop/consent`): every kind, and whether it has any wording. */
class ConsentViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(ConsentState())
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    // Every time it is come back to: a version may just have been saved. What is on screen stays while it is asked.
    override fun load(): Job = viewModelScope.launch {
        attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.consent().let { r -> _state.update { ConsentState(response = r) } } }
    }
}

data class ConsentFormState(
    val document: ConsentDocument? = null,
    val failure: String? = null,
    /** The wording as it is being typed. */
    val text: String = "",
    val problem: SaveProblem? = null,
    /** What the last save did, in the web's sentence. */
    val note: String? = null,
    val isSaving: Boolean = false,
) {
    /** Nothing to save until the words differ from the live ones. */
    val isChanged: Boolean get() = document != null && text.trim() != document.body.trim()
}

/**
 * One kind's wording. A save never edits: it starts a new version and keeps
 * the old one for whoever signed it, so the button says which version it will
 * be. chairtime refuses wording too short to mean anything, and so does this.
 */
class ConsentFormViewModel(val kind: String, private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(ConsentFormState())
    val state: StateFlow<ConsentFormState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = ConsentFormState()
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(failure = e.message) } }) {
                val document = api.consent().documents.firstOrNull { it.kind == kind }
                _state.value = if (document == null) ConsentFormState(failure = "That kind of consent is not one this shop has.") else ConsentFormState(document = document, text = document.body)
            }
        }
    }

    fun type(text: String) = _state.update { it.copy(text = text, problem = null, note = null) }

    fun save(): Job? {
        val now = _state.value
        if (now.isSaving || now.document == null) return null
        val words = now.text.trim()
        if (words.length < ConsentWords.SHORTEST) {
            _state.update { it.copy(problem = SaveProblem(ConsentWords.TOO_SHORT, "body"), note = null) }
            return null
        }
        _state.update { it.copy(isSaving = true, problem = null, note = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e)) } }) {
                val saved = api.saveConsent(kind, words)
                _state.update { it.copy(document = saved, text = saved.body, note = saved.savedNote) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }
}

// endregion
// region Quiet times

data class QuietTimesState(
    val response: QuietTimesResponse? = null,
    val failure: String? = null,
    /** What just happened, in the web's sentence. */
    val notice: String? = null,
    val problem: String? = null,
    /** What is being changed: a suggestion's "2-m", or a rule's id. One thing at a time. */
    val busy: String? = null,
)

/**
 * Quiet times (chairtime `app/(pro)/shop/pricing`). Not a rule editor, as the
 * API is not: the shop is shown when it is reliably empty and offered a
 * discount to try on those hours. Every change answers with the whole screen.
 */
class QuietTimesViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(QuietTimesState())
    val state: StateFlow<QuietTimesState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = QuietTimesState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.quietTimes().let { r -> _state.update { it.copy(response = r) } } } }
    }

    private fun change(key: String, notice: String?, act: suspend () -> QuietTimesResponse): Job? {
        if (_state.value.busy != null) return null
        _state.update { it.copy(busy = key, problem = null, notice = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = e.message) } }) {
                val r = act()
                _state.update { it.copy(response = r, notice = notice) }
            }
            _state.update { it.copy(busy = null) }
        }
    }

    fun accept(s: QuietTimesResponse.Suggestion): Job? = change(key(s), QuietWords.ADDED) { api.acceptQuietTime(s.weekday, s.part, s.percent) }
    fun showSaving(rule: QuietTimesResponse.PriceRule, show: Boolean): Job? = change(rule.id, null) { api.showSaving(rule.id, show) }
    fun stop(rule: QuietTimesResponse.PriceRule): Job? = change(rule.id, QuietWords.STOPPED) { api.stopQuietTime(rule.id) }

    companion object {
        fun key(s: QuietTimesResponse.Suggestion) = "${s.weekday}-${s.part}"
    }
}

// endregion
// region Offers

data class OffersState(
    val response: OffersResponse? = null,
    val failure: String? = null,
    val notice: String? = null,
    val problem: SaveProblem? = null,
    /** The form under the list: closed until asked for, as a new offer is the rarer thing to do here. */
    val isStarting: Boolean = false,
    val draft: OfferDraft = OfferDraft(),
    val isSaving: Boolean = false,
    /** The offer being stopped. */
    val stopping: String? = null,
)

/** Offers (chairtime `app/(pro)/shop/promotions`): what is running, what each has done, and a form to start one. */
class OffersViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(OffersState())
    val state: StateFlow<OffersState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = OffersState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.offers().let { r -> _state.update { it.copy(response = r) } } } }
    }

    fun starting(showing: Boolean) = _state.update { it.copy(isStarting = showing, problem = null, draft = if (showing) it.draft else OfferDraft()) }
    fun edit(change: (OfferDraft) -> OfferDraft) = _state.update { it.copy(draft = change(it.draft), problem = null) }

    fun start(): Job? {
        val now = _state.value
        if (now.isSaving) return null
        val write = try { now.draft.write() } catch (problem: DraftProblem) {
            _state.update { it.copy(problem = SaveProblem(problem)) }
            return null
        }
        _state.update { it.copy(isSaving = true, problem = null, notice = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e)) } }) {
                val r = api.startOffer(write)
                _state.update { it.copy(response = r, notice = OfferWords.ADDED, isStarting = false, draft = OfferDraft()) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    fun stop(offer: Offer): Job? {
        if (_state.value.stopping != null) return null
        _state.update { it.copy(stopping = offer.id, notice = null, problem = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e.message)) } }) {
                val r = api.stopOffer(offer.id)
                _state.update { it.copy(response = r, notice = OfferWords.STOPPED) }
            }
            _state.update { it.copy(stopping = null) }
        }
    }
}

// endregion
// region A person's calendar link

data class CalendarState(
    val response: CalendarLinkResponse? = null,
    val failure: String? = null,
    val problem: String? = null,
    val isBusy: Boolean = false,
)

/**
 * A person's own diary as a link a calendar app follows (chairtime
 * `app/(pro)/shop/calendar`). The link is a key: it is shown once, when it is
 * made, kept only while this screen is, and never written anywhere.
 */
class CalendarViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = CalendarState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.calendarLink().let { r -> _state.update { it.copy(response = r) } } } }
    }

    private fun act(change: suspend () -> CalendarLinkResponse): Job? {
        if (_state.value.isBusy) return null
        _state.update { it.copy(isBusy = true, problem = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = e.message) } }) { change().let { r -> _state.update { it.copy(response = r) } } }
            _state.update { it.copy(isBusy = false) }
        }
    }

    /** Makes a link, switching off any before it. */
    fun make(): Job? = act { api.makeCalendarLink() }
    fun stop(): Job? = act { api.stopCalendarLink() }
}

// endregion
