package com.wunderhand.app.features.clients

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.core.ClientFilter
import com.wunderhand.core.ClientInput
import com.wunderhand.core.ClientProfileResponse
import com.wunderhand.core.ClientWords
import com.wunderhand.core.ClientsResponse
import com.wunderhand.core.ContactFill
import com.wunderhand.core.HealthResponse
import com.wunderhand.core.Me
import com.wunderhand.core.NotesLock
import com.wunderhand.core.PickedContact
import com.wunderhand.core.ShopClock
import com.wunderhand.core.UnlockWords
import com.wunderhand.network.ApiError
import com.wunderhand.network.ClientsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

private fun ApiError.isTheApps() = this is ApiError.Unauthorized || this is ApiError.NotMember || this is ApiError.UpgradeRequired

// region The list

data class ClientsState(
    val filter: ClientFilter = ClientFilter.All,
    val query: String = "",
    /** Where the tab is: the list, somebody's profile, their medical notes. On a tablet the list is always there too. */
    val openId: String? = null,
    val showingHealth: Boolean = false,
    val isAdding: Boolean = false,
    val response: ClientsResponse? = null,
    val failure: String? = null,
)

/** The client list: which filter, what is typed in the search, and what chairtime said (chairtime `app/(pro)/clients/page.tsx`). */
class ClientsViewModel(
    me: Me,
    val api: ClientsApi,
    val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** How long typing has to stop before it is a search. */
    private val pauseMillis: Long = 300,
) : ViewModel() {
    val clock = ShopClock(me.shop.timezone)
    val currency: String = me.shop.currency
    /** An owner: sees what clients have spent. chairtime sends nobody else the figures. */
    val seesMoney: Boolean = me.staff.isOwner

    private val _state = MutableStateFlow(
        ClientsState(
            filter = ClientFilter.entries.firstOrNull { it.raw == saved.get<String>("filter") } ?: ClientFilter.All,
            query = saved["query"] ?: "",
            openId = saved["open"],
            // Medical notes are never where the app comes back to by itself: they are asked for.
            showingHealth = false,
        ),
    )
    val state: StateFlow<ClientsState> = _state.asStateFlow()
    private var loading: Job? = null

    init { load() }

    fun load(afterTyping: Boolean = false): Job {
        loading?.cancel()
        return viewModelScope.launch {
            // Typing searches once the typing stops, not on every letter.
            if (afterTyping && _state.value.query.isNotEmpty()) delay(pauseMillis)
            val asked = _state.value
            try {
                val loaded = api.clients(asked.filter, asked.query)
                _state.update { it.copy(response = loaded, failure = null) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
            }
        }.also { loading = it }
    }

    fun search(query: String) {
        saved["query"] = query
        _state.update { it.copy(query = query) }
        load(afterTyping = true)
    }

    fun filter(filter: ClientFilter) {
        saved["filter"] = filter.raw
        _state.update { it.copy(filter = filter) }
        load()
    }

    fun open(id: String?) {
        saved["open"] = id
        _state.update { it.copy(openId = id, showingHealth = false) }
    }

    fun showHealth(showing: Boolean) = _state.update { it.copy(showingHealth = showing) }
    fun adding(showing: Boolean) = _state.update { it.copy(isAdding = showing) }

    /** Somebody new: the list has them now, and they open. */
    fun added(id: String) {
        adding(false)
        open(id)
        load()
    }

    /** Removed: back to the list, which no longer has them. */
    fun removed() {
        open(null)
        load()
    }
}

// endregion
// region One client

data class ProfileState(
    val response: ClientProfileResponse? = null,
    val failure: String? = null,
    /** A sentence after something happened here — "Booked in for Thu 17 Sept, 10:00." */
    val notice: String? = null,
)

/** One client's profile, and what can be started from it. */
class ClientProfileModel(val id: String, private val api: ClientsApi, private val clock: ShopClock, private val handle: suspend (ApiError) -> Unit) {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val response = api.client(id)
            _state.update { it.copy(response = response, failure = null) }
        } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
    }

    fun forgetNotice() = _state.update { it.copy(notice = null) }

    suspend fun bookedIn(at: Instant) {
        load()
        _state.update { it.copy(notice = ClientWords.bookedIn(at, clock)) }
    }
}

// endregion
// region The form

data class ClientFormState(
    val input: ClientInput = ClientInput(),
    val problem: String? = null,
    /** The field chairtime's refusal is about, so it can be shown on it. */
    val problemField: String? = null,
    val isSaving: Boolean = false,
    val isRemoving: Boolean = false,
    /** Said once a contact has filled the form: what it filled. */
    val fromContacts: String? = null,
)

/**
 * Add a client, or change one (chairtime `components/clients/ClientForm.tsx`).
 * What is typed is kept where the system can hand it back: a form half filled
 * in should survive a phone call.
 */
class ClientFormViewModel(
    val existing: ClientProfileResponse.Profile?,
    private val api: ClientsApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        ClientFormState(
            input = saved.get<ArrayList<String>>("input")?.let { ClientInput(it[0], it[1], it[2], it[3], it[4], it[5]) }
                ?: existing?.let(::ClientInput) ?: ClientInput(),
        ),
    )
    val state: StateFlow<ClientFormState> = _state.asStateFlow()

    fun edit(change: (ClientInput) -> ClientInput) {
        _state.update { it.copy(input = change(it.input)) }
        _state.value.input.let { saved["input"] = arrayListOf(it.name, it.phone, it.email, it.dateOfBirth, it.notes, it.standingFormula) }
    }

    /** A new client only: picking a contact over somebody already on file
     *  would quietly replace what the shop had. */
    fun fill(contact: PickedContact) {
        val filled = ContactFill.apply(contact, _state.value.input)
        edit { filled.input }
        _state.update { it.copy(fromContacts = ContactFill.said(filled.what)) }
    }

    fun save(onSaved: (String) -> Unit): Job? {
        if (_state.value.isSaving || _state.value.isRemoving) return null
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val input = _state.value.input
                val result = if (existing != null) api.updateClient(existing.id, input) else api.createClient(input)
                _state.update { it.copy(problem = null, problemField = null, isSaving = false) }
                onSaved(result.id)
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error)
                else _state.update { it.copy(problem = error.message, problemField = (error as? ApiError.Validation)?.field) }
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    fun remove(onRemoved: () -> Unit): Job? {
        val id = existing?.id ?: return null
        if (_state.value.isSaving || _state.value.isRemoving) return null
        _state.update { it.copy(isRemoving = true) }
        return viewModelScope.launch {
            try {
                api.removeClient(id)
                _state.update { it.copy(isRemoving = false) }
                onRemoved()
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = error.message) }
                _state.update { it.copy(isRemoving = false) }
            }
        }
    }
}

// endregion
// region Medical notes

/**
 * The phone's own check that it is you, in front of medical notes. One for the
 * process, because leaving the app has to lock every screen that could show
 * notes, not only the one that was open.
 */
interface NotesGate {
    sealed interface Outcome {
        data object Unlocked : Outcome
        /** Dismissed, or the app was sent away mid-prompt: nothing to say. */
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
        /** The phone has no screen lock, so there is nothing to lock with. */
        data object Unprotected : Outcome
    }

    /** What this phone will ask for. */
    val method: UnlockWords.Method
    fun isUnlocked(now: Instant = Instant.now()): Boolean
    /** Left the app, signed out, changed shop. */
    fun close()
    suspend fun unlock(reason: String): Outcome
}

/** The lock's own arithmetic, for a gate to stand on. */
abstract class TimedGate(private val now: () -> Instant = Instant::now) : NotesGate {
    @Volatile private var lock = NotesLock()
    override fun isUnlocked(now: Instant) = lock.isUnlocked(now)
    override fun close() { lock = lock.locked() }
    protected fun opened() { lock = lock.unlocked(now()) }
}

data class HealthState(
    val response: HealthResponse? = null,
    /** What is in the boxes. In memory only: never saved state, never a file. */
    val values: Map<String, String> = emptyMap(),
    val failure: String? = null,
    val notice: Pair<String, Boolean>? = null,
    val isSaving: Boolean = false,
    val confirmation: String = "",
    val isErasing: Boolean = false,
    /** Nothing is fetched — and so nothing is written down as read — until the
     *  phone knows it is somebody allowed to hold it. */
    val isLocked: Boolean = true,
    val isUnlocking: Boolean = false,
    val unlockProblem: String? = null,
    val isUnprotected: Boolean = false,
)

/**
 * Medical notes (chairtime `app/(pro)/clients/[id]/health/page.tsx`).
 *
 * A ViewModel so that turning the phone does not lose half a sentence about
 * somebody's allergy — but one with no saved state, and [forget] empties it
 * the moment the screen goes or the app leaves the front. Nothing read here
 * outlives being looked at.
 */
class HealthViewModel(
    val clientId: String,
    val clientName: String,
    private val api: ClientsApi,
    val clock: ShopClock,
    private val gate: NotesGate,
    private val handle: suspend (ApiError) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(HealthState(isLocked = !gate.isUnlocked()))
    val state: StateFlow<HealthState> = _state.asStateFlow()
    val method: UnlockWords.Method get() = gate.method

    /** Ask the phone first; fetch only once it has said yes. */
    fun open(): Job? {
        if (_state.value.isUnlocking || _state.value.response != null) return null
        _state.update { it.copy(isUnlocking = true) }
        return viewModelScope.launch {
            when (val outcome = gate.unlock(UnlockWords.reason(clientName))) {
                NotesGate.Outcome.Unlocked -> { _state.update { it.copy(unlockProblem = null, isLocked = false) }; load() }
                NotesGate.Outcome.Unprotected -> { _state.update { it.copy(unlockProblem = null, isUnprotected = true, isLocked = false) }; load() }
                NotesGate.Outcome.Cancelled -> _state.update { it.copy(isLocked = true) }
                is NotesGate.Outcome.Failed -> _state.update { it.copy(unlockProblem = outcome.message, isLocked = true) }
            }
            _state.update { it.copy(isUnlocking = false) }
        }
    }

    private suspend fun load() {
        try {
            val loaded = api.health(clientId)
            _state.update { it.copy(response = loaded, values = loaded.fields.associate { f -> f.key to loaded.value(f.key) }, failure = null) }
        } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
    }

    fun type(key: String, value: String) = _state.update { it.copy(values = it.values + (key to value)) }
    fun typeConfirmation(value: String) = _state.update { it.copy(confirmation = value) }

    fun save(): Job? {
        if (_state.value.isSaving || _state.value.isErasing) return null
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val result = api.saveHealth(clientId, _state.value.values)
                load()
                _state.update { it.copy(notice = ClientWords.healthSaved(result.retainUntil, clock) to false) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(notice = error.message to true) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    /** The typed word is chairtime's to check, not the app's. */
    fun erase(): Job? {
        if (_state.value.isSaving || _state.value.isErasing) return null
        _state.update { it.copy(isErasing = true) }
        return viewModelScope.launch {
            try {
                api.eraseHealth(clientId, _state.value.confirmation)
                _state.update { it.copy(confirmation = "") }
                load()
                _state.update { it.copy(notice = ClientWords.HEALTH_ERASED to false) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(notice = error.message to true) }
            }
            _state.update { it.copy(isErasing = false) }
        }
    }

    /** Gone from the screen, or from the app: what was read goes, and the next look is asked for again. */
    fun forget() = _state.update { HealthState(isLocked = true) }

    /** Gone from the app: the unlock goes too. */
    fun leftTheApp() {
        gate.close()
        forget()
    }
}

// endregion
