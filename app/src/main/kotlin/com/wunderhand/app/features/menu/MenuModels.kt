package com.wunderhand.app.features.menu

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.app.app.isTheApps
import com.wunderhand.core.DraftProblem
import com.wunderhand.core.DurationMode
import com.wunderhand.core.ExtraLinksWrite
import com.wunderhand.core.ExtraWrite
import com.wunderhand.core.ExtrasResponse
import com.wunderhand.core.LocationMode
import com.wunderhand.core.Me
import com.wunderhand.core.MenuOptions
import com.wunderhand.core.MenuResponse
import com.wunderhand.core.MenuServiceResponse
import com.wunderhand.core.MoneyInput
import com.wunderhand.core.PerformersWrite
import com.wunderhand.core.PricingMode
import com.wunderhand.core.ServiceDraft
import com.wunderhand.core.StepDraft
import com.wunderhand.network.ApiError
import com.wunderhand.network.MenuApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// region The menu

data class MenuState(
    val response: MenuResponse? = null,
    val failure: String? = null,
    /** The category the list is narrowed to, or null for all of it. */
    val category: String? = null,
    val openId: String? = null,
    val isAdding: Boolean = false,
    val isRefreshing: Boolean = false,
)

/** The Menu tab (chairtime `app/(pro)/menu/page.tsx`). */
class MenuViewModel(
    me: Me,
    val api: MenuApi,
    val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val currency: String = me.shop.currency
    /** The menu is an owner's to change (chairtime `lib/auth/owner.ts`). Anybody else reads it. */
    val isOwner: Boolean = me.staff.isOwner
    val myStaffId: String = me.staff.id

    private val _state = MutableStateFlow(MenuState(category = saved["category"], openId = saved["open"], isAdding = saved["adding"] ?: false))
    val state: StateFlow<MenuState> = _state.asStateFlow()

    init { load() }

    fun load(byHand: Boolean = false): Job = viewModelScope.launch {
        if (byHand) _state.update { it.copy(isRefreshing = true) }
        try {
            val loaded = api.menu()
            // A category that has gone is not one to stay narrowed to.
            _state.update { it.copy(response = loaded, failure = null, category = it.category?.takeIf { c -> loaded.categories.any { cat -> cat.id == c } }) }
        } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
        _state.update { it.copy(isRefreshing = false) }
    }

    fun category(id: String?) {
        saved["category"] = id
        _state.update { it.copy(category = id) }
    }

    fun open(id: String?) {
        saved["open"] = id
        _state.update { it.copy(openId = id) }
    }

    // That a form is open is kept where the system can hand it back, with what was typed into it: killed in the
    // background with a form half filled in, the app comes back to the form, not to the screen behind it.
    fun adding(showing: Boolean) {
        saved["adding"] = showing
        _state.update { it.copy(isAdding = showing) }
    }

    /** Straight to its page: it has an hour and nobody to do it, and both are one tap from there. */
    fun created(id: String) {
        adding(false)
        open(id)
        load()
    }

    /** It left the menu: back to the list, which no longer has it. */
    fun archived() {
        open(null)
        load()
    }
}

// endregion
// region One service

data class ServiceState(val response: MenuServiceResponse? = null, val failure: String? = null)

/** One service's page, and what its editors hand back. */
class ServiceModel(val id: String, private val api: MenuApi, private val handle: suspend (ApiError) -> Unit) {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val loaded = api.menuService(id)
            _state.update { ServiceState(loaded, null) }
        } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
    }

    /** An editor saved, and handed back the service as it now is. */
    fun took(response: MenuServiceResponse) = _state.update { ServiceState(response, null) }
}

// endregion
// region The service form

data class ServiceFormState(
    val draft: ServiceDraft = ServiceDraft(),
    val options: MenuOptions? = null,
    val problem: SaveProblem? = null,
    val isSaving: Boolean = false,
    val isArchiving: Boolean = false,
)

/** A new service, or one being changed (chairtime `components/menu/ServiceForm.tsx`). */
class ServiceFormViewModel(
    val existing: MenuServiceResponse?,
    private val api: MenuApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** Where a new service happens to begin with — "either" at a shop that travels, as the web's form has it. */
    defaultLocationMode: LocationMode = LocationMode.AtVenue,
) : ViewModel() {
    private val _state = MutableStateFlow(ServiceFormState(draft = saved.get<ArrayList<String?>>("draft")?.let(::unpack) ?: existing?.let { ServiceDraft(it.service) } ?: ServiceDraft(locationMode = defaultLocationMode)))
    val state: StateFlow<ServiceFormState> = _state.asStateFlow()

    init {
        // The form works without them — no category to pick, nothing to need first — so a failure here is not worth a word.
        viewModelScope.launch { runCatching { api.menuOptions() }.onSuccess { o -> _state.update { it.copy(options = o) } } }
    }

    fun edit(change: (ServiceDraft) -> ServiceDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        saved["draft"] = pack(_state.value.draft)
    }

    fun save(onSaved: (MenuServiceResponse) -> Unit): Job? {
        if (_state.value.isSaving || _state.value.isArchiving) return null
        val write = try { _state.value.draft.write() } catch (problem: DraftProblem) {
            _state.update { it.copy(problem = SaveProblem(problem)) }
            return null
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val response = if (existing != null) api.updateService(existing.service.id, write) else api.createService(write)
                _state.update { it.copy(problem = null, isSaving = false) }
                onSaved(response)
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = SaveProblem(error)) }
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    fun archive(onArchived: () -> Unit): Job? {
        val id = existing?.service?.id ?: return null
        if (_state.value.isSaving || _state.value.isArchiving) return null
        _state.update { it.copy(isArchiving = true) }
        return viewModelScope.launch {
            try {
                api.archiveService(id)
                _state.update { it.copy(isArchiving = false) }
                onArchived()
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = SaveProblem(error)) }
                _state.update { it.copy(isArchiving = false) }
            }
        }
    }

    private companion object {
        /** A form half filled in should survive a phone call: the draft, as something the system can keep. */
        fun pack(d: ServiceDraft): ArrayList<String?> = arrayListOf(
            d.name, d.description, d.categoryId, d.newCategory, d.pricingMode.raw, d.price, d.hourlyRate, d.deposit, d.durationMode.raw,
            d.minMinutes, d.maxMinutes, d.locationMode.raw, d.bookableOnline.toString(), d.requiresConsent.toString(),
            d.prerequisiteServiceId, d.prerequisiteLeadHours, d.minAgeYears, d.requiresResourceTypeId,
        )

        fun unpack(v: ArrayList<String?>): ServiceDraft? = if (v.size != 18) null else ServiceDraft(
            name = v[0].orEmpty(), description = v[1].orEmpty(), categoryId = v[2], newCategory = v[3].orEmpty(), pricingMode = PricingMode.of(v[4].orEmpty()),
            price = v[5].orEmpty(), hourlyRate = v[6].orEmpty(), deposit = v[7].orEmpty(), durationMode = DurationMode.of(v[8].orEmpty()),
            minMinutes = v[9].orEmpty(), maxMinutes = v[10].orEmpty(), locationMode = LocationMode.of(v[11].orEmpty()), bookableOnline = v[12] == "true",
            requiresConsent = v[13] == "true", prerequisiteServiceId = v[14], prerequisiteLeadHours = v[15].orEmpty(), minAgeYears = v[16].orEmpty(), requiresResourceTypeId = v[17],
        )
    }
}

// endregion
// region How the time is used

data class StepsState(val rows: List<StepDraft>, val problem: SaveProblem? = null, val isSaving: Boolean = false)

/**
 * How the time is used (chairtime `app/(pro)/menu/[id]/segments`). The web adds
 * one step a save and cannot reorder; here they are added, moved and removed
 * freely, and saved whole.
 */
class StepsViewModel(
    val service: MenuServiceResponse,
    private val api: MenuApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        StepsState(
            saved.get<ArrayList<String>>("rows")?.chunked(3)?.map { StepDraft(it[0], it[1], it[2] == "true") }?.takeIf { it.isNotEmpty() }
                ?: service.segments.map(::StepDraft).ifEmpty { listOf(StepDraft()) },
        ),
    )
    val state: StateFlow<StepsState> = _state.asStateFlow()

    private fun rows(change: (List<StepDraft>) -> List<StepDraft>) {
        _state.update { it.copy(rows = change(it.rows)) }
        saved["rows"] = ArrayList(_state.value.rows.flatMap { listOf(it.label, it.minutes, it.staffBusy.toString()) })
    }

    fun edit(index: Int, change: (StepDraft) -> StepDraft) = rows { all -> all.mapIndexed { i, row -> if (i == index) change(row) else row } }
    fun add() = rows { if (it.size >= MOST) it else it + StepDraft() }
    /** The last one stays: a service is at least one block of time. */
    fun remove(index: Int) = rows { if (it.size <= 1) it else it.filterIndexed { i, _ -> i != index } }

    fun move(index: Int, by: Int) = rows { all ->
        val target = index + by
        if (index !in all.indices || target !in all.indices) all else all.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun save(onSaved: (MenuServiceResponse) -> Unit): Job? {
        if (_state.value.isSaving) return null
        val write = try { StepDraft.write(_state.value.rows) } catch (problem: DraftProblem) {
            _state.update { it.copy(problem = SaveProblem(problem)) }
            return null
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val response = api.saveSteps(service.service.id, write)
                _state.update { it.copy(problem = null, isSaving = false) }
                onSaved(response)
            } catch (error: ApiError) {
                // The server counts the steps it was sent, not the rows on screen, so its field is not ours to point at.
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = SaveProblem(error.message)) }
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    companion object { const val MOST = 20 }
}

// endregion
// region Who does it

data class PerformerRow(val id: String, val name: String, val roleTitle: String?, val isOn: Boolean, val price: String)

data class PerformersState(val rows: List<PerformerRow>? = null, val failure: String? = null, val problem: SaveProblem? = null, val isSaving: Boolean = false)

/**
 * Who does this? (chairtime `app/(pro)/menu/[id]/performers`).
 *
 * Who does a service is an owner's to say. Anybody else opens this for one
 * thing — their own price — so they are shown their own row alone, with no
 * switch on it: taking themselves off is not theirs to do.
 */
class PerformersViewModel(
    val service: MenuServiceResponse,
    val isOwner: Boolean,
    private val myStaffId: String,
    private val api: MenuApi,
    private val handle: suspend (ApiError) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(PerformersState())
    val state: StateFlow<PerformersState> = _state.asStateFlow()

    /** What a blank price means, as the box's placeholder. */
    val standardPrice: String? = service.service.pricePence?.let(MoneyInput::pounds)

    init { load() }

    fun load(): Job = viewModelScope.launch {
        if (!isOwner) {
            // Their own row needs nothing the service has not already handed over, and what the
            // form chooses between is an owner's to read (chairtime `menu/options`), so this asks for nothing.
            val mine = service.performers.filter { it.id == myStaffId }.map { PerformerRow(it.id, it.name, null, true, it.overridePence?.let(MoneyInput::pounds).orEmpty()) }
            _state.update { it.copy(rows = mine, failure = null) }
            return@launch
        }
        try {
            val assigned = service.performers.associateBy { it.id }
            val rows = api.menuOptions().staff.map { p -> PerformerRow(p.id, p.name, p.roleTitle, p.id in assigned, assigned[p.id]?.overridePence?.let(MoneyInput::pounds).orEmpty()) }
            _state.update { it.copy(rows = rows, failure = null) }
        } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
    }

    fun edit(id: String, change: (PerformerRow) -> PerformerRow) = _state.update { s -> s.copy(rows = s.rows?.map { if (it.id == id) change(it) else it }) }

    fun save(onSaved: (MenuServiceResponse) -> Unit): Job? {
        if (_state.value.isSaving) return null
        val entries = _state.value.rows.orEmpty().filter { it.isOn }.map { person ->
            val typed = person.price.trim()
            // The web reads junk as "the standard price". Saying so is kinder.
            val pence = if (typed.isEmpty()) null else MoneyInput.pence(typed) ?: run {
                _state.update { it.copy(problem = SaveProblem("Enter an amount like 28.50", "price_${person.id}")) }
                return null
            }
            PerformersWrite.Entry(person.id, pence)
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val response = api.savePerformers(service.service.id, PerformersWrite(entries))
                _state.update { it.copy(problem = null, isSaving = false) }
                onSaved(response)
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = SaveProblem(error)) }
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}

// endregion
// region Extras

data class ExtraFormState(val existing: ExtrasResponse.Extra?, val name: String, val price: String, val minutes: String, val problem: SaveProblem? = null) {
    constructor(existing: ExtrasResponse.Extra?) : this(existing, existing?.name.orEmpty(), existing?.pricePence?.let(MoneyInput::pounds).orEmpty(), existing?.minutes?.toString() ?: "0")
}

data class ExtrasState(
    val extras: List<ExtrasResponse.Extra>? = null,
    val offered: Set<String> = emptySet(),
    val failure: String? = null,
    val problem: String? = null,
    val justSaved: Boolean = false,
    val isSaving: Boolean = false,
    /** The one being made or changed, in place of the list. */
    val form: ExtraFormState? = null,
    /** Something was saved here: the service's page should look again. */
    val changedAnything: Boolean = false,
)

/**
 * Extras (chairtime `app/(pro)/menu/[id]/addons`): managed from the service
 * they belong to, because that is where a pro thinks of them — but the shop's
 * underneath, so the same treatment can be offered on three colours without
 * being made three times.
 */
class ExtrasViewModel(val service: MenuServiceResponse, private val api: MenuApi, private val handle: suspend (ApiError) -> Unit) : ViewModel() {
    private val _state = MutableStateFlow(ExtrasState())
    val state: StateFlow<ExtrasState> = _state.asStateFlow()
    private val serviceId get() = service.service.id

    init { load() }

    private fun took(r: ExtrasResponse) = _state.update { it.copy(extras = r.extras, offered = r.extras.filter { e -> e.offered }.map { e -> e.id }.toSet(), failure = null) }

    fun load(): Job = viewModelScope.launch {
        try { took(api.extras(serviceId)) } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
    }

    fun offer(id: String, on: Boolean) = _state.update { it.copy(offered = if (on) it.offered + id else it.offered - id, justSaved = false) }

    fun saveLinks(): Job? {
        if (_state.value.isSaving) return null
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                took(api.saveExtraLinks(serviceId, ExtraLinksWrite(_state.value.offered.sorted())))
                _state.update { it.copy(problem = null, justSaved = true, changedAnything = true) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = error.message) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    // One extra: a new one, or one being changed — and, for one that exists, retired.

    fun editing(extra: ExtrasResponse.Extra?) = _state.update { it.copy(form = ExtraFormState(extra)) }
    fun closeForm() = _state.update { it.copy(form = null) }
    fun type(change: (ExtraFormState) -> ExtraFormState) = _state.update { it.copy(form = it.form?.let(change)) }

    private fun refuse(text: String, field: String): Job? {
        type { it.copy(problem = SaveProblem(text, field)) }
        return null
    }

    fun saveExtra(): Job? {
        val form = _state.value.form ?: return null
        if (_state.value.isSaving) return null
        val name = form.name.trim()
        if (name.isEmpty()) return refuse("A name is needed", "name")
        val pence = MoneyInput.pence(form.price) ?: return refuse("Enter an amount like 28.50", "pricePence")
        val minutes = form.minutes.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull()?.takeIf { m -> m >= 0 } } ?: return refuse("Extra time must be a whole number", "minutes")
        // A new one is offered at once on the service it was made from; changing one leaves where it is offered alone.
        val write = ExtraWrite(name, pence, minutes, serviceId = if (form.existing == null) serviceId else null)
        return finishing { if (form.existing != null) api.updateExtra(form.existing.id, write) else api.createExtra(write) }
    }

    fun retire(): Job? {
        val extra = _state.value.form?.existing ?: return null
        if (_state.value.isSaving) return null
        return finishing { api.retireExtra(extra.id) }
    }

    private fun finishing(act: suspend () -> Unit): Job {
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                act()
                _state.update { it.copy(form = null, changedAnything = true) }
                load().join()
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else type { it.copy(problem = SaveProblem(error)) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }
}

// endregion
