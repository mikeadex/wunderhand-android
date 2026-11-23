package com.wunderhand.app.features.shop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.core.ShopClock
import com.wunderhand.core.TimeOffCheck
import com.wunderhand.core.TimeOffDraft
import com.wunderhand.core.TimeOffEntry
import com.wunderhand.core.TimeOffWords
import com.wunderhand.design.ChoiceField
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.DashedButton
import com.wunderhand.design.Hint
import com.wunderhand.design.Panel
import com.wunderhand.design.PanelRow
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Time off (chairtime `app/(pro)/shop/time-off`): days nobody can book — a
 * holiday, Christmas, a course — for one person or the whole shop at once.
 *
 * Until 1.4 a holiday was hours blocked on one day, a day at a time, from the
 * diary. This lists what is coming up, takes a first and a last day, and says
 * what is already booked in them before it saves: time off moves and cancels
 * nothing, so the person taking it has to know what they are leaving behind.
 *
 * Anybody's, for their own days. An owner gives them to somebody else, or to
 * everyone; the picker is offered only where there is somebody to pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeOffScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: TimeOffViewModel = viewModel(key = pageKey(shop.me, "time-off")) { TimeOffViewModel(shop.me, app.client, app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response
    val clock = remember(shop.me.shop.timezone) { ShopClock(shop.me.shop.timezone) }
    var removing by remember { mutableStateOf<TimeOffEntry?>(null) }
    /** The day being picked: true for the first, false for the last. */
    var picking by remember { mutableStateOf<Boolean?>(null) }

    SettingPage(TimeOffWords.TITLE, "timeOff", onBack, intro = response?.let { if (it.choosesWho) TimeOffWords.INTRO_SHOP else TimeOffWords.INTRO_YOU }) {
        Failure(state.failure, response == null) { model.load() }
        if (response == null) { if (state.failure == null) Loading("the time off", 2, 80); return@SettingPage }
        Notice(state.notice, "timeOffNotice")
        if (!state.isAdding) Problem(state.problem, "timeOffProblem")

        Heading(TimeOffWords.COMING_UP)
        if (response.comingUp.isEmpty()) Text(TimeOffWords.NONE, Modifier.padding(top = 8.dp, start = 4.dp).testTag("timeOffNone"), style = WHType.Body, color = WHColors.Neutral700)
        Entries(response.comingUp, response.choosesWho, state) { removing = it }

        if (response.blockedHours.isNotEmpty()) {
            Heading(TimeOffWords.BLOCKED_HOURS)
            Entries(response.blockedHours, response.choosesWho, state) { removing = it }
        }

        val draft = state.draft
        if (!state.isAdding || draft == null) DashedButton(TimeOffWords.ADD, { model.adding(true) }, Modifier.padding(top = 20.dp).testTag("timeOffAdd"))
        else Panel(TimeOffWords.ADD, Modifier.padding(top = 20.dp).testTag("timeOffForm"), action = "Close", actionTag = "timeOffFormClose", onAction = { model.adding(false) }) {
            PanelRow {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Problem(state.problem, "timeOffProblem")
                    state.check?.let { InTheWay(it, draft.from, clock) }
                    if (response.choosesWho) ChoiceField(
                        TimeOffWords.WHO, null, listOf(TimeOffDraft.EVERYONE to TimeOffWords.EVERYONE) + response.people.map { it.id to it.name }, draft.who,
                        { v -> if (v != null) model.edit { it.copy(who = v) } }, Modifier.testTag("field-timeOffWho"), isProblem = state.problem?.field == "who",
                    )
                    DayField(TimeOffWords.FIRST_DAY, draft.from, state.problem?.field == "from", Modifier.testTag("field-timeOffFrom")) { picking = true }
                    DayField(TimeOffWords.LAST_DAY, draft.to, state.problem?.field == "to", Modifier.testTag("field-timeOffTo")) { picking = false }
                    WHField(TimeOffWords.WHAT_FOR, draft.note, { v -> model.edit { it.copy(note = v.take(200)) } }, inputModifier = Modifier.testTag("field-timeOffNote"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), isProblem = state.problem?.field == "note", placeholder = TimeOffWords.WHAT_FOR_EXAMPLE)
                    Hint(TimeOffWords.WHAT_FOR_HINT)
                    PrimaryButton(if (state.check != null) TimeOffWords.ANYWAY else TimeOffWords.SAVE, { model.save() }, Modifier.testTag("timeOffSave"), loading = state.isSaving)
                }
            }
        }

        Hint(TimeOffWords.FOOTNOTE, Modifier.padding(top = 18.dp))
    }

    removing?.let { entry ->
        ConfirmDialog(
            title = "${TimeOffWords.removeConfirm(entry)}?", message = TimeOffWords.removeMessage(entry),
            confirm = TimeOffWords.removeConfirm(entry), onConfirm = { removing = null; model.remove(entry) }, onDismiss = { removing = null },
        )
    }

    val draft = state.draft
    val first = picking
    if (first != null && draft != null) {
        // A calendar date, picked and kept in UTC, so no travelling phone turns the 4th into the 3rd. Today is the shop's today.
        val today = remember { LocalDate.parse(clock.isoDate(Instant.now())) }
        val from = runCatching { LocalDate.parse(draft.from) }.getOrNull() ?: today
        val shown = if (first) from else runCatching { LocalDate.parse(draft.to) }.getOrNull() ?: from
        // The last day is never before the first, nor further off than chairtime takes in one go.
        val floor = (if (first) today else from).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ceiling = if (first) Long.MAX_VALUE else from.plusDays((state.response?.maxDays ?: 366) - 1L).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = today.year..today.year + 3,
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in floor..ceiling },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                WordsButton("Choose", {
                    picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }?.let { day -> model.edit { if (first) it.withFrom(day) else it.withTo(day) } }
                    picking = null
                }, Modifier.testTag("chooseDate"))
            },
            dismissButton = { WordsButton("Cancel", { picking = null }, color = WHColors.Neutral700) },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 20.dp, start = 4.dp).semantics { heading() }, style = WHType.Semi14, color = WHColors.Neutral700)
}

@Composable
private fun Entries(entries: List<TimeOffEntry>, showWho: Boolean, state: TimeOffState, onRemove: (TimeOffEntry) -> Unit) {
    if (entries.isEmpty()) return
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (entry in entries) EntryCard(entry, showWho, isRemoving = state.removing == entry.key, canRemove = state.removing == null) { onRemove(entry) }
    }
}

/** The days, what they are for and whose, and the way back. Bookings still inside are said under them, because nothing else will. */
@Composable
private fun EntryCard(entry: TimeOffEntry, showWho: Boolean, isRemoving: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    val days = TimeOffWords.whenLine(entry)
    WHCard(Modifier.testTag("timeOff-${entry.key}")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp).padding(top = 8.dp, bottom = if (entry.inside > 0) 2.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(days, style = WHType.RowName, color = WHColors.Ink)
                Text(TimeOffWords.whatLine(entry, showWho), style = WHType.Meta, color = WHColors.Neutral700)
            }
            WordsButton(TimeOffWords.REMOVE, onRemove, Modifier.semantics { contentDescription = "Remove $days" }.testTag("timeOffRemove"), color = WHColors.Accent, enabled = canRemove || isRemoving, loading = isRemoving)
        }
        TimeOffWords.insideNote(entry.inside)?.let {
            Text(
                it, Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WHColors.Accent100).padding(horizontal = 10.dp, vertical = 8.dp).testTag("timeOffInside"),
                style = WHType.Meta, color = WHColors.Accent800,
            )
        }
    }
}

/**
 * What is booked in the days asked for, before anything is saved. Each
 * booking is named: "3 bookings" is not something anybody can act on, and
 * these are the clients who would otherwise turn up to a locked door.
 *
 * It arrives above the button that was just pressed, at the top of the form —
 * on a phone, off the top of the screen. So it brings itself into view: a
 * warning nobody saw is a button that changed its words for no reason.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InTheWay(check: TimeOffCheck, firstDay: String, clock: ShopClock) {
    val shape = RoundedCornerShape(12.dp)
    val count = check.inside.size
    val intoView = remember { BringIntoViewRequester() }
    LaunchedEffect(check) { intoView.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(intoView).clip(shape).background(WHColors.Accent100).border(1.dp, WHColors.Accent300, shape).padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("timeOffInTheWay"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(TimeOffWords.checkHeading(count), Modifier.semantics { heading() }, style = WHType.Semi14, color = WHColors.Accent800)
        Text(TimeOffWords.checkBody(count), style = WHType.Summary, color = WHColors.Accent800)
        for (booking in check.inside.take(TimeOffWords.SHOWN)) {
            Text(
                TimeOffWords.insideLine(booking, clock, check.several),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WHColors.Surface).padding(horizontal = 10.dp, vertical = 8.dp).testTag("timeOffBooking"),
                style = WHType.Meta, color = WHColors.Ink,
            )
        }
        TimeOffWords.moreLine(count, firstDay)?.let { Text(it, style = WHType.Meta, color = WHColors.Accent800) }
    }
}

/** A calendar date, as a field: its name small above, the day under it, and the picker behind it. */
@Composable
private fun DayField(label: String, iso: String, isProblem: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val shown = TimeOffWords.day(iso)
    Column(
        modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, if (isProblem) WHColors.Accent else WHColors.Divider, shape)
            .clickable(role = Role.Button, onClickLabel = "Change the day", onClick = onPick).semantics { contentDescription = "$label: $shown" }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
        Text(shown, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Ink)
    }
}
