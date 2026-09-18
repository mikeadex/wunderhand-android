package com.wunderhand.app.features.waitlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.isTheApps
import com.wunderhand.core.ClientFilter
import com.wunderhand.core.ClientRow
import com.wunderhand.core.Flexibility
import com.wunderhand.core.GapResponse
import com.wunderhand.core.OfferSent
import com.wunderhand.core.WaitWords
import com.wunderhand.core.WaitingRow
import com.wunderhand.core.WaitlistJoinRequest
import com.wunderhand.core.WaitlistOptions
import com.wunderhand.core.WaitlistResponse
import com.wunderhand.network.ApiError
import com.wunderhand.network.ClientsApi
import com.wunderhand.network.WaitlistApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

// region Everybody waiting

/** Somebody to add straight away — from a client's profile. */
data class WaitingFor(val clientId: String, val clientName: String)

data class WaitlistState(
    val response: WaitlistResponse? = null,
    val failure: String? = null,
    /** "Wren is on the list." */
    val notice: String? = null,
    val isAdding: Boolean = false,
    /** Counts each time the form is opened: the last person's ticks are not the next one's. */
    val addVisit: Int = 0,
    val removingId: String? = null,
    val isRefreshing: Boolean = false,
)

/**
 * Everybody waiting (chairtime `app/(pro)/waitlist/page.tsx`). Ordered by how
 * long they have waited, not by when a gap might suit them: this screen
 * answers "who am I letting down", not "who takes this hour".
 */
class WaitlistViewModel(private val api: WaitlistApi, private val handle: suspend (ApiError) -> Unit, addStraightAway: Boolean = false, private val now: () -> Instant = Instant::now) : ViewModel() {
    private val _state = MutableStateFlow(WaitlistState(isAdding = addStraightAway))
    val state: StateFlow<WaitlistState> = _state.asStateFlow()

    init { load() }

    fun load(byHand: Boolean = false): Job = viewModelScope.launch {
        if (byHand) _state.update { it.copy(isRefreshing = true) }
        look()
        _state.update { it.copy(isRefreshing = false) }
    }

    private suspend fun look() {
        try { api.waitlist().let { r -> _state.update { it.copy(response = r, failure = null) } } }
        catch (error: ApiError) { if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) } }
    }

    /** "4 people · 1 with an offer out · longest waiting 3 wks" */
    fun eyebrow(state: WaitlistState): String {
        val r = state.response ?: return " "
        val n = r.waiting.size
        return listOfNotNull(
            "$n ${if (n == 1) "person" else "people"}", "${r.offeredCount} with an offer out".takeIf { r.offeredCount > 0 },
            r.longestWaitingSince?.takeIf { n > 0 }?.let { "longest waiting ${WaitWords.waited(it, now())}" },
        ).joinToString(" · ")
    }

    fun waited(row: WaitingRow): String = WaitWords.waited(row.waitingSince, now())

    fun adding(showing: Boolean) = _state.update { it.copy(isAdding = showing, addVisit = if (showing) it.addVisit + 1 else it.addVisit) }

    fun added(name: String) {
        _state.update { it.copy(isAdding = false, notice = "$name is on the list.") }
        load()
    }

    fun remove(row: WaitingRow): Job? {
        if (_state.value.removingId != null) return null
        _state.update { it.copy(removingId = row.id) }
        return viewModelScope.launch {
            try {
                api.leaveWaitlist(row.id)
                _state.update { it.copy(notice = "${row.clientName} is off the list.") }
                look()
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message, notice = null) }
            }
            _state.update { it.copy(removingId = null) }
        }
    }
}

// endregion
// region Who is waiting?

data class JoinState(
    val options: WaitlistOptions? = null,
    val client: WaitingFor? = null,
    val query: String = "",
    val matches: List<ClientRow> = emptyList(),
    val serviceId: String = "",
    /** Null is anyone available. */
    val staffId: String? = null,
    /** Calendar dates, "2026-10-01": a day is a day wherever the phone is. */
    val earliest: String? = null,
    val latest: String? = null,
    val days: Set<Int> = emptySet(),
    val parts: Set<String> = emptySet(),
    val problem: String? = null,
    val isSaving: Boolean = false,
) {
    val canSave: Boolean get() = client != null && serviceId.isNotEmpty() && !isSaving
}

/**
 * Put somebody on the list (chairtime `app/(pro)/waitlist/new/page.tsx`). The
 * two questions that matter are what they want and when they can come, and the
 * second is the one shops skip. Everything is optional except who and what — a
 * blank answer means "any".
 */
class WaitlistJoinViewModel(
    private val api: WaitlistApi,
    private val clients: ClientsApi,
    private val handle: suspend (ApiError) -> Unit,
    preset: WaitingFor? = null,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val pauseMillis: Long = 250,
) : ViewModel() {
    private val _state = MutableStateFlow(
        saved.get<ArrayList<String>>("typed")?.takeIf { it.size == 9 }?.let { t ->
            JoinState(
                client = t[0].takeIf { it.isNotEmpty() }?.let { WaitingFor(it, t[1]) }, query = t[2], serviceId = t[3], staffId = t[4].ifEmpty { null },
                earliest = t[5].ifEmpty { null }, latest = t[6].ifEmpty { null }, days = t[7].split(",").mapNotNull { it.toIntOrNull() }.toSet(), parts = t[8].split(",").filter { it.isNotEmpty() }.toSet(),
            )
        } ?: JoinState(client = preset),
    )
    val state: StateFlow<JoinState> = _state.asStateFlow()
    private var searching: Job? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = api.waitlistOptions()
                _state.update { it.copy(options = loaded, serviceId = it.serviceId.ifEmpty { loaded.services.firstOrNull()?.id.orEmpty() }) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = error.message) }
            }
        }
    }

    /** A form half filled in should survive a phone call. */
    private fun edit(change: (JoinState) -> JoinState) {
        _state.update(change)
        _state.value.let { s ->
            saved["typed"] = arrayListOf(s.client?.clientId.orEmpty(), s.client?.clientName.orEmpty(), s.query, s.serviceId, s.staffId.orEmpty(), s.earliest.orEmpty(), s.latest.orEmpty(), s.days.sorted().joinToString(","), s.parts.joinToString(","))
        }
    }

    /** Typing searches once the typing stops, not on every letter. */
    fun search(query: String) {
        edit { it.copy(query = query) }
        searching?.cancel()
        if (query.isBlank()) { _state.update { it.copy(matches = emptyList()) }; return }
        searching = viewModelScope.launch {
            delay(pauseMillis)
            // Nothing worth a sentence if it fails: the list simply stays as it was, and typing again asks again.
            runCatching { clients.clients(ClientFilter.All, query.trim()) }.onSuccess { r -> _state.update { it.copy(matches = r.clients.take(6)) } }
        }
    }

    fun choose(row: ClientRow) { searching?.cancel(); edit { it.copy(client = WaitingFor(row.id, row.name), matches = emptyList()) } }
    fun changeClient() = edit { it.copy(client = null, query = "", matches = emptyList()) }
    fun service(id: String) = edit { it.copy(serviceId = id) }
    fun staff(id: String?) = edit { it.copy(staffId = id) }
    fun earliest(date: String?) = edit { it.copy(earliest = date) }
    fun latest(date: String?) = edit { it.copy(latest = date) }
    fun day(value: Int) = edit { it.copy(days = if (value in it.days) it.days - value else it.days + value) }
    fun part(value: String) = edit { it.copy(parts = if (value in it.parts) it.parts - value else it.parts + value) }

    fun save(onJoined: (String) -> Unit): Job? {
        val s = _state.value
        val who = s.client ?: return null
        if (!s.canSave) return null
        if (s.earliest != null && s.latest != null && s.latest < s.earliest) {
            _state.update { it.copy(problem = "“No use after” is before “not before”. Check the two dates.") }
            return null
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                // Mornings, afternoons, evenings: sent in the day's order, whatever order they were ticked in.
                api.joinWaitlist(WaitlistJoinRequest(who.clientId, s.serviceId, s.staffId, s.earliest, s.latest, s.days.sorted(), Flexibility.parts.map { it.value }.filter { it in s.parts }))
                _state.update { it.copy(problem = null, isSaving = false) }
                onJoined(who.clientName)
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = error.message) }
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}

// endregion
// region Fill the gap

/** A window in somebody's diary, to fill from the waitlist. */
data class GapWindow(val staffId: String, val from: Instant, val to: Instant) {
    val id: String get() = "$staffId-${from.toEpochMilli()}-${to.toEpochMilli()}"
    val minutes: Int get() = Duration.between(from, to).toMinutes().toInt()

    /** As something the system can keep and hand back. */
    fun packed(): ArrayList<String> = arrayListOf(staffId, from.toEpochMilli().toString(), to.toEpochMilli().toString())

    companion object {
        fun unpack(v: ArrayList<String>?): GapWindow? = v?.takeIf { it.size == 3 }?.let { GapWindow(it[0], Instant.ofEpochMilli(it[1].toLongOrNull() ?: return null), Instant.ofEpochMilli(it[2].toLongOrNull() ?: return null)) }
    }
}

data class GapState(
    val response: GapResponse? = null,
    val failure: String? = null,
    val ticked: Set<String> = emptySet(),
    val isSending: Boolean = false,
    /** What went out. Once it has, the screen is about that. */
    val sent: OfferSent? = null,
) {
    val canOffer: Boolean get() = response != null && ticked.isNotEmpty() && !isSending
}

/**
 * Fill the gap (chairtime `app/(pro)/gaps/page.tsx`). An empty hour is a lost
 * eighty pounds, and the moment it becomes fillable is the moment somebody
 * cancels — when the pro is mid-cut and has ninety seconds. So the whole thing
 * is one decision: here is who fits, tick them, send. The ordering does the thinking.
 */
class GapViewModel(val window: GapWindow, private val api: WaitlistApi, private val handle: suspend (ApiError) -> Unit, private val now: () -> Instant = Instant::now) : ViewModel() {
    private val _state = MutableStateFlow(GapState())
    val state: StateFlow<GapState> = _state.asStateFlow()

    init { load() }

    fun load(): Job = viewModelScope.launch {
        try { api.gap(window.staffId, window.from, window.to).let { r -> _state.update { it.copy(response = r, ticked = r.preselected, failure = null) } } }
        catch (error: ApiError) { if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) } }
    }

    fun joined(c: GapResponse.Candidate): String = WaitWords.joined(c.waitingSince, now())
    fun tick(entryId: String) = _state.update { it.copy(ticked = if (entryId in it.ticked) it.ticked - entryId else it.ticked + entryId) }

    /** "3 people could take it." */
    fun summary(state: GapState): String = state.response?.candidates?.size.let { n ->
        when (n) { null -> "Finding who could take it…"; 0 -> "Nobody waiting fits it."; 1 -> "1 person could take it."; else -> "$n people could take it." }
    }

    fun offer(): Job? {
        val s = _state.value
        val response = s.response ?: return null
        if (!s.canOffer) return null
        _state.update { it.copy(isSending = true) }
        return viewModelScope.launch {
            try {
                // In chairtime's order, best fit first, whatever order they were ticked in.
                val sent = api.offerGap(window.staffId, window.from, window.to, response.candidates.map { it.entryId }.filter { it in s.ticked })
                _state.update { it.copy(sent = sent, failure = null) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
            }
            _state.update { it.copy(isSending = false) }
        }
    }
}

// endregion
