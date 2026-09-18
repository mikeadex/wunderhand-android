package com.wunderhand.app.features.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.core.ExtrasResponse
import com.wunderhand.core.MenuServiceResponse
import com.wunderhand.core.Pence
import com.wunderhand.core.StepDraft
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.DashedButton
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.Hint
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SavedLine
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall

// Done on the number pad puts it away: there is no other key on it that would.
private val money = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
private val number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)

/** A small square to press inside a row: move up, move down, remove, change. 48dp to the touch. */
@Composable
private fun IconPress(icon: WHIcons, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        WHIcon(icon, size = 18.dp, tint = if (enabled) WHColors.Neutral700 else WHColors.Neutral500.copy(alpha = 0.5f))
    }
}

// region How the time is used

/**
 * How the time is used (chairtime `app/(pro)/menu/[id]/segments`).
 *
 * The screen that decides whether the develop-gap model was worth the trouble:
 * "twenty minutes on, thirty five off, thirty five on", where the middle is
 * time somebody else can be booked into.
 */
@Composable
fun StepsEditorScreen(model: StepsViewModel, onSaved: (MenuServiceResponse) -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    EditorPage(
        title = "How the time is used", onClose = onClose, tag = "steps",
        intro = "Break ${model.service.service.name} into steps. Turn off “you are busy” for any step where the client is here but you are not — colour developing, a mask, a break in a long sitting.",
        accentIntro = "Those minutes stay on the client’s appointment but come back onto your diary, so someone else can be booked into them.",
        footer = { PrimaryButton("Save the steps", { focus.clearFocus(); model.save(onSaved) }, Modifier.testTag("saveSteps"), loading = state.isSaving) },
    ) {
        state.problem?.let { NoteCard(it.text, Modifier.padding(top = 16.dp).testTag("stepsProblem")) }

        StepDraft.summary(state.rows)?.let { held ->
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.padding(top = 18.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(16.dp).semantics(mergeDescendants = true) { }.testTag("stepsSummary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepsBar(StepDraft.preview(state.rows))
                Text(held, style = WHType.Body, color = WHColors.Ink)
                StepDraft.sellable(state.rows)?.let { Text(it, style = WHType.Semi14, color = WHColors.Accent) }
            }
        }

        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.rows.forEachIndexed { index, row -> StepRow(model, index, row, count = state.rows.size, isProblem = state.problem?.field == "steps.$index.minutes") }
        }
        DashedButton("Add a step", model::add, Modifier.padding(top = 10.dp).testTag("addStep"), enabled = state.rows.size < StepsViewModel.MOST)
        Hint("An appointment already booked keeps the steps it was booked with.", Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun StepRow(model: StepsViewModel, index: Int, row: StepDraft, count: Int, isProblem: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    val n = index + 1
    Column(
        Modifier.fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).border(1.dp, if (row.staffBusy) WHColors.Surface else WHColors.Accent300, shape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 10.dp).testTag("step-$index"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(WHColors.Ink).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Text("$n", style = WHType.Tag.copy(fontWeight = FontWeight.Bold), color = WHColors.Bg)
            }
            Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (row.label.isEmpty()) Text(if (index == 0) "Apply" else "What happens", Modifier.clearAndSetSemantics { }, style = WHType.RowName, color = WHColors.Neutral500, maxLines = 1)
                BasicTextField(
                    row.label, { v -> model.edit(index) { it.copy(label = v) } },
                    Modifier.fillMaxWidth().padding(vertical = 13.dp).semantics { contentDescription = "Step $n name" }.testTag("stepLabel-$index"),
                    textStyle = WHType.RowName.copy(color = WHColors.Ink), singleLine = true, cursorBrush = SolidColor(WHColors.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
            IconPress(WHIcons.ChevronUp, "Move step $n up", { model.move(index, -1) }, enabled = index > 0)
            IconPress(WHIcons.ChevronDown, "Move step $n down", { model.move(index, 1) }, enabled = index < count - 1)
            IconPress(WHIcons.Trash, "Remove step $n", { model.remove(index) }, Modifier.testTag("removeStep-$index"), enabled = count > 1)
        }
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val box = RoundedCornerShape(8.dp)
            BasicTextField(
                row.minutes, { v -> model.edit(index) { it.copy(minutes = v.filter(Char::isDigit).take(4)) } },
                Modifier.width(72.dp).clip(box).background(WHColors.Bg).border(1.dp, if (isProblem) WHColors.Accent else WHColors.Divider, box).padding(horizontal = 10.dp, vertical = 13.dp)
                    .semantics { contentDescription = "Step $n minutes" }.testTag("stepMinutes-$index"),
                textStyle = WHType.RowName.copy(color = WHColors.Ink, textAlign = TextAlign.End), singleLine = true, cursorBrush = SolidColor(WHColors.Accent), keyboardOptions = number,
            )
            Text("min", Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
            SwitchRow("You are busy", row.staffBusy, { v -> model.edit(index) { it.copy(staffBusy = v) } }, Modifier.weight(1f).padding(start = 12.dp).testTag("stepBusy-$index"))
        }
    }
}

// endregion
// region Who does it

/** Who does this? A price left blank is the service's own — shown as the placeholder, so blank visibly means "the standard". */
@Composable
fun PerformersEditorScreen(model: PerformersViewModel, currency: String, onSaved: (MenuServiceResponse) -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val service = model.service.service
    val standard = service.pricePence?.formatted(currency) ?: "price"
    val rows = state.rows
    EditorPage(
        title = if (model.isOwner) "Who does this?" else "Your price", onClose = onClose, tag = "performers",
        intro = if (model.isOwner) "A service nobody is assigned to cannot be booked. Leave a price blank to use the standard $standard."
        else "What you charge for ${service.name}. Leave it blank to charge the standard $standard. Who does this service is the owner's to set.",
        footer = if (!rows.isNullOrEmpty()) ({ PrimaryButton("Save", { focus.clearFocus(); model.save(onSaved) }, Modifier.testTag("savePerformers"), loading = state.isSaving) }) else null,
    ) {
        state.failure?.let { NoteCard(it, Modifier.padding(top = 16.dp)); WordsButton("Try again", { model.load() }) }
        state.problem?.let { NoteCard(it.text, Modifier.padding(top = 16.dp).testTag("performersProblem")) }
        when {
            rows == null -> if (state.failure == null) Column(Modifier.padding(top = 20.dp).semantics { contentDescription = "Loading the team" }, verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(4) { SkeletonBlock(56.dp, radius = 10.dp) } }
            rows.isEmpty() -> Text("Nobody on the team takes bookings yet. Add someone in Shop first.", Modifier.padding(top = 20.dp), style = WHType.Body, color = WHColors.Neutral700)
            else -> WHCard(Modifier.padding(top = 20.dp)) {
                rows.forEachIndexed { index, person ->
                    if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (model.isOwner) SwitchRow(person.name, person.isOn, { on -> model.edit(person.id) { it.copy(isOn = on) } }, Modifier.testTag("performer-$index"), hint = person.roleTitle?.takeIf { it.isNotBlank() })
                        // No switch: taking themselves off a service is the owner's.
                        else Text(person.name, Modifier.padding(top = 8.dp).testTag("performer-$index"), style = WHType.RowName, color = WHColors.Ink)
                        if (person.isOn) WHField("${person.name}’s price", person.price, { v -> model.edit(person.id) { it.copy(price = v) } }, Modifier.padding(bottom = 6.dp),
                            inputModifier = Modifier.testTag("performerPrice-$index"), keyboardOptions = money, prefix = "£", placeholder = model.standardPrice ?: "0", isProblem = state.problem?.field == "price_${person.id}")
                    }
                }
            }
        }
    }
}

// endregion
// region Extras

/** Extras (chairtime `app/(pro)/menu/[id]/addons`). The web can make and retire one; here one can be changed too. */
@Composable
fun ExtrasEditorScreen(model: ExtrasViewModel, currency: String, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val form = state.form
    if (form != null) {
        // The system's back leaves the one extra, not the whole sheet.
        BackHandler { model.closeForm() }
        ExtraForm(model, form, state.isSaving)
        return
    }
    val extras = state.extras
    EditorPage(
        title = "Extras", onClose = onClose, tag = "extras",
        intro = "Things a client can add to ${model.service.service.name} — a beard trim, a treatment, nail art.",
        accentIntro = "An extra that takes time makes the appointment longer, so the diary stops offering slots it no longer fits into.",
        footer = if (!extras.isNullOrEmpty()) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.justSaved) SavedLine("Saved.")
                PrimaryButton("Save which are offered", { model.saveLinks() }, Modifier.weight(1f).testTag("saveExtraLinks"), loading = state.isSaving)
            }
        }) else null,
    ) {
        state.failure?.let { NoteCard(it, Modifier.padding(top = 16.dp)); WordsButton("Try again", { model.load() }) }
        state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp)) }
        if (extras == null) {
            if (state.failure == null) Column(Modifier.padding(top = 24.dp).semantics { contentDescription = "Loading the extras" }, verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(3) { SkeletonBlock(56.dp, radius = 10.dp) } }
            return@EditorPage
        }
        if (extras.isEmpty()) {
            Text("Nothing is offered alongside this service yet. An extra is a small add-on a client can take with it — a treatment, a finish, a drink — priced on its own.", Modifier.padding(top = 24.dp), style = WHType.Body, color = WHColors.Neutral700)
        } else {
            Eyebrow("Offered with this service", Modifier.padding(top = 24.dp, start = 4.dp))
            WHCard(Modifier.padding(top = 8.dp)) {
                extras.forEachIndexed { index, extra ->
                    if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SwitchRow(extra.name, extra.id in state.offered, { on -> model.offer(extra.id, on) }, Modifier.weight(1f).padding(vertical = 10.dp).testTag("extraOffered-$index"), hint = extra.line(currency))
                        IconPress(WHIcons.Pencil, "Change ${extra.name}", { model.editing(extra) }, Modifier.testTag("changeExtra-$index"))
                    }
                }
            }
        }
        DashedButton("Add a new one", { model.editing(null) }, Modifier.padding(top = if (extras.isEmpty()) 24.dp else 12.dp).testTag("addExtra"))
    }
}

@Composable
private fun ExtraForm(model: ExtrasViewModel, form: ExtraFormState, isSaving: Boolean) {
    val focus = LocalFocusManager.current
    val existing: ExtrasResponse.Extra? = form.existing
    var confirmingRetire by remember { mutableStateOf(false) }
    EditorPage(
        title = if (existing == null) "Add a new one" else "Change ${existing.name}", onClose = model::closeForm, tag = "extraForm", back = true,
        footer = { PrimaryButton(if (existing == null) "Add the extra" else "Save", { focus.clearFocus(); model.saveExtra() }, Modifier.testTag("saveExtra"), loading = isSaving) },
    ) {
        form.problem?.let { NoteCard(it.text, Modifier.padding(top = 16.dp).testTag("extraProblem")) }
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WHField("Name", form.name, { v -> model.type { it.copy(name = v) } }, inputModifier = Modifier.testTag("extraName"), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), placeholder = "Beard trim", isProblem = form.problem?.field == "name")
            WHField("Price", form.price, { v -> model.type { it.copy(price = v) } }, inputModifier = Modifier.testTag("extraPrice"), keyboardOptions = money, prefix = "£", placeholder = "10", isProblem = form.problem?.field == "pricePence")
            WHField("Extra time (minutes)", form.minutes, { v -> model.type { it.copy(minutes = v) } }, inputModifier = Modifier.testTag("extraMinutes"), keyboardOptions = number, isProblem = form.problem?.field == "minutes")
            Hint("Zero for anything that costs money but no time, like an aftercare product.")
        }
        if (existing != null) Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.alpha(if (isSaving) 0.5f else 1f)) { WordsButton("Retire ${existing.name}", { confirmingRetire = true }, Modifier.testTag("retireExtra"), color = WHColors.Accent, enabled = !isSaving) }
            Hint("It stops being offered. Past bookings keep the name and price they were charged, so your takings still add up.")
        }
    }
    if (confirmingRetire && existing != null) {
        ConfirmDialog(
            title = "Retire ${existing.name}?", message = "It comes off every service that offers it, not only this one.", confirm = "Retire",
            onConfirm = { confirmingRetire = false; model.retire() }, onDismiss = { confirmingRetire = false },
        )
    }
}

// endregion
