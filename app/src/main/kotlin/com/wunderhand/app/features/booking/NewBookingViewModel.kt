package com.wunderhand.app.features.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.core.BookingCreated
import com.wunderhand.core.BookingService
import com.wunderhand.core.BookingServiceResponse
import com.wunderhand.core.BookingSlotsResponse
import com.wunderhand.core.BookingStep
import com.wunderhand.core.Ineligible
import com.wunderhand.core.IsoDay
import com.wunderhand.core.ShopClock
import com.wunderhand.network.ApiError
import com.wunderhand.network.BookingApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Where a new booking starts from: the diary's button (nothing chosen), an
 * empty stretch of someone's column on a tablet (who, and perhaps when), or
 * "Book them again" on a finished appointment (who it is for).
 */
data class NewBookingStart(
    val id: String = UUID.randomUUID().toString(),
    val staffId: String? = null,
    val slot: Instant? = null,
    val clientId: String? = null,
    val clientName: String? = null,
)

/** Why the time step is showing a note instead of a booking. */
sealed interface Refusal {
    /** Somebody took this time while it was being chosen. */
    data class Taken(val at: Instant) : Refusal
    /** A rule the booking failed, in chairtime's words. A rule this build has not heard of is [Problem]. */
    data class Rule(val rule: Ineligible, val message: String) : Refusal
    data class Problem(val message: String) : Refusal
}

data class NewBookingState(
    val services: List<BookingService>? = null,
    /** The chosen service's people, extras and rules. */
    val detail: BookingServiceResponse? = null,
    val slots: BookingSlotsResponse? = null,
    val loadProblem: String? = null,
    val serviceId: String? = null,
    /** Which outlet, at a shop where more than one does the service. */
    val outletId: String? = null,
    val staffId: String? = null,
    val addonIds: List<String> = emptyList(),
    val extrasSeen: Boolean = false,
    val slot: Instant? = null,
    /** The first day of times shown; null is today. */
    val from: String? = null,
    val overridePrerequisite: Boolean = false,
    val refusal: Refusal? = null,
    val isBooking: Boolean = false,
) {
    val hasExtras: Boolean get() = !detail?.addons.isNullOrEmpty()
    /** The outlets where somebody does the service; a step only when there is more than one. */
    val outlets: List<BookingServiceResponse.Outlet> get() = detail?.outlets.orEmpty()
    val needsOutlet: Boolean get() = detail?.needsOutlet ?: false
    val outlet: BookingServiceResponse.Outlet? get() = outlets.firstOrNull { it.id == outletId }

    /** Null while the chosen service's details are on their way: until then
     *  it is not known whether there is an outlet or an extras step. */
    val step: BookingStep?
        get() = if (serviceId != null && detail == null) null
        else BookingStep.current(serviceId != null, staffId != null, hasExtras, extrasSeen, outletNeeded = needsOutlet, outletChosen = outletId != null)

    val service: BookingService? get() = detail?.service ?: services?.firstOrNull { it.id == serviceId }
    val person: BookingServiceResponse.Performer? get() = detail?.staff?.firstOrNull { it.id == staffId }
    val chosenAddons: List<BookingServiceResponse.Addon> get() = detail?.addons?.filter { it.id in addonIds }.orEmpty()
    val extraMinutes: Int get() = chosenAddons.sumOf { it.minutes }
    val chosenSlot: BookingSlotsResponse.Slot? get() = slot?.let { slots?.slot(it) }
}

/**
 * A new booking, a step at a time (chairtime `app/(pro)/booking/new`):
 * service, person, extras when the service has any, then a time.
 *
 * Every answer is kept, and going back undoes the last one — as the web's URL
 * does. Nothing is held while choosing: the time is only claimed when "Book"
 * is pressed, and chairtime's database decides whether it still can be.
 *
 * The answers are also kept where the system can hand them back, so a phone
 * call in the middle of a booking does not lose it.
 */
class NewBookingViewModel(
    val start: NewBookingStart,
    private val api: BookingApi,
    val clock: ShopClock,
    val currency: String,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        NewBookingState(
            serviceId = saved["service"],
            outletId = saved["outlet"],
            staffId = saved["staff"] ?: start.staffId,
            addonIds = saved.get<ArrayList<String>>("addons").orEmpty(),
            extrasSeen = saved["extrasSeen"] ?: false,
            slot = saved.get<Long>("slot")?.let(Instant::ofEpochMilli),
            from = saved["from"],
        ),
    )
    val state: StateFlow<NewBookingState> = _state.asStateFlow()

    /** A time chosen before the times were fetched — tapped on the team grid —
     *  kept only if it turns out to be one on offer. */
    private var wantedSlot: Instant? = start.slot

    private fun set(change: (NewBookingState) -> NewBookingState) {
        _state.update(change)
        val s = _state.value
        saved["service"] = s.serviceId
        saved["outlet"] = s.outletId
        saved["staff"] = s.staffId
        saved["addons"] = ArrayList(s.addonIds)
        saved["extrasSeen"] = s.extrasSeen
        saved["slot"] = s.slot?.toEpochMilli()
        saved["from"] = s.from
    }

    init {
        viewModelScope.launch {
            loadServices()
            // Handed back by the system part-way through: pick up where it was.
            if (_state.value.serviceId != null) loadDetail()
        }
    }

    // region Loading

    suspend fun loadServices() {
        if (_state.value.services != null) return
        try {
            val services = api.bookingServices().services
            set { it.copy(services = services, loadProblem = null) }
        } catch (error: ApiError) { fail(error) }
    }

    private suspend fun loadDetail() {
        val asked = _state.value.serviceId ?: return
        val outlet = _state.value.outletId
        try {
            val loaded = api.bookingService(asked, outlet)
            if (asked != _state.value.serviceId || outlet != _state.value.outletId) return
            set { s ->
                // Someone tapped on the grid may not do this service, or not at this outlet: ask who is.
                s.copy(detail = loaded, loadProblem = null, staffId = s.staffId?.takeIf { id -> loaded.staff.any { it.id == id } })
            }
            if (_state.value.step == BookingStep.Time) loadSlots()
        } catch (error: ApiError) { fail(error) }
    }

    suspend fun loadSlots() {
        val s = _state.value
        val serviceId = s.serviceId ?: return
        val staffId = s.staffId ?: return
        val asked = listOf(serviceId, staffId, s.addonIds, s.from, s.outletId)
        try {
            val loaded = api.bookingSlots(serviceId, staffId, s.addonIds, s.from, s.outletId)
            // An answer to a question that has since changed is nobody's answer.
            if (asked != _state.value.let { listOf(it.serviceId, it.staffId, it.addonIds, it.from, it.outletId) }) return
            val wanted = wantedSlot.also { wantedSlot = null }
            set { it.copy(slots = loaded, loadProblem = null, slot = if (wanted != null && loaded.slot(wanted) != null) wanted else it.slot?.takeIf { at -> loaded.slot(at) != null }) }
        } catch (error: ApiError) { fail(error) }
    }

    private suspend fun fail(error: ApiError) {
        if (error is ApiError.Unauthorized || error is ApiError.NotMember || error is ApiError.UpgradeRequired) handle(error)
        else set { it.copy(loadProblem = error.message) }
    }

    // endregion
    // region Answers

    fun choose(service: BookingService): Job {
        set { clearedFromExtras(it).copy(serviceId = service.id, outletId = null, detail = null, addonIds = emptyList()) }
        return viewModelScope.launch { loadDetail() }
    }

    /** Which outlet: the people offered next are those who work there, so
     *  the service's details are fetched again with the outlet named. */
    fun choose(outlet: BookingServiceResponse.Outlet): Job {
        set { clearedFromExtras(it).copy(outletId = outlet.id) }
        return viewModelScope.launch { loadDetail() }
    }

    fun choose(person: BookingServiceResponse.Performer): Job {
        set { clearedFromExtras(it).copy(staffId = person.id) }
        return viewModelScope.launch { if (_state.value.step == BookingStep.Time) loadSlots() }
    }

    fun toggle(addon: BookingServiceResponse.Addon) = set { s ->
        // The times on offer were for a different length of appointment.
        s.copy(addonIds = if (addon.id in s.addonIds) s.addonIds - addon.id else s.addonIds + addon.id, slot = null, slots = null)
    }

    fun continueFromExtras(): Job {
        set { it.copy(extrasSeen = true, slots = null) }
        return viewModelScope.launch { loadSlots() }
    }

    fun select(at: Instant) = set { it.copy(slot = at, refusal = it.refusal.takeUnless { r -> r is Refusal.Taken }) }

    fun setOverride(ticked: Boolean) = set { it.copy(overridePrerequisite = ticked) }

    fun laterDays(): Job? {
        val last = _state.value.slots?.days?.lastOrNull() ?: return null
        set { it.copy(from = IsoDay.shift(last.isoDate, 1), slots = null) }
        return viewModelScope.launch { loadSlots() }
    }

    /** One step back, undoing its answer. False on the first step: there is
     *  nothing to go back to, and the booking is abandoned. */
    fun back(): Boolean {
        val s = _state.value
        if (s.serviceId == null) return false
        when (s.step) {
            BookingStep.Service -> return false
            BookingStep.Person -> if (s.needsOutlet) {
                // Back to "which outlet?"; the people are fetched again once one is chosen.
                set { clearedFromExtras(it).copy(outletId = null, staffId = null) }
            } else {
                set { clearedFromExtras(it).copy(serviceId = null, detail = null, staffId = null, addonIds = emptyList()) }
            }
            BookingStep.Outlet, null -> set { clearedFromExtras(it).copy(serviceId = null, outletId = null, detail = null, staffId = null, addonIds = emptyList()) }
            BookingStep.Extras -> set { clearedFromExtras(it).copy(staffId = null) }
            BookingStep.Time ->
                if (s.hasExtras) set { it.copy(extrasSeen = false, slot = null, slots = null, from = null, refusal = null) }
                else set { clearedFromExtras(it).copy(staffId = null) }
        }
        return true
    }

    private fun clearedFromExtras(s: NewBookingState) =
        s.copy(extrasSeen = false, slot = null, slots = null, from = null, refusal = null, overridePrerequisite = false)

    // endregion
    // region Booking

    /** Book the chosen time. [onBooked] only when chairtime said yes; otherwise the reason is in `refusal`. */
    fun book(onBooked: (BookingCreated) -> Unit): Job? {
        val s = _state.value
        val serviceId = s.serviceId ?: return null
        val staffId = s.staffId ?: return null
        val slot = s.slot ?: return null
        if (s.isBooking) return null
        set { it.copy(isBooking = true) }
        return viewModelScope.launch {
            try {
                val created = api.book(serviceId, staffId, slot, start.clientId, s.addonIds, s.overridePrerequisite, s.outletId)
                set { it.copy(refusal = null, isBooking = false) }
                onBooked(created)
            } catch (error: ApiError) {
                when (error) {
                    is ApiError.Unauthorized, is ApiError.NotMember, is ApiError.UpgradeRequired -> handle(error)
                    is ApiError.SlotTaken -> {
                        // The times below are fetched again, so the taken one is simply gone.
                        set { it.copy(refusal = Refusal.Taken(slot), slot = null) }
                        loadSlots()
                    }
                    is ApiError.Ineligible -> set {
                        it.copy(refusal = Ineligible.of(error.rule)?.let { rule -> Refusal.Rule(rule, error.message) } ?: Refusal.Problem(error.message))
                    }
                    else -> set { it.copy(refusal = Refusal.Problem(error.message)) }
                }
                set { it.copy(isBooking = false) }
            }
        }
    }

    // endregion
}
