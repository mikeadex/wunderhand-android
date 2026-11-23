package com.wunderhand.app.features.shop

import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.core.Me
import com.wunderhand.core.ShopClock
import com.wunderhand.core.TimeOffCheck
import com.wunderhand.core.TimeOffDraft
import com.wunderhand.core.TimeOffEntry
import com.wunderhand.core.TimeOffResponse
import com.wunderhand.core.TimeOffWords
import com.wunderhand.network.ActionsApi
import com.wunderhand.network.ApiError
import com.wunderhand.network.ShopApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

data class TimeOffState(
    val response: TimeOffResponse? = null,
    val failure: String? = null,
    /** What just happened, said once. */
    val notice: String? = null,
    val problem: SaveProblem? = null,
    /** The form is open. */
    val isAdding: Boolean = false,
    val draft: TimeOffDraft? = null,
    /**
     * What is already booked in the days asked for. Shown before anything is
     * saved, and the button becomes "Take the time off anyway"; changing the
     * days asks again.
     */
    val check: TimeOffCheck? = null,
    val isSaving: Boolean = false,
    /** The line being taken back, by its key. */
    val removing: String? = null,
)

/**
 * Time off (chairtime `app/(pro)/shop/time-off`): the days coming up, a form
 * to take more, and each line's way back. Anybody's for their own days; who
 * else they may be given to is chairtime's to say (`mayChooseWho`), and it
 * refuses the rest.
 *
 * Bookings already in the days are never moved or cancelled, so the form asks
 * what is in them first and saves only once that has been seen.
 *
 * @param blocks an hour blocked from the diary is taken back where it was made.
 * @param today the shop's calendar date now; a new stretch starts the day after.
 */
class TimeOffViewModel(
    private val me: Me,
    private val api: ShopApi,
    private val blocks: ActionsApi,
    handle: suspend (ApiError) -> Unit,
    private val today: () -> String = { ShopClock(me.shop.timezone).isoDate(Instant.now()) },
) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(TimeOffState())
    val state: StateFlow<TimeOffState> = _state.asStateFlow()

    override fun load(): Job {
        _state.value = TimeOffState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.timeOff().let { r -> _state.update { it.copy(response = r) } } } }
    }

    /**
     * One day, tomorrow, for whoever is asking — or the first person there is,
     * for an owner the diary does not show. Tomorrow, as on the iPhone: today
     * is already under way, and is still there to be picked.
     */
    private fun fresh(response: TimeOffResponse?): TimeOffDraft {
        val people = response?.people.orEmpty()
        val who = people.firstOrNull { it.id == me.staff.id }?.id ?: people.firstOrNull()?.id ?: me.staff.id
        val first = today().let { now -> runCatching { LocalDate.parse(now).plusDays(1).toString() }.getOrDefault(now) }
        return TimeOffDraft(who, from = first, to = first)
    }

    fun adding(showing: Boolean) = _state.update { it.copy(isAdding = showing, draft = if (showing) fresh(it.response) else null, problem = null, check = null, notice = null) }

    /** Any change is a new question: what was in the way of the old days says nothing about these. */
    fun edit(change: (TimeOffDraft) -> TimeOffDraft) = _state.update { it.copy(draft = it.draft?.let(change), problem = null, check = null) }

    /** Asks what is booked in the days first. Nothing there, or already shown: saves. */
    fun save(): Job? {
        val now = _state.value
        val write = now.draft?.write() ?: return null
        if (now.isSaving) return null
        _state.update { it.copy(isSaving = true, problem = null, notice = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e)) } }) {
                if (now.check == null) {
                    val inTheWay = api.checkTimeOff(write)
                    if (inTheWay.inside.isNotEmpty()) {
                        _state.update { it.copy(check = inTheWay) }
                        return@attempt
                    }
                }
                val r = api.takeTimeOff(write)
                _state.update { it.copy(response = r, notice = TimeOffWords.SAVED, isAdding = false, draft = null, check = null) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    /** Whole days by the request and its dates; an hour by its own id, and then the list is read again. */
    fun remove(entry: TimeOffEntry): Job? {
        if (_state.value.removing != null) return null
        _state.update { it.copy(removing = entry.key, notice = null, problem = null) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e.message)) } }) {
                val blockId = entry.blockId
                val r = if (blockId != null) { blocks.unblock(blockId); api.timeOff() }
                else api.removeTimeOff(entry.seriesId.orEmpty(), entry.from, entry.to, entry.staffIdToRemove)
                _state.update { it.copy(response = r, notice = TimeOffWords.REMOVED) }
            }
            _state.update { it.copy(removing = null) }
        }
    }
}
