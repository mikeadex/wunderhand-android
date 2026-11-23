package com.wunderhand.app.features.shop

import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.core.DraftProblem
import com.wunderhand.core.PageDraft
import com.wunderhand.core.PageResponse
import com.wunderhand.core.PageWords
import com.wunderhand.core.SideDraft
import com.wunderhand.core.SiteResponse
import com.wunderhand.core.SiteWords
import com.wunderhand.network.ApiError
import com.wunderhand.network.ShopApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Your page and Your website: two long screens whose every change answers with
// the whole screen again, so each is redrawn from the answer and never patched.

/**
 * What a change did, or why it was refused, and the part of the screen it was
 * about. These screens are long; a sentence at the top would be said to nobody.
 */
data class Said(val where: String, val text: String, val isProblem: Boolean = false, val field: String? = null) {
    constructor(where: String, error: ApiError) : this(where, error.message, isProblem = true, field = (error as? ApiError.Validation)?.field)
}

// region Your page

data class PageState(
    val response: PageResponse? = null,
    val failure: String? = null,
    /** The words as they are being typed, and the trades ticked. */
    val draft: PageDraft = PageDraft(),
    /** Why the words were not saved, and the line it was about. */
    val problem: SaveProblem? = null,
    val isSaving: Boolean = false,
    val justSaved: Boolean = false,
    /** What else is being changed: "cover", "logo", "publish", "outlet:…", "photo:…" or "add". One thing at a time. */
    val busy: String? = null,
    val said: Said? = null,
    /** The photograph about to be added: what it is, and who did it. */
    val caption: String = "",
    val staffId: String? = null,
) {
    /** Nothing to save until the words or the trades differ from the page's. */
    val isChanged: Boolean get() = response != null && draft != PageDraft(response)
}

/**
 * Your page (chairtime `app/(pro)/shop/profile`): the words and trades saved
 * together; a photograph, a switch or publishing done the moment it is asked.
 */
class PageViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(PageState())
    val state: StateFlow<PageState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = PageState()
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.page().let { r -> _state.value = PageState(response = r, draft = PageDraft(r)) } }
        }
    }

    fun edit(change: (PageDraft) -> PageDraft) = _state.update { s ->
        s.copy(draft = change(s.draft).within(s.response?.limits ?: PageResponse.Limits()), problem = null, justSaved = false)
    }

    fun toggle(trade: String) = edit { it.copy(trades = if (trade in it.trades) it.trades - trade else it.trades + trade) }

    fun save(): Job? {
        val now = _state.value
        val page = now.response ?: return null
        if (now.isSaving) return null
        _state.update { it.copy(isSaving = true, problem = null, justSaved = false) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e)) } }) {
                val r = api.savePage(now.draft.write(page.tradeChoices))
                _state.update { it.copy(response = r, draft = PageDraft(r), justSaved = true) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    /** Everything but the words. The answer is the page again; words half typed are left as they are. */
    private fun change(key: String, where: String, done: String?, after: (PageState) -> PageState = { it }, act: suspend () -> PageResponse): Job? {
        if (_state.value.busy != null) return null
        _state.update { it.copy(busy = key, said = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(said = Said(where, e)) } }) {
                val r = act()
                _state.update { s -> after(s.copy(response = r, draft = if (s.isChanged) s.draft else PageDraft(r), said = done?.let { Said(where, it) })) }
            }
            _state.update { it.copy(busy = null) }
        }
    }

    /** @param kind `cover` or `logo`. */
    fun upload(kind: String, image: ByteArray, type: String = "image/jpeg"): Job? = change(kind, kind, null) { api.uploadPageImage(kind, image, type) }
    fun removeImage(kind: String): Job? = change(kind, kind, null) { api.removePageImage(kind) }

    /** What was picked is not a picture this phone can read. */
    fun unreadable(where: String) = _state.update { it.copy(said = Said(where, PageWords.UNREADABLE, isProblem = true)) }

    fun setPrivate(outlet: PageResponse.Outlet, on: Boolean): Job? =
        change("outlet:${outlet.id}", "address", PageWords.ADDRESS_SAVED) { api.setAddressPrivate(outlet.id, on) }

    fun publish(on: Boolean): Job? = change("publish", "link", if (on) PageWords.PUBLISHED else PageWords.TAKEN_DOWN) { api.publishPage(on) }

    fun caption(text: String) = _state.update { it.copy(caption = text.take(it.response?.limits?.caption ?: 140)) }
    fun who(staffId: String?) = _state.update { it.copy(staffId = staffId) }

    fun addPhoto(jpeg: ByteArray): Job? {
        val now = _state.value
        return change("add", "work", PageWords.PHOTO_ADDED, after = { it.copy(caption = "", staffId = null) }) {
            api.addWorkPhoto(jpeg, now.caption.trim().ifEmpty { null }, now.staffId)
        }
    }

    fun removePhoto(photo: PageResponse.Photo): Job? = change("photo:${photo.id}", "work", PageWords.PHOTO_REMOVED) { api.removeWorkPhoto(photo.id) }
}

// endregion
// region Your website

data class SiteState(
    val response: SiteResponse? = null,
    val failure: String? = null,
    /** The sides being changed, by id. One not here is as the server has it. */
    val drafts: Map<String, SideDraft> = emptyMap(),
    val newSide: String = "",
    val newDomain: String = "",
    /** What is being changed: "design:…", "side:…", "addSide", "domain:…", "addDomain" or "refresh". One thing at a time. */
    val busy: String? = null,
    val said: Said? = null,
) {
    fun draft(side: SiteResponse.Side): SideDraft = drafts[side.id] ?: SideDraft(side)
    fun isChanged(side: SiteResponse.Side): Boolean = draft(side) != SideDraft(side)
}

/**
 * Your website (chairtime `app/(pro)/shop/site`): which design, the sides of
 * the business, and — only for a shop whose site has an address of its own —
 * the domain it answers on.
 */
class SiteViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(SiteState())
    val state: StateFlow<SiteState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = SiteState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.site().let { r -> _state.update { it.copy(response = r) } } } }
    }

    private fun change(key: String, where: String, done: String?, after: (SiteState) -> SiteState = { it }, act: suspend () -> SiteResponse): Job? {
        if (_state.value.busy != null) return null
        _state.update { it.copy(busy = key, said = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(said = Said(where, e)) } }) {
                val r = act()
                // A side that has gone takes what was being typed into it with it.
                _state.update { s -> after(s.copy(response = r, drafts = s.drafts.filterKeys { id -> r.sides.sides.any { it.id == id } }, said = done?.let { Said(where, it) })) }
            }
            _state.update { it.copy(busy = null) }
        }
    }

    /** Reads it all again: a domain that is ours but not pointing here yet only changes by looking. */
    fun refresh(): Job? = change("refresh", "address", null) { api.site() }

    fun choose(design: SiteResponse.Design): Job? = change("design:${design.key}", "design:${design.key}", SiteWords.DESIGN_SAVED) { api.chooseDesign(design.key) }

    // Sides

    fun edit(side: SiteResponse.Side, change: (SideDraft) -> SideDraft) = _state.update { s ->
        val sides = s.response?.sides ?: return@update s
        s.copy(drafts = s.drafts + (side.id to change(s.draft(side)).within(sides)), said = s.said?.takeIf { it.where != "side:${side.id}" })
    }

    fun toggle(side: SiteResponse.Side, categoryId: String) =
        edit(side) { it.copy(categoryIds = if (categoryId in it.categoryIds) it.categoryIds - categoryId else it.categoryIds + categoryId) }

    fun saveSide(side: SiteResponse.Side): Job? {
        val now = _state.value
        val sides = now.response?.sides ?: return null
        val where = "side:${side.id}"
        val write = try { now.draft(side).write(sides) } catch (problem: DraftProblem) {
            _state.update { it.copy(said = Said(where, problem.message, isProblem = true, field = problem.field)) }
            return null
        }
        return change(where, where, SiteWords.SIDE_SAVED, after = { it.copy(drafts = it.drafts - side.id) }) { api.saveSide(side.id, write) }
    }

    fun removeSide(side: SiteResponse.Side): Job? = change("side:${side.id}", "sides", SiteWords.SIDE_REMOVED) { api.removeSide(side.id) }

    /** @param direction `up` or `down`. */
    fun move(side: SiteResponse.Side, direction: String): Job? = change("side:${side.id}", "sides", null) { api.moveSide(side.id, direction) }

    fun typeSide(text: String) = _state.update { s -> s.copy(newSide = text.take(s.response?.sides?.nameMax ?: 40), said = s.said?.takeIf { it.where != "addSide" }) }

    fun addSide(): Job? {
        val name = _state.value.newSide.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(said = Said("addSide", SiteWords.NEEDS_NAME, isProblem = true, field = "name")) }
            return null
        }
        return change("addSide", "addSide", SiteWords.SIDE_ADDED, after = { it.copy(newSide = "") }) { api.addSide(name) }
    }

    // A domain of its own

    fun typeDomain(text: String) = _state.update { s -> s.copy(newDomain = text, said = s.said?.takeIf { it.where != "addDomain" }) }

    fun addDomain(): Job? {
        val domain = _state.value.newDomain.trim()
        if (domain.isEmpty()) return null
        return change("addDomain", "addDomain", SiteWords.DOMAIN_ADDED, after = { it.copy(newDomain = "") }) { api.addDomain(domain) }
    }

    /** Looks at the DNS again, or finishes a connection that is verified: the server knows which. */
    fun check(domain: SiteResponse.Domain): Job? = change("domain:${domain.id}", "domain:${domain.id}", null) { api.checkDomain(domain.id) }

    fun removeDomain(domain: SiteResponse.Domain): Job? = change("domain:${domain.id}", "address", SiteWords.DOMAIN_REMOVED) { api.removeDomain(domain.id) }
}

// endregion
