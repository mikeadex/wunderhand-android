package com.wunderhand.app.features.diary

import com.wunderhand.core.ActionWords
import com.wunderhand.core.AppointmentResponse
import com.wunderhand.core.CloseOutcome
import com.wunderhand.core.ShopClock
import com.wunderhand.core.SlotsResponse
import com.wunderhand.network.ApiError
import com.wunderhand.network.WunderhandApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** What an open appointment is in the middle of doing. One thing at a time. */
enum class SheetAction { Done, NoShow, Cancel, Consent, RepeatStart, RepeatStop, RepeatStopAndCancel }

/** A sentence after something happened — or was refused. */
data class Notice(val text: String, val isProblem: Boolean)

data class AppointmentState(
    val response: AppointmentResponse? = null,
    val loadFailure: String? = null,
    val busy: SheetAction? = null,
    val notice: Notice? = null,
    // Moving it: where it could go, and the one being tried.
    val slots: SlotsResponse? = null,
    val slotsProblem: String? = null,
    val moving: Instant? = null,
)

/**
 * One open appointment: what chairtime says about it, and the things that can
 * be done to it from the sheet.
 *
 * @param changed the day underneath is no longer what it was: reload it.
 * @param handle a refusal that is the whole app's business.
 */
class AppointmentModel(
    val id: String,
    private val api: WunderhandApi,
    val clock: ShopClock,
    private val changed: suspend () -> Unit,
    private val handle: suspend (ApiError) -> Unit,
) {
    private val _state = MutableStateFlow(AppointmentState())
    val state: StateFlow<AppointmentState> = _state.asStateFlow()

    val currency: String get() = _state.value.response?.appointment?.currency ?: "GBP"

    private suspend fun isTheAppsBusiness(error: ApiError): Boolean {
        val theApps = error is ApiError.Unauthorized || error is ApiError.NotMember || error is ApiError.UpgradeRequired
        if (theApps) handle(error)
        return theApps
    }

    suspend fun load() {
        try {
            val response = api.appointment(id)
            _state.update { it.copy(response = response, loadFailure = null) }
        } catch (error: ApiError) {
            isTheAppsBusiness(error)
            // What was on screen stays there; a first load that failed says why.
            _state.update { it.copy(loadFailure = error.message) }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // region Actions

    suspend fun close(outcome: CloseOutcome) = run(
        when (outcome) {
            CloseOutcome.Completed -> SheetAction.Done
            CloseOutcome.NoShow -> SheetAction.NoShow
            CloseOutcome.Cancelled -> SheetAction.Cancel
        },
    ) { ActionWords.closed(outcome, api.close(id, outcome).refund, currency) }

    suspend fun recordConsent() {
        val version = _state.value.response?.consentWording?.version
        run(SheetAction.Consent) {
            api.recordConsent(id)
            ActionWords.consentRecorded(version)
        }
    }

    suspend fun startRepeat(weeks: Int) = run(SheetAction.RepeatStart) { ActionWords.repeatStarted(api.startRepeat(id, weeks)) }

    suspend fun stopRepeat(cancelUpcoming: Boolean) =
        run(if (cancelUpcoming) SheetAction.RepeatStopAndCancel else SheetAction.RepeatStop) { ActionWords.repeatStopped(api.stopRepeat(id, cancelUpcoming)) }

    /**
     * Do one thing, say what happened, then show the appointment and the day
     * as they now are — whether it worked or not, since a refusal usually
     * means something changed elsewhere.
     */
    private suspend fun run(action: SheetAction, body: suspend () -> String) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action, notice = null) }
        try {
            val result = try {
                Notice(body(), isProblem = false)
            } catch (error: ApiError) {
                if (isTheAppsBusiness(error)) return
                Notice(error.message, isProblem = true)
            }
            // The sentence and the appointment it describes arrive together, so
            // "Booked the next 3" never sits above a card that does not show them.
            load()
            _state.update { it.copy(notice = result) }
            changed()
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    // endregion
    // region Moving it

    /** The next open days with the same person; later ones from a date. */
    suspend fun loadSlots(from: String? = null) {
        try {
            val slots = api.slots(id, from)
            _state.update { it.copy(slots = slots) }
        } catch (error: ApiError) {
            if (isTheAppsBusiness(error)) return
            _state.update { it.copy(slotsProblem = error.message) }
        }
    }

    fun forgetSlots() = _state.update { it.copy(slots = null, slotsProblem = null, moving = null) }

    /** Move it there. True when it moved, and the sheet can go back to the appointment. */
    suspend fun moveTo(startsAt: Instant): Boolean {
        if (_state.value.moving != null) return false
        _state.update { it.copy(moving = startsAt, slotsProblem = null) }
        try {
            api.move(id, startsAt)
            load()
            _state.update { it.copy(notice = Notice(ActionWords.moved(startsAt, clock), isProblem = false)) }
            changed()
            return true
        } catch (error: ApiError) {
            if (isTheAppsBusiness(error)) return false
            // Most likely somebody took it while this screen was open: say so,
            // and show the times as they are now.
            val problem = if (error is ApiError.SlotTaken) ActionWords.SLOT_TAKEN_WHILE_LOOKING else error.message
            _state.update { it.copy(slotsProblem = problem) }
            loadSlots()
            return false
        } finally {
            _state.update { it.copy(moving = null) }
        }
    }

    // endregion
}
