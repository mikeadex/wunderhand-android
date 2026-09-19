package com.wunderhand.app.features.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.app.app.isTheApps
import com.wunderhand.core.BookingRules
import com.wunderhand.core.DraftProblem
import com.wunderhand.core.Employment
import com.wunderhand.core.HoursResponse
import com.wunderhand.core.HoursWrite
import com.wunderhand.core.Me
import com.wunderhand.core.MoneyInput
import com.wunderhand.core.OutletDraft
import com.wunderhand.core.OutletResponse
import com.wunderhand.core.OutletsResponse
import com.wunderhand.core.PolicyResponse
import com.wunderhand.core.PolicyWrite
import com.wunderhand.core.ReminderWords
import com.wunderhand.core.RemindersResponse
import com.wunderhand.core.RemindersWrite
import com.wunderhand.core.ShopResponse
import com.wunderhand.core.TeamPersonResponse
import com.wunderhand.core.TeamPersonWrite
import com.wunderhand.core.TeamResponse
import com.wunderhand.network.ApiError
import com.wunderhand.network.ShopApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// region The index

/** Where in the Shop tab somebody is. A person's page sits on top of the team, so this is a stack. */
sealed interface ShopRoute {
    val raw: String
    data object Hours : ShopRoute { override val raw = "hours" }
    data object Rules : ShopRoute { override val raw = "rules" }
    data object Policy : ShopRoute { override val raw = "policy" }
    data object Reminders : ShopRoute { override val raw = "reminders" }
    data object Team : ShopRoute { override val raw = "team" }
    data object Outlets : ShopRoute { override val raw = "outlets" }
    data object Account : ShopRoute { override val raw = "account" }
    data object Close : ShopRoute { override val raw = "close" }
    data class Person(val id: String) : ShopRoute { override val raw = "person:$id" }

    /** What the owner-only note calls it. */
    val title: String
        get() = when (this) {
            Hours -> "Working hours"; Rules -> "Booking rules"; Policy -> "Deposits and cancellation"; Reminders -> "Reminders"
            Team, is Person -> "Team"; Outlets -> "Outlets"; Account -> "Your login"; Close -> "Close this shop"
        }

    /**
     * Each of these screens is the shop's, not somebody's own day, so all of
     * them are an owner's (chairtime `lib/auth/owner.ts`) — but for their own
     * hours, and their own login, which is one person's whoever they are.
     */
    val isAnybodys: Boolean get() = this == Hours || this == Account

    companion object {
        fun of(raw: String): ShopRoute? = when {
            raw.startsWith("person:") -> Person(raw.removePrefix("person:"))
            else -> listOf(Hours, Rules, Policy, Reminders, Team, Outlets, Account, Close).firstOrNull { it.raw == raw }
        }
    }
}

data class ShopState(
    val response: ShopResponse? = null,
    val failure: String? = null,
    val path: List<ShopRoute> = emptyList(),
    /** Counts each screen opened, so one come back to is looked at afresh and one turned sideways is not. */
    val visit: Int = 0,
    val isRefreshing: Boolean = false,
    val switchingTo: String? = null,
)

/** The Shop tab (chairtime `app/(pro)/shop/page.tsx`). */
class ShopViewModel(
    val me: Me,
    private val api: ShopApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    /** Read from the session and not the loaded shop, so a screen never opens as an owner's for the moment before the answer arrives. */
    val isOwner: Boolean = me.staff.isOwner

    private val _state = MutableStateFlow(
        ShopState(path = saved.get<ArrayList<String>>("path").orEmpty().mapNotNull(ShopRoute::of), visit = saved["visit"] ?: 0),
    )
    val state: StateFlow<ShopState> = _state.asStateFlow()

    init { load() }

    fun load(byHand: Boolean = false): Job = viewModelScope.launch {
        if (byHand) _state.update { it.copy(isRefreshing = true) }
        try {
            val loaded = api.shop()
            _state.update { it.copy(response = loaded, failure = null) }
        } catch (error: ApiError) {
            if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) }
        }
        _state.update { it.copy(isRefreshing = false) }
    }

    private fun path(change: (List<ShopRoute>) -> List<ShopRoute>) {
        _state.update { it.copy(path = change(it.path), visit = it.visit + 1) }
        saved["path"] = ArrayList(_state.value.path.map { it.raw })
        saved["visit"] = _state.value.visit
    }

    /** From the index: a new stack, so on a tablet picking another row replaces what was open. */
    fun open(route: ShopRoute) = path { listOf(route) }
    fun push(route: ShopRoute) = path { it + route }

    fun back() {
        path { it.dropLast(1) }
        // Back from a settings screen: the row's fact may have changed.
        if (_state.value.path.isEmpty()) load()
    }

    fun switching(shopId: String?) = _state.update { it.copy(switchingTo = shopId) }
}

// endregion
// region A settings screen

/**
 * What the four settings screens share: loaded when the screen is opened,
 * kept while the phone is turned or unfolded, and looked at afresh the next
 * time it is come to.
 */
abstract class SettingViewModel(protected val handle: suspend (ApiError) -> Unit) : ViewModel() {
    private var visit: Int? = null

    fun enter(visit: Int) {
        if (this.visit == visit) return
        this.visit = visit
        load()
    }

    abstract fun load(): Job

    protected suspend fun attempt(onRefused: (ApiError) -> Unit, act: suspend () -> Unit) {
        try { act() } catch (error: ApiError) { if (error.isTheApps()) handle(error) else onRefused(error) }
    }
}

// Working hours

data class DayRow(val isOn: Boolean = false, val opens: String = "09:00", val closes: String = "18:00")

data class HoursState(
    val response: HoursResponse? = null,
    val failure: String? = null,
    val rows: Map<Int, DayRow> = emptyMap(),
    val problem: SaveProblem? = null,
    /** The day a refusal is about, so its name can say so. */
    val problemWeekday: Int? = null,
    val justSaved: Boolean = false,
    val isSaving: Boolean = false,
)

/**
 * Working hours (chairtime `app/(pro)/shop/hours`): one person at one outlet, a
 * row a day. An owner sets anybody's week; anybody else sets their own, which
 * is what chairtime allows them (`assertOwnerOrSelf`).
 */
class HoursViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(HoursState())
    val state: StateFlow<HoursState> = _state.asStateFlow()

    override fun load(): Job { _state.value = HoursState(); return show(null, null) }

    /** Somebody else's week, or the same person's somewhere else. */
    fun show(staffId: String?, outletId: String?): Job = viewModelScope.launch {
        attempt({ e -> _state.update { it.copy(failure = e.message) } }) {
            val r = api.hours(staffId, outletId)
            _state.value = HoursState(response = r, rows = HoursResponse.weekOrder.associateWith { w -> r.day(w)?.let { DayRow(true, it.opensAt, it.closesAt) } ?: DayRow() })
        }
    }

    fun edit(weekday: Int, change: (DayRow) -> DayRow) = _state.update { it.copy(rows = it.rows + (weekday to change(it.rows[weekday] ?: DayRow())), justSaved = false) }

    fun save(): Job? {
        val now = _state.value
        val staffId = now.response?.staffId ?: return null
        val outletId = now.response.outletId ?: return null
        if (now.isSaving) return null
        val open = HoursResponse.weekOrder.mapNotNull { w -> now.rows[w]?.takeIf { it.isOn }?.let { HoursResponse.Day(w, it.opens, it.closes) } }
        // Said here before it is sent, against the row it is about.
        open.firstOrNull { it.closesAt <= it.opensAt }?.let { bad ->
            _state.update { it.copy(problem = SaveProblem("Closes before it opens — check the times."), problemWeekday = bad.weekday) }
            return null
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            attempt({ e ->
                // "days.2.closesAt" names the third row sent; say which day that was.
                val index = (e as? ApiError.Validation)?.field?.takeIf { it.startsWith("days.") }?.split(".")?.getOrNull(1)?.toIntOrNull()
                _state.update { it.copy(problem = SaveProblem(e), problemWeekday = index?.let(open::getOrNull)?.weekday) }
            }) {
                val r = api.saveHours(HoursWrite(staffId, outletId, open))
                _state.update { it.copy(response = r, problem = null, problemWeekday = null, justSaved = true) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }
}

// Numbers: booking rules, and deposits and cancellation

data class NumbersState(
    val isLoaded: Boolean = false,
    val failure: String? = null,
    val text: Map<String, String> = emptyMap(),
    val problem: SaveProblem? = null,
    val justSaved: Boolean = false,
    val isSaving: Boolean = false,
    /** Policy only: what a client is told when the wording is left blank. */
    val defaultWording: String = "",
)

data class NumberSetting(val key: String, val label: String, val hint: String? = null) {
    /** "Slot interval", from "Slot interval (minutes)". */
    val name: String get() = label.substringBefore(" (")
}

/** A screen of whole numbers, saved together. */
abstract class NumbersViewModel(handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    protected val _state = MutableStateFlow(NumbersState())
    val state: StateFlow<NumbersState> = _state.asStateFlow()
    abstract val fields: List<NumberSetting>

    fun type(key: String, value: String) = _state.update { it.copy(text = it.text + (key to value), justSaved = false) }

    protected fun took(text: Map<String, String>, defaultWording: String = "") = _state.update { NumbersState(isLoaded = true, text = text, defaultWording = defaultWording) }

    /** Every field as a whole number, or null having said which one is not. */
    protected fun numbers(): Map<String, Int>? = fields.associate { f ->
        val n = _state.value.text[f.key].orEmpty().trim().toIntOrNull() ?: run {
            _state.update { it.copy(problem = SaveProblem("${f.name} must be a whole number", f.key)) }
            return null
        }
        f.key to n
    }

    protected fun saving(act: suspend () -> Unit): Job? {
        if (_state.value.isSaving) return null
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e)) } }) {
                act()
                _state.update { it.copy(problem = null, justSaved = true) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }

    abstract fun save(): Job?
}

/** Booking rules (chairtime `app/(pro)/shop/rules`). */
class RulesViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : NumbersViewModel(handle) {
    override val fields = listOf(
        NumberSetting("slotIntervalMinutes", "Slot interval (minutes)", "Times land on this grid — 15 gives 09:00, 09:15, 09:30. An awkward gap is still offered at its own start time, so nothing goes unsold for being off-grid."),
        NumberSetting("bufferMinutes", "Buffer after each appointment (minutes)", "Kept clear for tidying up. Comes off the end of the whole appointment, not each step."),
        NumberSetting("noticeHours", "Least notice (hours)", "The soonest a client can book from now."),
        NumberSetting("horizonWeeks", "Book up to (weeks ahead)"),
        NumberSetting("paymentHoldMinutes", "Hold while paying (minutes)", "The slot is genuinely reserved for this long — nobody else can take it while they finish."),
        NumberSetting("waitlistHoldMinutes", "Hold a waitlist offer (minutes)"),
    )

    private fun show(r: BookingRules) = took(mapOf(
        "slotIntervalMinutes" to "${r.slotIntervalMinutes}", "bufferMinutes" to "${r.bufferMinutes}", "noticeHours" to "${r.noticeHours}",
        "horizonWeeks" to "${r.horizonWeeks}", "paymentHoldMinutes" to "${r.paymentHoldMinutes}", "waitlistHoldMinutes" to "${r.waitlistHoldMinutes}",
    ))

    override fun load(): Job {
        _state.value = NumbersState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { show(api.rules()) } }
    }

    override fun save(): Job? {
        val n = numbers() ?: return null
        return saving {
            val savedRules = api.saveRules(BookingRules(n.getValue("slotIntervalMinutes"), n.getValue("bufferMinutes"), n.getValue("noticeHours"), n.getValue("horizonWeeks"), n.getValue("paymentHoldMinutes"), n.getValue("waitlistHoldMinutes")))
            show(savedRules)
        }
    }
}

/** Deposits and cancellation (chairtime `app/(pro)/shop/policy`). */
class PolicyViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : NumbersViewModel(handle) {
    override val fields = listOf(
        NumberSetting("freeCancellationHours", "Free to cancel until (hours before)"),
        NumberSetting("lateCancellationPercent", "Late cancellation fee (%)"),
        NumberSetting("noShowPercent", "No-show fee (%)", "Charged to the card taken at booking. UK banks can still refuse an absent-cardholder payment, so this will sometimes need chasing by hand."),
        NumberSetting("payInFullAfterNoShows", "Pay in full after this many no-shows"),
    )

    private fun show(p: PolicyResponse) = took(
        mapOf(
            DEPOSIT to MoneyInput.pounds(p.standardDepositPence), WORDING to p.clientFacingWording.orEmpty(),
            "freeCancellationHours" to "${p.freeCancellationHours}", "lateCancellationPercent" to "${p.lateCancellationPercent}",
            "noShowPercent" to "${p.noShowPercent}", "payInFullAfterNoShows" to "${p.payInFullAfterNoShows}",
        ),
        p.defaultWording,
    )

    override fun load(): Job {
        _state.value = NumbersState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { show(api.policy()) } }
    }

    override fun save(): Job? {
        val pence = MoneyInput.pence(_state.value.text[DEPOSIT].orEmpty()) ?: run {
            _state.update { it.copy(problem = SaveProblem("Enter an amount like 28.50", DEPOSIT)) }
            return null
        }
        val n = numbers() ?: return null
        val wording = _state.value.text[WORDING].orEmpty().trim().ifEmpty { null }
        return saving {
            show(api.savePolicy(PolicyWrite(pence, n.getValue("freeCancellationHours"), n.getValue("lateCancellationPercent"), n.getValue("noShowPercent"), n.getValue("payInFullAfterNoShows"), wording)))
        }
    }

    companion object {
        const val DEPOSIT = "standardDepositPence"
        const val WORDING = "clientFacingWording"
    }
}

// Reminders

data class ReminderRow(val isOn: Boolean, val value: String, val unit: ReminderWords.Unit) {
    val minutes: Int? get() = value.trim().toIntOrNull()?.takeIf { it > 0 }?.let { ReminderWords.minutes(it, unit) }
    /** "1 day before", or that nothing is set. */
    val says: String get() = minutes?.let(ReminderWords::offsetLabel) ?: "Not set"
}

data class RemindersState(
    val response: RemindersResponse? = null,
    val failure: String? = null,
    val rows: List<ReminderRow> = emptyList(),
    val problem: SaveProblem? = null,
    val justSaved: Boolean = false,
    val isSaving: Boolean = false,
)

/** Reminders (chairtime `app/(pro)/shop/reminders`): up to three emails before an appointment. Saved wholesale, as the web's form is. */
class RemindersViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(RemindersState())
    val state: StateFlow<RemindersState> = _state.asStateFlow()

    private fun rows(r: RemindersResponse) = r.slots.map { s -> ReminderWords.split(s.minutesBefore).let { (n, unit) -> ReminderRow(s.enabled, if (n > 0) "$n" else "", unit) } }

    override fun load(): Job {
        _state.value = RemindersState()
        return viewModelScope.launch { attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.reminders().let { r -> _state.value = RemindersState(r, rows = rows(r)) } } }
    }

    fun edit(index: Int, change: (ReminderRow) -> ReminderRow) = _state.update { s -> s.copy(rows = s.rows.mapIndexed { i, row -> if (i == index) change(row) else row }, justSaved = false) }

    fun save(): Job? {
        if (_state.value.isSaving) return null
        val slots = _state.value.rows.mapIndexedNotNull { i, row ->
            fun refuse(text: String): Nothing? { _state.update { it.copy(problem = SaveProblem(text, "slots.$i.minutesBefore")) }; return null }
            // An empty row is a slot the shop did not use. Only an empty row that is switched on is a contradiction.
            if (row.value.isBlank()) { if (row.isOn) return refuse("Say how long before.") else return@mapIndexedNotNull null }
            RemindersWrite.Slot(row.minutes ?: return refuse("Give a number of ${row.unit.name.lowercase()}."), row.isOn)
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = SaveProblem(e)) } }) {
                val r = api.saveReminders(RemindersWrite(slots))
                _state.update { it.copy(response = r, rows = rows(r), problem = null, justSaved = true) }
            }
            _state.update { it.copy(isSaving = false) }
        }
    }
}

// endregion
// region The team

data class TeamState(val response: TeamResponse? = null, val failure: String? = null, val isAdding: Boolean = false)

/** The team (chairtime `app/(pro)/shop/team`): who is on it, who logs in, who is in the diary and who sees the money. */
class TeamViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit, private val saved: SavedStateHandle = SavedStateHandle()) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(TeamState(isAdding = saved["adding"] ?: false))
    val state: StateFlow<TeamState> = _state.asStateFlow()

    // Every time it is come back to: somebody may have been changed. What is on screen stays while it is asked.
    override fun load(): Job = viewModelScope.launch {
        attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.team().let { r -> _state.update { it.copy(response = r, failure = null) } } }
    }

    fun adding(showing: Boolean) {
        saved["adding"] = showing
        _state.update { it.copy(isAdding = showing) }
    }
}

data class PersonState(
    val response: TeamPersonResponse? = null,
    val failure: String? = null,
    /** What just happened, in a sentence — chairtime's where it sent one. */
    val notice: String? = null,
    val problem: String? = null,
    val email: String = "",
    val isBusy: Boolean = false,
    val isEditing: Boolean = false,
)

/**
 * One person (chairtime `app/(pro)/shop/team/[id]`). Three things live here and
 * only one of them is an edit. Whether they can log in, and whether they see
 * the shop's money, are acts — an owner's, each with guards chairtime keeps,
 * each asked about first. Nothing here names a price.
 */
class TeamPersonViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val handle = handle
    private var shown: Pair<String, Int>? = null
    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state.asStateFlow()

    fun enter(id: String, visit: Int) {
        if (shown == id to visit) return
        // Back from the dead on the same person, mid-change: the form comes back once they have loaded. Anybody else starts clean.
        val wasEditing = saved.get<String>("editing") == id
        shown = id to visit
        _state.value = PersonState(isEditing = wasEditing)
        load(id)
    }

    private val id: String? get() = shown?.first

    fun load(id: String? = this.id): Job = viewModelScope.launch {
        id ?: return@launch
        try { api.teamPerson(id).let { r -> _state.update { it.copy(response = r, failure = null) } } }
        catch (error: ApiError) { if (error.isTheApps()) handle(error) else _state.update { it.copy(failure = error.message) } }
    }

    fun typeEmail(value: String) = _state.update { it.copy(email = value) }
    fun editing(showing: Boolean) {
        saved["editing"] = id.takeIf { showing }
        _state.update { it.copy(isEditing = showing) }
    }
    fun edited(response: TeamPersonResponse) = _state.update {
        saved["editing"] = null
        it.copy(response = response, isEditing = false, notice = null, problem = null) }

    /** One act, then the person as chairtime now has them — whether it worked or not, since a refusal usually means something changed elsewhere. */
    private fun act(body: suspend (String) -> Pair<TeamPersonResponse, String>): Job? {
        val id = id ?: return null
        if (_state.value.isBusy) return null
        _state.update { it.copy(isBusy = true) }
        return viewModelScope.launch {
            try {
                val (updated, said) = body(id)
                _state.update { it.copy(response = updated, notice = said, problem = null) }
            } catch (error: ApiError) {
                if (error.isTheApps()) handle(error) else {
                    _state.update { it.copy(problem = error.message, notice = null) }
                    load(id).join()
                }
            }
            _state.update { it.copy(isBusy = false) }
        }
    }

    fun invite(): Job? {
        val person = _state.value.response?.person ?: return null
        val address = _state.value.email.trim()
        if (!person.isComingBack && address.isEmpty()) {
            _state.update { it.copy(problem = "An email is needed", notice = null) }
            return null
        }
        // Somebody coming back needs no address: their login still exists.
        return act { id -> api.invite(id, address.takeIf { !person.isComingBack }).let { sent -> _state.update { it.copy(email = "") }; sent.asPerson to sent.message } }
    }

    fun setOwner(on: Boolean): Job? = act { id ->
        val updated = api.setOwner(id, on)
        val first = updated.person.firstName
        updated to if (on) "Saved. $first can now see the shop’s takings." else "Saved. ${if (updated.isYou) "You now see" else "$first now sees"} only ${if (updated.isYou) "your" else "their"} own takings."
    }

    fun remove(): Job? = act { id -> api.removeTeamPerson(id).let { it to "${it.person.firstName} is off the team, and out of the diary. Their appointments keep their name." } }
}

data class PersonFormState(
    val name: String = "", val role: String = "", val employment: Employment = Employment.Employed, val isBookable: Boolean = true,
    val outletIds: Set<String> = emptySet(), val outlets: List<TeamPersonResponse.Outlet> = emptyList(),
    val problem: SaveProblem? = null, val isSaving: Boolean = false,
)

/**
 * Somebody new, or somebody being changed (chairtime `components/shop/StaffForm.tsx`).
 * No login status here: a new person has none, and inviting them is its own act on their page.
 */
class TeamPersonFormViewModel(
    val existing: TeamPersonResponse?,
    private val api: ShopApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        PersonFormState(
            name = existing?.person?.name.orEmpty(), role = existing?.person?.roleTitle.orEmpty(),
            employment = existing?.person?.employment?.let(Employment::of) ?: Employment.Employed, isBookable = existing?.person?.isBookable ?: true,
            outletIds = existing?.outletIds.orEmpty().toSet(), outlets = existing?.outlets.orEmpty(),
        ).let { fresh ->
            // A form half filled in should survive a phone call.
            saved.get<ArrayList<String>>("typed")?.takeIf { it.size >= 4 }?.let { t ->
                fresh.copy(name = t[0], role = t[1], employment = Employment.of(t[2]) ?: fresh.employment, isBookable = t[3] == "true", outletIds = t.drop(4).toSet())
            } ?: fresh
        },
    )
    val state: StateFlow<PersonFormState> = _state.asStateFlow()

    init {
        // Somebody new works at the first outlet unless told otherwise, as on the web.
        if (existing == null) viewModelScope.launch {
            val all = runCatching { api.outlets() }.getOrNull() ?: return@launch
            _state.update { s ->
                val outlets = all.outlets.map { TeamPersonResponse.Outlet(it.id, it.name, it.city, it.postcode) }
                s.copy(outlets = outlets, outletIds = if (saved.contains("typed")) s.outletIds else setOfNotNull(outlets.firstOrNull()?.id))
            }
        }
    }

    fun edit(change: (PersonFormState) -> PersonFormState) {
        _state.update(change)
        _state.value.let { saved["typed"] = ArrayList(listOf(it.name, it.role, it.employment.raw, it.isBookable.toString()) + it.outletIds) }
    }

    fun save(onSaved: (TeamPersonResponse) -> Unit): Job? {
        val now = _state.value
        if (now.isSaving) return null
        val name = now.name.trim()
        if (name.isEmpty()) { _state.update { it.copy(problem = SaveProblem("A name is needed", "name")) }; return null }
        val write = TeamPersonWrite(name, now.role.trim().ifEmpty { null }, now.employment.raw, now.isBookable, now.outletIds.sorted())
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val response = if (existing != null) api.updateTeamPerson(existing.person.id, write) else api.addTeamPerson(write)
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
// region Outlets

data class OutletsState(
    val response: OutletsResponse? = null,
    val failure: String? = null,
    val problem: String? = null,
    /** The one being fetched to open: the list has no travel fees, and the form needs them. */
    val opening: String? = null,
    /** The form is up: on a new outlet when [editing] is null. */
    val isEditing: Boolean = false,
    val editing: OutletResponse? = null,
    val formVisit: Int = 0,
)

/** Outlets (chairtime `app/(pro)/shop/outlets`): where the shop is, and how far it travels. */
class OutletsViewModel(private val api: ShopApi, handle: suspend (ApiError) -> Unit, private val saved: SavedStateHandle = SavedStateHandle()) : SettingViewModel(handle) {
    private val _state = MutableStateFlow(OutletsState(formVisit = saved["formVisit"] ?: 0))

    init {
        // Back from the dead with a form up: a new outlet's needs nothing; one being changed is fetched again, and what was typed is laid over it.
        when (val was = saved.get<String>("form")) {
            null -> Unit
            "" -> _state.update { it.copy(isEditing = true) }
            else -> reopen(was)
        }
    }
    val state: StateFlow<OutletsState> = _state.asStateFlow()

    override fun load(): Job = viewModelScope.launch {
        attempt({ e -> _state.update { it.copy(failure = e.message) } }) { api.outlets().let { r -> _state.update { it.copy(response = r, failure = null) } } }
    }

    private fun remember(form: String?) {
        saved["form"] = form
        saved["formVisit"] = _state.value.formVisit
    }

    fun add() {
        _state.update { it.copy(isEditing = true, editing = null, formVisit = it.formVisit + 1) }
        remember("")
    }

    private fun reopen(id: String) = viewModelScope.launch {
        attempt({ e -> _state.update { it.copy(problem = e.message) } }) { api.outlet(id).let { r -> _state.update { it.copy(isEditing = true, editing = r) } } }
    }

    fun open(id: String): Job? {
        if (_state.value.opening != null) return null
        _state.update { it.copy(opening = id) }
        return viewModelScope.launch {
            attempt({ e -> _state.update { it.copy(problem = e.message) } }) {
                val r = api.outlet(id)
                _state.update { it.copy(isEditing = true, editing = r, problem = null, formVisit = it.formVisit + 1) }
                remember(id)
            }
            _state.update { it.copy(opening = null) }
        }
    }

    fun closeForm(savedOne: Boolean) {
        _state.update { it.copy(isEditing = false, editing = null) }
        remember(null)
        if (savedOne) load()
    }
}

data class OutletFormState(val draft: OutletDraft, val problem: SaveProblem? = null, val isSaving: Boolean = false)

/** A new outlet, or one being changed (chairtime `components/shop/LocationForm.tsx`). */
class OutletFormViewModel(
    val existing: OutletResponse?,
    private val api: ShopApi,
    private val handle: suspend (ApiError) -> Unit,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(OutletFormState(saved.get<ArrayList<String>>("draft")?.let(::unpack) ?: existing?.let(::OutletDraft) ?: OutletDraft()))
    val state: StateFlow<OutletFormState> = _state.asStateFlow()

    fun edit(change: (OutletDraft) -> OutletDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        saved["draft"] = pack(_state.value.draft)
    }

    fun addBand() = edit { if (it.bands.size >= OutletDraft.MAX_BANDS) it else it.copy(bands = it.bands + OutletDraft.BandDraft()) }
    fun removeBand(index: Int) = edit { it.copy(bands = it.bands.filterIndexed { i, _ -> i != index }) }
    fun editBand(index: Int, change: (OutletDraft.BandDraft) -> OutletDraft.BandDraft) = edit { d -> d.copy(bands = d.bands.mapIndexed { i, b -> if (i == index) change(b) else b }) }

    fun save(onSaved: (OutletResponse) -> Unit): Job? {
        if (_state.value.isSaving) return null
        val write = try { _state.value.draft.write() } catch (problem: DraftProblem) {
            _state.update { it.copy(problem = SaveProblem(problem)) }
            return null
        }
        _state.update { it.copy(isSaving = true) }
        return viewModelScope.launch {
            try {
                val response = if (existing != null) api.updateOutlet(existing.outlet.id, write) else api.addOutlet(write)
                _state.update { it.copy(problem = null, isSaving = false) }
                onSaved(response)
            } catch (error: ApiError) {
                // The server names bands by their place in the sorted ladder, which is not the row on screen; say it plainly.
                if (error.isTheApps()) handle(error) else _state.update { it.copy(problem = SaveProblem(error).let { p -> if (p.field?.startsWith("travelBands") == true) p.copy(field = null) else p }) }
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    private companion object {
        fun pack(d: OutletDraft): ArrayList<String> = ArrayList(
            listOf(d.name, d.addressLine1, d.city, d.postcode, d.timezone, d.servesRadiusMiles, d.defaultTravelMinutes, d.addressPrivate.toString()) + d.bands.flatMap { listOf(it.miles, it.fee) },
        )

        fun unpack(v: ArrayList<String>): OutletDraft? = if (v.size < 8) null else OutletDraft(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7] == "true", v.drop(8).chunked(2).filter { it.size == 2 }.map { OutletDraft.BandDraft(it[0], it[1]) })
    }
}

// endregion
