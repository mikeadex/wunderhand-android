package com.wunderhand.app.features.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.app.features.shell.EditorSheet
import com.wunderhand.core.Employment
import com.wunderhand.core.TeamPersonResponse
import com.wunderhand.core.TeamResponse
import com.wunderhand.core.TeamWords
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.EmptyNote
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.Fact
import com.wunderhand.design.Hint
import com.wunderhand.design.Panel
import com.wunderhand.design.PanelRow
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.TagTone
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHChip
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHTag
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall
import java.util.UUID

/** A small label — a seat, a badge — in the web's pill shape. */
@Composable
internal fun Pill(text: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    if (accent) WHTag(text, TagTone.Accent, modifier)
    else Text(text, modifier.clip(RoundedCornerShape(6.dp)).background(WHColors.Well).padding(horizontal = 8.dp, vertical = 3.dp), style = WHType.Tag, color = WHColors.Neutral700)
}

// region The team

/** The team (chairtime `app/(pro)/shop/team`): who is on it, who logs in, who is in the diary and who sees the money. */
@Composable
internal fun TeamScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: TeamViewModel = viewModel(key = "shop-team") { TeamViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response

    SettingPage("Team", "team", onBack, intro = response?.summary, save = "Add someone".takeIf { response != null && shop.isOwner }, onSave = { model.adding(true) }) {
        Failure(state.failure, response == null) { model.load() }
        when {
            response == null -> if (state.failure == null) Loading("the team", 5, 54)
            response.people.isEmpty() -> EmptyNote("Nobody on the team yet", "Add the people who take bookings. Each one gets their own column in the diary, their own hours, and their own services.", Modifier.padding(top = 20.dp))
            else -> WHCard(Modifier.padding(top = 20.dp)) {
                response.people.forEachIndexed { index, person ->
                    if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                    PersonRow(person) { shop.push(ShopRoute.Person(person.id)) }
                }
            }
        }
    }

    if (state.isAdding) EditorSheet(onDismiss = { model.adding(false) }) {
        val form: TeamPersonFormViewModel = viewModel(key = "person-form-${rememberSaveable { UUID.randomUUID().toString() }}") { TeamPersonFormViewModel(null, app.client, app::handle, createSavedStateHandle()) }
        // Straight to their page: inviting them to log in is one tap from there.
        TeamPersonForm(form, onSaved = { made -> model.adding(false); model.load(); shop.push(ShopRoute.Person(made.person.id)) }, onClose = { model.adding(false) })
    }
}

@Composable
private fun PersonRow(person: TeamResponse.Person, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = listOf(person.name, person.statusLabel, person.hint).filter { it.isNotEmpty() }.joinToString(", ") }.testTag("teamPerson"),
    ) {
        Row(Modifier.weight(1f).clearAndSetSemantics { }.padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.name, style = WHType.RowName, color = WHColors.Ink)
                if (person.hint.isNotEmpty()) Text(person.hint, style = WHType.Meta, color = WHColors.Neutral700)
            }
            Pill(person.statusLabel, accent = person.isInvited)
            WHIcon(WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500)
        }
    }
}

// endregion
// region One person

private sealed interface Asking {
    data class Owner(val on: Boolean) : Asking
    data object Remove : Asking
}

/**
 * One person (chairtime `app/(pro)/shop/team/[id]`). Who they are and where
 * they work is a form. Whether they can log in, and whether they see the
 * shop's money, are acts, each asked about first. Nothing here names a price:
 * an invitation says it adds a seat to the plan, and what a seat costs is on the web.
 */
@Composable
internal fun TeamPersonScreen(id: String, app: AppModel, visit: Int, onBack: (() -> Unit)?) {
    val model: TeamPersonViewModel = viewModel(key = "shop-person") { TeamPersonViewModel(app.client, app::handle) }
    LaunchedEffect(id, visit) { model.enter(id, visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val r = state.response
    var asking by remember { mutableStateOf<Asking?>(null) }

    SettingPage(r?.person?.name ?: " ", "person", onBack, eyebrow = "Team") {
        if (r != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(r.person.statusLabel, accent = r.person.accountStatus == "invited", Modifier.testTag("personStatus"))
            if (r.person.isOwner) Pill("Owner · sees the money")
        }
        Failure(state.failure, r == null) { model.load() }
        state.notice?.let {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("personNotice"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WHIcon(WHIcons.Check, size = 16.dp, tint = WHColors.Ink, modifier = Modifier.padding(top = 2.dp))
                Text(it, style = WHType.Body, color = WHColors.Ink)
            }
        }
        state.problem?.let { com.wunderhand.design.NoteCard(it, Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("personProblem")) }

        if (r == null) { if (state.failure == null) Loading("them", 3, 110); return@SettingPage }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Who they are", action = "Change".takeIf { r.viewerIsOwner }, actionTag = "changePerson", onAction = { model.editing(true) }) {
                Fact("Role", r.person.roleTitle ?: "—")
                Fact("How they work", r.person.employmentLabel)
                Fact("Diary", if (r.person.isBookable) "Takes bookings" else "Not in the diary")
                Fact("Works at", r.worksAt)
            }

            val login = TeamWords.login(r)
            Panel(TeamWords.loginTitle(r.person)) {
                PanelRow(vertical = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(login.text, style = WHType.Body, color = WHColors.Neutral800)
                        val sentTo = r.person.inviteEmail
                        if (r.person.accountStatus == "invited" && sentTo != null) Text("Sent to $sentTo. Waiting for them to accept.", Modifier.testTag("inviteSentTo"), style = WHType.Semi14, color = WHColors.Accent)
                        if (login.canInvite) {
                            if (!r.person.isComingBack) WHField(
                                "Their email", state.email, model::typeEmail, inputModifier = Modifier.testTag("inviteEmail").semantics { contentType = ContentType.EmailAddress },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            )
                            PrimaryButton(TeamWords.inviteButton(r.person), { focus.clearFocus(); model.invite() }, Modifier.testTag("sendInvite"), loading = state.isBusy)
                        }
                    }
                }
            }

            if (r.viewerIsOwner) Panel("The shop’s money") {
                PanelRow(vertical = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(TeamWords.money(r), style = WHType.Body, color = WHColors.Neutral800)
                        val label = TeamWords.moneyButton(r)
                        if (label != null) Box { WordsButton(label, { asking = Asking.Owner(!r.person.isOwner) }, Modifier.testTag("ownerButton"), color = if (r.person.isOwner) WHColors.Accent else WHColors.Ink, enabled = !state.isBusy) }
                        else Text(TeamWords.needsLoginFirst(r.person), style = WHType.Meta, color = WHColors.Neutral700)
                    }
                }
            }

            if (r.viewerIsOwner && !r.person.isSuspended) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box { WordsButton("Remove from the team", { asking = Asking.Remove }, Modifier.testTag("removePerson"), color = WHColors.Accent, enabled = !state.isBusy) }
                Hint(TeamWords.REMOVING)
            }
        }
    }

    if (state.isEditing && r != null) EditorSheet(onDismiss = { model.editing(false) }) {
        val form: TeamPersonFormViewModel = viewModel(key = "person-form-${rememberSaveable { UUID.randomUUID().toString() }}") { TeamPersonFormViewModel(r, app.client, app::handle, createSavedStateHandle()) }
        TeamPersonForm(form, onSaved = model::edited, onClose = { model.editing(false) })
    }

    val which = asking
    if (which != null && r != null) when (which) {
        is Asking.Owner -> ConfirmDialog(
            title = if (which.on) "Let ${r.person.name} see the shop’s money?" else "${TeamWords.moneyButton(r).orEmpty()}?",
            message = if (which.on) "They will see everyone’s takings on the Money tab." else if (r.isYou) TeamWords.STANDING_DOWN else "They will see only their own takings.",
            confirm = if (which.on) "Let them see it" else "Stop", keep = "Leave it as it is", onConfirm = { model.setOwner(which.on) }, onDismiss = { asking = null },
        )
        Asking.Remove -> ConfirmDialog(
            title = "Remove ${r.person.name} from the team?", message = TeamWords.REMOVING, confirm = "Remove", keep = "Keep them",
            onConfirm = { model.remove() }, onDismiss = { asking = null },
        )
    }
}

// endregion
// region The form

/** Somebody new, or somebody being changed (chairtime `components/shop/StaffForm.tsx`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeamPersonForm(form: TeamPersonFormViewModel, onSaved: (TeamPersonResponse) -> Unit, onClose: () -> Unit) {
    val state by form.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    EditorPage(
        title = form.existing?.person?.name ?: "Add someone", onClose = onClose, tag = "personForm",
        footer = { PrimaryButton(if (form.existing == null) "Add to the team" else "Save", { focus.clearFocus(); form.save(onSaved) }, Modifier.testTag("savePerson"), loading = state.isSaving) },
    ) {
        Problem(state.problem, "personFormProblem")
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WHField("Name", state.name, { v -> form.edit { it.copy(name = v) } }, inputModifier = Modifier.testTag("personName").semantics { contentType = ContentType.PersonFullName },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), isProblem = state.problem?.field == "name")
            WHField("Role", state.role, { v -> form.edit { it.copy(role = v) } }, inputModifier = Modifier.testTag("personRole"), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), placeholder = "Barber, Colourist, Front desk")

            Eyebrow("How they work", Modifier.padding(top = 10.dp, start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                for (e in Employment.entries) WHChip(e.label, state.employment == e, { form.edit { it.copy(employment = e) } }, Modifier.testTag("employment-${e.raw}"))
            }
            WHCard(Modifier.padding(top = 6.dp)) {
                SwitchRow("Appears in the diary", state.isBookable, { v -> form.edit { it.copy(isBookable = v) } }, Modifier.padding(16.dp).testTag("personBookable"), hint = "Turn off for office staff who never take appointments.")
            }
            if (state.outlets.isNotEmpty()) {
                Eyebrow("Works at", Modifier.padding(top = 10.dp, start = 4.dp))
                WHCard {
                    state.outlets.forEachIndexed { index, outlet ->
                        if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                        SwitchRow(outlet.name, outlet.id in state.outletIds, { on -> form.edit { it.copy(outletIds = if (on) it.outletIds + outlet.id else it.outletIds - outlet.id) } },
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("worksAt-$index"), hint = outlet.place.ifEmpty { null })
                    }
                }
                Hint(TeamWords.SEATS)
            }
        }
    }
}

// endregion
