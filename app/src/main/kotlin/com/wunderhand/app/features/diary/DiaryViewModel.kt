package com.wunderhand.app.features.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.core.DiaryMember
import com.wunderhand.core.DiaryResponse
import com.wunderhand.core.IsoDay
import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.core.ShopClock
import com.wunderhand.network.ApiError
import com.wunderhand.network.DiaryApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * How the day is drawn. A phone offers Day (the agenda), Grid (one person's
 * day by the hour) and Week; a tablet at full width offers Day (the team's
 * columns), Week and List — as the web does on a phone and a laptop.
 */
enum class DiaryMode { Day, Grid, Week, List }

/** Everything the diary screen draws from. */
data class DiaryState(
    /** The shop's calendar date asked for; null is today where the shop is.
     *  Only a person changes it — a load never does, so loading a day cannot
     *  set off another load of the same day. */
    val date: String? = null,
    val mode: DiaryMode = DiaryMode.Day,
    /** The person the day is narrowed to; null is everyone. */
    val focusStaffId: String? = null,
    val openAppointmentId: String? = null,
    val response: DiaryResponse? = null,
    val isLoading: Boolean = false,
    /** Why the last load failed, shown over whatever was loaded before it. */
    val failure: String? = null,
    /** When what is on screen was last true. A day kept through a failed
     *  reload is worth reading, but never worth mistaking for now. */
    val loadedAt: Instant? = null,
) {
    val isShowingRequestedDay: Boolean get() = date == null || response?.date == date

    /** A day on screen that chairtime could not confirm. */
    val isShowingAKeptDay: Boolean get() = response != null && failure != null

    val members: List<DiaryMember> get() = response?.team?.members.orEmpty()

    /** The people the phone's agenda shows: the chosen one, or everyone. */
    val shownMembers: List<DiaryMember>
        get() = members.firstOrNull { it.id == focusStaffId }?.let(::listOf) ?: members

    /** Whose day the phone's grid draws: the chosen person, or yourself, or whoever is first. */
    fun gridMember(myStaffId: String): DiaryMember? =
        members.firstOrNull { it.id == focusStaffId } ?: members.firstOrNull { it.id == myStaffId } ?: members.firstOrNull()

    val heading: String
        get() = response?.let { if (it.date == it.today) "Today" else IsoDay.weekdayLong(it.date) } ?: "Today"

    /** Whether the week on screen contains today — if not, offer a way back. */
    val weekContainsToday: Boolean get() = response?.let { r -> r.week.any { it.isoDate == r.today } } ?: true
}

/**
 * The diary screen's state: which day, which view, whose, and what chairtime
 * said about it.
 *
 * @param handle what to do with a refusal that is the whole app's business —
 *   a session that has ended, a shop that is no longer theirs.
 */
class DiaryViewModel(
    val me: Me,
    private val api: DiaryApi,
    private val cache: OfflineCache,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    val clock = ShopClock(me.shop.timezone)
    val currency: String get() = me.shop.currency

    // What somebody chose comes back after the system has killed the app in the background.
    private val _state = MutableStateFlow(
        DiaryState(
            date = saved["date"],
            mode = saved.get<String>("mode")?.let { name -> DiaryMode.entries.firstOrNull { it.name == name } } ?: DiaryMode.Day,
            focusStaffId = saved["focus"],
            openAppointmentId = saved["open"],
        ),
    )
    val state: StateFlow<DiaryState> = _state.asStateFlow()

    private var loading: Job? = null

    init { load() }

    /** "FRIDAY 18 SEPTEMBER": the day on screen, in the shop's words. */
    val eyebrow: String
        get() = _state.value.response?.let { clock.dayEyebrow(it.dayStart.plus(Duration.ofHours(12))) } ?: clock.dayEyebrow(now())

    // region Loading

    /** Ask for the day. A load already under way is dropped: the newest question is the one that matters. */
    fun load(): Job {
        loading?.cancel()
        return viewModelScope.launch { fetch() }.also { loading = it }
    }

    private suspend fun fetch() {
        _state.update { it.copy(isLoading = true) }
        try {
            val loaded = api.diary(_state.value.date)
            val at = now()
            _state.update { s ->
                s.copy(
                    response = loaded, failure = null, loadedAt = at, isLoading = false,
                    // A person removed from the team since they were chosen.
                    focusStaffId = s.focusStaffId?.takeIf { id -> loaded.team.members.any { it.id == id } },
                )
            }
            // Off the main thread: the screen is already drawn, and a day on disk is never worth a frame.
            withContext(io) { cache.save(loaded, at, me.shop.id) }
        } catch (error: ApiError) {
            _state.update { it.copy(isLoading = false) }
            when (error) {
                is ApiError.Unauthorized, is ApiError.NotMember, is ApiError.UpgradeRequired -> handle(error)
                else -> {
                    _state.update { it.copy(failure = error.message) }
                    fallBackOnWhatWasKept()
                }
            }
        }
    }

    /** Nothing on screen and no answer: show the day this shop last loaded,
     *  if it is the day being asked for. The bar above it says how old it is. */
    private suspend fun fallBackOnWhatWasKept() {
        if (_state.value.response != null) return
        val wanted = _state.value.date ?: clock.isoDate(now())
        val kept = withContext(io) { cache.savedDay(me.shop.id, wanted) } ?: return
        _state.update { it.copy(response = kept.response, loadedAt = kept.at) }
    }

    // endregion
    // region What somebody chooses

    /** Show another day. The one on screen stays until the new one arrives,
     *  so the week strip does not flash blank between taps. */
    fun show(date: String) {
        val s = _state.value
        if (date == (s.date ?: s.response?.date)) return
        saved["date"] = date
        _state.update { it.copy(date = date) }
        load()
    }

    fun showToday() {
        _state.value.response?.today?.let(::show)
    }

    fun shiftWeek(weeks: Int) {
        val current = _state.value.let { it.date ?: it.response?.date } ?: return
        show(IsoDay.shift(current, 7 * weeks))
    }

    fun setMode(mode: DiaryMode) {
        saved["mode"] = mode.name
        _state.update { it.copy(mode = mode) }
    }

    fun focus(staffId: String?) {
        saved["focus"] = staffId
        _state.update { it.copy(focusStaffId = staffId) }
    }

    fun open(appointmentId: String?) {
        saved["open"] = appointmentId
        _state.update { it.copy(openAppointmentId = appointmentId) }
    }

    /** A day from the week list: go to it, as a day. */
    fun openDay(date: String) {
        setMode(DiaryMode.Day)
        show(date)
    }

    /** The grid and the list are the wide layout's; the phone's grid is its own. */
    fun fitTo(wide: Boolean) {
        val mode = _state.value.mode
        if (wide && mode == DiaryMode.Grid) setMode(DiaryMode.Day)
        if (!wide && mode == DiaryMode.List) setMode(DiaryMode.Day)
    }

    // endregion
}
