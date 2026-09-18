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
import com.wunderhand.network.WunderhandApi
import com.wunderhand.app.features.booking.NewBookingStart
import com.wunderhand.app.features.waitlist.GapWindow
import com.wunderhand.core.BookingCreated
import com.wunderhand.core.DiaryAppointment
import com.wunderhand.core.DiaryBreak
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

/** A change made by hand on a grid, waiting for chairtime's answer. */
data class PendingChange(val appointmentId: String, val edge: Edge, val at: Instant) {
    enum class Edge { Start, End }
}

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
    /** A drag let go of and not yet answered: the block stays where it was
     *  dropped, dimmed, until chairtime says yes or no. */
    val pending: PendingChange? = null,
    /** Why a move, a resize or an unblock was refused. */
    val actionProblem: String? = null,
    val isBlockingTime: Boolean = false,
    /** A new booking being made, and where it was started from. */
    val newBooking: NewBookingStart? = null,
    /** A stretch of somebody's day being offered to the waiting list. */
    val gap: GapWindow? = null,
    val isShowingWaitlist: Boolean = false,
) {
    val isShowingRequestedDay: Boolean get() = date == null || response?.date == date

    /**
     * The day something done now is about: the one asked for, not the one
     * still on screen while it loads. Tap Thursday, tap Block time, and it is
     * Thursday that gets blocked. Null only before anything has loaded, when
     * it is today.
     */
    val dayInHand: String? get() = date ?: response?.date

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
    val api: WunderhandApi,
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
            gap = GapWindow.unpack(saved["gap"]),
            newBooking = saved.get<ArrayList<String>>("booking")?.let { f ->
                NewBookingStart(f[0], f[1].ifEmpty { null }, f[2].toLongOrNull()?.let(Instant::ofEpochMilli), f[3].ifEmpty { null }, f[4].ifEmpty { null })
            },
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
    // region Changing the day

    /**
     * Move an appointment to a new start — a drag on a grid. Nothing is
     * checked here: chairtime's exclusion constraint decides, and a clash
     * comes back as a sentence with the appointment where it was.
     */
    fun move(appointment: DiaryAppointment, to: Instant) =
        change(PendingChange(appointment.id, PendingChange.Edge.Start, to)) { api.move(appointment.id, to) }

    /** Stretch or shorten an appointment by its end — the handle on a grid. */
    fun resize(appointment: DiaryAppointment, to: Instant) =
        change(PendingChange(appointment.id, PendingChange.Edge.End, to)) { api.resize(appointment.id, to) }

    fun unblock(block: DiaryBreak) = change(null) { api.unblock(block.id) }

    private fun change(pending: PendingChange?, body: suspend () -> Unit): Job? {
        if (_state.value.pending != null) return null
        _state.update { it.copy(pending = pending, actionProblem = null) }
        return viewModelScope.launch {
            try {
                body()
            } catch (error: ApiError) {
                if (error is ApiError.Unauthorized || error is ApiError.NotMember || error is ApiError.UpgradeRequired) {
                    _state.update { it.copy(pending = null) }
                    handle(error)
                    return@launch
                }
                _state.update { it.copy(actionProblem = error.message) }
            }
            // Whether it worked or not, the day is shown as chairtime now has
            // it — and only then does the block stop being where it was dropped.
            load().join()
            _state.update { it.copy(pending = null) }
        }
    }

    /** A refusal met by a sheet over the diary that is the whole app's business. */
    suspend fun handleElsewhere(error: ApiError) = handle(error)

    fun dismissActionProblem() = _state.update { it.copy(actionProblem = null) }

    fun blockingTime(showing: Boolean) = _state.update { it.copy(isBlockingTime = showing) }

    /** After time was blocked: show the day it was blocked on. */
    fun blocked(on: String) {
        _state.update { it.copy(isBlockingTime = false) }
        val s = _state.value
        if (on != (s.date ?: s.response?.date)) show(on) else load()
    }

    // endregion
    // region A new booking

    /** Start one: from the button (nothing chosen), somebody's column (who, and
     *  perhaps when), or a finished appointment (who it is for). */
    fun startBooking(start: NewBookingStart?) {
        // Kept where the system can hand it back: a booking half made should survive a phone call.
        saved["booking"] = start?.let { arrayListOf(it.id, it.staffId.orEmpty(), it.slot?.toEpochMilli()?.toString().orEmpty(), it.clientId.orEmpty(), it.clientName.orEmpty()) }
        _state.update { it.copy(newBooking = start) }
    }

    /** Offer a free stretch to whoever is waiting. Closed, the day is looked at again: an offer out changes who is waiting. */
    fun fillGap(window: GapWindow?) {
        saved["gap"] = window?.packed()
        val wasOpen = _state.value.gap != null
        _state.update { it.copy(gap = window) }
        if (window == null && wasOpen) load()
    }

    /** The waiting list, from "3 waiting". Closed, the count may have changed. */
    fun showWaitlist(showing: Boolean) {
        val wasOpen = _state.value.isShowingWaitlist
        _state.update { it.copy(isShowingWaitlist = showing) }
        if (!showing && wasOpen) load()
    }

    /** A booking was made: close the flow, go to its day, and open it there. */
    fun booked(created: BookingCreated) {
        startBooking(null)
        open(created.appointmentId)
        val s = _state.value
        if (created.date != (s.date ?: s.response?.date)) show(created.date) else load()
    }

    /**
     * A tap on an empty stretch of somebody's column books them there. Snapped
     * down to the shop's grid, as the web's column click is. The time comes
     * along only if they are working then and it has not passed — otherwise
     * the booking starts with just the person.
     */
    fun bookAt(member: DiaryMember, minutesFromGridStart: Int, gridStart: Instant) {
        if (_state.value.response?.team?.isClosed == true) return
        val interval = maxOf(member.day.slotIntervalMinutes, 1)
        val at = gridStart.plusSeconds((maxOf(minutesFromGridStart, 0) / interval * interval) * 60L)
        val working = member.day.open.any { it.start <= at && at < it.end }
        startBooking(NewBookingStart(staffId = member.id, slot = at.takeIf { working && it > now() }))
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
