package com.wunderhand.app.features.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.core.HoursResponse
import com.wunderhand.core.ReminderWords
import com.wunderhand.design.Hint
import com.wunderhand.design.NoteCard
import com.wunderhand.design.Panel
import com.wunderhand.design.PanelRow
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SavedLine
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHChip
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHSegmented
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton

// Done on the number pad puts it away: there is no other key on it that would.
internal val wholeNumbers = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
internal val amounts = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)

// region What the settings screens share

/**
 * A settings screen: where it sits, what it is, what it is for, and one button
 * under it that says "Saved." once it has.
 *
 * @param onBack null beside the index on a tablet, where there is nowhere to go back to.
 */
@Composable
internal fun SettingPage(
    title: String, tag: String, onBack: (() -> Unit)?, intro: String? = null, eyebrow: String = "Shop",
    save: String? = null, onSave: () -> Unit = {}, isSaving: Boolean = false, justSaved: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = LocalFocusManager.current
    EditorPage(
        title = title, onClose = onBack, tag = tag, intro = intro, back = true, eyebrow = eyebrow, closeLabel = "Back to ${eyebrow.lowercase().let { if (it == "shop") "the shop" else "the $it" }}",
        footer = if (save != null) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (justSaved) SavedLine("Saved.")
                PrimaryButton(save, { focus.clearFocus(); onSave() }, Modifier.weight(1f).testTag("$tag-save"), loading = isSaving)
            }
        }) else null,
        content = content,
    )
}

@Composable
internal fun Loading(what: String, rows: Int, height: Int) {
    Column(Modifier.padding(top = 20.dp).semantics { contentDescription = "Loading $what" }, verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(rows) { SkeletonBlock(height.dp, radius = 10.dp) } }
}

@Composable
internal fun Failure(text: String?, isBlank: Boolean, onRetry: () -> Unit) {
    if (text == null) return
    NoteCard(text, Modifier.padding(top = 16.dp))
    if (isBlank) WordsButton("Try again", onRetry)
}

@Composable
internal fun Problem(problem: SaveProblem?, tag: String) {
    problem?.let { NoteCard(it.text, Modifier.padding(top = 16.dp).testTag(tag)) }
}

/**
 * A screen that belongs to whoever runs the shop, reached by somebody who does
 * not. Shown instead of the screen and not beside a dead button: chairtime
 * refuses the change anyway (`lib/auth/owner.ts`), and the web sends them back
 * to Shop with the same sentence.
 */
@Composable
internal fun OwnerOnlyNote(what: String, onBack: (() -> Unit)?) {
    EditorPage(title = what, onClose = onBack, tag = "ownerOnly", back = true, closeLabel = "Back to the shop") {
        Text("That is for an owner of the shop. You can see it on the web, and ask whoever runs the shop to make the change.", Modifier.padding(top = 12.dp).widthIn(max = 520.dp), style = WHType.Body, color = WHColors.Neutral800)
    }
}

// endregion
// region Working hours

/**
 * Working hours (chairtime `app/(pro)/shop/hours`): one person at one outlet, a
 * row a day. Switch a day off and it becomes a day off — no times are kept.
 * Breaks and holidays are blocked separately, from the diary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HoursScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: HoursViewModel = viewModel(key = "shop-hours") { HoursViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response
    val usable = response != null && response.staff.isNotEmpty() && response.outlets.isNotEmpty()
    /** The time being picked: which day, and whether it is when it opens. */
    var picking by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    val who = response?.person?.let { p -> if (response.outlets.size > 1) "${p.name} · ${response.outlet?.name.orEmpty()}" else p.name }
    SettingPage("Working hours", "hours", onBack, intro = who, save = "Save hours".takeIf { usable }, onSave = { model.save() }, isSaving = state.isSaving, justSaved = state.justSaved) {
        Failure(state.failure, response == null) { model.load() }
        when {
            response == null -> if (state.failure == null) Loading("the hours", 7, 54)
            !usable -> NoteCard("Add someone to the team and an outlet first — hours belong to a person at a place.", Modifier.padding(top = 16.dp))
            else -> {
                // Who, and where — chips, as the web has them, only when there is a choice. An owner picks whose week; anybody else has only their own.
                if (shop.isOwner && response.staff.size > 1) Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()).testTag("hoursPeople"), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (p in response.staff) WHChip(p.name.substringBefore(' '), p.id == response.staffId, { model.show(p.id, response.outletId) }, Modifier.semantics { contentDescription = "${p.name}’s hours" })
                }
                if (response.outlets.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).testTag("hoursOutlets"), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (o in response.outlets) WHChip(o.name, o.id == response.outletId, { model.show(response.staffId, o.id) })
                }
                Problem(state.problem, "hoursProblem")
                WHCard(Modifier.padding(top = 16.dp)) {
                    HoursResponse.weekOrder.forEachIndexed { index, weekday ->
                        if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                        val row = state.rows[weekday] ?: DayRow()
                        val name = HoursResponse.dayName(weekday)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            SwitchRow(name, row.isOn, { on -> model.edit(weekday) { it.copy(isOn = on) } }, Modifier.testTag("day-$weekday"), hint = if (row.isOn) null else "Day off")
                            if (row.isOn) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TimeBox(row.opens, "$name opens at ${row.opens}", state.problemWeekday == weekday, Modifier.testTag("opens-$weekday")) { picking = weekday to true }
                                Text("to", Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
                                TimeBox(row.closes, "$name closes at ${row.closes}", state.problemWeekday == weekday, Modifier.testTag("closes-$weekday")) { picking = weekday to false }
                            }
                        }
                    }
                }
                Hint("Switch a day off and it becomes a day off — no times are kept. Breaks and holidays are blocked separately from the diary.", Modifier.padding(top = 14.dp))
            }
        }
    }

    picking?.let { (weekday, isOpening) ->
        val row = state.rows[weekday] ?: DayRow()
        // A time of day, never an instant, so a clock change cannot move it.
        val (hour, minute) = (if (isOpening) row.opens else row.closes).split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrNull(0) ?: 9) to (it.getOrNull(1) ?: 0) }
        val picker = rememberTimePickerState(hour, minute, is24Hour = true)
        TimePickerDialog(
            onDismissRequest = { picking = null },
            title = { Text("${HoursResponse.dayName(weekday)} ${if (isOpening) "opens" else "closes"} at", style = WHType.Semi14, color = WHColors.Neutral800) },
            confirmButton = {
                WordsButton("Choose", {
                    val chosen = "%02d:%02d".format(picker.hour, picker.minute)
                    model.edit(weekday) { if (isOpening) it.copy(opens = chosen) else it.copy(closes = chosen) }
                    picking = null
                }, Modifier.testTag("chooseTime"))
            },
            dismissButton = { WordsButton("Cancel", { picking = null }, color = WHColors.Neutral700) },
        ) {
            TimePicker(picker, colors = TimePickerDefaults.colors(selectorColor = WHColors.Ink, timeSelectorSelectedContainerColor = WHColors.Well, timeSelectorSelectedContentColor = WHColors.Ink, clockDialColor = WHColors.Well))
        }
    }
}

@Composable
private fun TimeBox(time: String, says: String, isProblem: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.heightIn(min = 48.dp).width(84.dp).clip(shape).background(WHColors.Bg).border(1.dp, if (isProblem) WHColors.Accent else WHColors.Divider, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = says },
        contentAlignment = Alignment.Center,
    ) { Text(time, Modifier.clearAndSetSemantics { }, style = WHType.RowName, color = WHColors.Ink) }
}

// endregion
// region Booking rules, and deposits and cancellation

@Composable
private fun NumberFields(model: NumbersViewModel, state: NumbersState) {
    for (field in model.fields) {
        WHField(field.label, state.text[field.key].orEmpty(), { model.type(field.key, it) }, inputModifier = Modifier.testTag("field-${field.key}"), keyboardOptions = wholeNumbers, isProblem = state.problem?.field == field.key)
        field.hint?.let { Hint(it) }
    }
}

/** Booking rules (chairtime `app/(pro)/shop/rules`): the six numbers that shape every time offered to a client. */
@Composable
internal fun RulesScreen(app: AppModel, visit: Int, onBack: (() -> Unit)?) {
    val model: RulesViewModel = viewModel(key = "shop-rules") { RulesViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    SettingPage(
        "Booking rules", "rules", onBack, intro = "These shape every time offered to a client, so a change here changes what the booking page shows straight away.",
        save = "Save rules".takeIf { state.isLoaded }, onSave = { model.save() }, isSaving = state.isSaving, justSaved = state.justSaved,
    ) {
        Failure(state.failure, !state.isLoaded) { model.load() }
        Problem(state.problem, "rulesProblem")
        if (state.isLoaded) Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { NumberFields(model, state) }
        else if (state.failure == null) Loading("the rules", 6, 56)
    }
}

/** Deposits and cancellation (chairtime `app/(pro)/shop/policy`). */
@Composable
internal fun PolicyScreen(app: AppModel, visit: Int, onBack: (() -> Unit)?) {
    val model: PolicyViewModel = viewModel(key = "shop-policy") { PolicyViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    SettingPage("Deposits and cancellation", "policy", onBack, save = "Save policy".takeIf { state.isLoaded }, onSave = { model.save() }, isSaving = state.isSaving, justSaved = state.justSaved) {
        Failure(state.failure, !state.isLoaded) { model.load() }
        Problem(state.problem, "policyProblem")
        if (state.isLoaded) Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WHField("Standard deposit", state.text[PolicyViewModel.DEPOSIT].orEmpty(), { model.type(PolicyViewModel.DEPOSIT, it) }, inputModifier = Modifier.testTag("field-standardDeposit"),
                keyboardOptions = amounts, prefix = "£", isProblem = state.problem?.field == PolicyViewModel.DEPOSIT)
            Hint("Individual services can ask for more or less.")
            NumberFields(model, state)
            // The generated line, as the web's placeholder: what a client is told when this is left blank.
            WHField("What clients are told", state.text[PolicyViewModel.WORDING].orEmpty(), { model.type(PolicyViewModel.WORDING, it) }, Modifier.padding(top = 8.dp), inputModifier = Modifier.testTag("field-clientFacingWording"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), singleLine = false, isProblem = state.problem?.field == PolicyViewModel.WORDING)
            if (state.text[PolicyViewModel.WORDING].isNullOrBlank() && state.defaultWording.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Left blank, clients are told:", style = WHType.Meta, color = WHColors.Neutral700)
                    Text(state.defaultWording, Modifier.testTag("defaultWording"), style = WHType.Meta, color = WHColors.Neutral800)
                }
            }
            Hint("Shown word for word above the pay button. Leave it blank and clients are told the standard line, kept in step with the hours you set — the deposit sentence appears only when a deposit was taken. Changing it starts a new version, so a booking taken last month is still judged against what was on screen then.")
        } else if (state.failure == null) Loading("the policy", 5, 56)
    }
}

// endregion
// region Reminders

/** Reminders (chairtime `app/(pro)/shop/reminders`): up to three emails before an appointment. */
@Composable
internal fun RemindersScreen(app: AppModel, visit: Int, onBack: (() -> Unit)?) {
    val model: RemindersViewModel = viewModel(key = "shop-reminders") { RemindersViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response
    SettingPage(
        "Reminders", "reminders", onBack,
        intro = "Up to three emails before an appointment. Each one names the date and time and carries a link to change or cancel — a client who cannot come is worth far more to you as a free slot than as a no-show.",
        save = "Save reminders".takeIf { response != null }, onSave = { model.save() }, isSaving = state.isSaving, justSaved = state.justSaved,
    ) {
        Failure(state.failure, response == null) { model.load() }
        if (response == null) { if (state.failure == null) Loading("the reminders", 3, 120); return@SettingPage }
        if (!response.anyOn) NoteCard("Nothing is going out at the moment. Switch one on and it starts with the next appointment that comes due.", Modifier.padding(top = 16.dp))
        Problem(state.problem, "remindersProblem")
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.rows.forEachIndexed { i, row ->
                val n = i + 1
                WHCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow("Reminder $n", row.isOn, { on -> model.edit(i) { it.copy(isOn = on) } }, Modifier.testTag("reminder-$n"), hint = row.says)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WHField("How long", row.value, { v -> model.edit(i) { it.copy(value = v.filter(Char::isDigit).take(4)) } }, Modifier.width(116.dp), inputModifier = Modifier.testTag("field-value$n"),
                                keyboardOptions = wholeNumbers, isProblem = state.problem?.field == "slots.$i.minutesBefore")
                            WHSegmented(ReminderWords.Unit.entries.map { it to it.label }, row.unit, { u -> model.edit(i) { it.copy(unit = u) } })
                        }
                    }
                }
            }
        }
        Panel("Last 30 days", Modifier.padding(top = 20.dp)) {
            PanelRow {
                Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(response.recentLabel, style = WHType.Semi14, color = WHColors.Ink)
                    if (response.recent.failed > 0) Text("${response.recent.failed} could not be delivered — usually an address with a typo in it.", style = WHType.Meta, color = WHColors.Accent)
                    Text("A reminder is recorded before it is sent, so nobody ever gets the same one twice. The trade is that one which fails is not tried again.", style = WHType.Meta, color = WHColors.Neutral700)
                }
            }
        }
    }
}

// endregion
