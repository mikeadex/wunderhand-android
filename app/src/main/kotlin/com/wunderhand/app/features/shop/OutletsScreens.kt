package com.wunderhand.app.features.shop

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.app.features.shell.EditorSheet
import com.wunderhand.core.OutletDraft
import com.wunderhand.core.OutletWords
import com.wunderhand.core.OutletsResponse
import com.wunderhand.design.ChoiceField
import com.wunderhand.design.DashedButton
import com.wunderhand.design.EmptyNote
import com.wunderhand.design.FormGroup
import com.wunderhand.design.Hint
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType

/**
 * Outlets (chairtime `app/(pro)/shop/outlets`): where the shop is, and how far
 * it travels. Adding one is an owner's, because an outlet goes on the shop's
 * plan — which the app says, and never prices.
 */
@Composable
internal fun OutletsScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: OutletsViewModel = viewModel(key = "shop-outlets") { OutletsViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response

    SettingPage("Outlets", "outlets", onBack, save = "Add an outlet".takeIf { response != null && shop.isOwner }, onSave = model::add) {
        Failure(state.failure, response == null) { model.load() }
        state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp)) }
        when {
            response == null -> if (state.failure == null) Loading("the outlets", 2, 96)
            response.outlets.isEmpty() -> EmptyNote("No outlets yet", "An outlet is a place you work: the shop itself, a chair you rent, or the road for at-the-client visits. Hours and travel are set on each one.", Modifier.padding(top = 20.dp))
            else -> Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (outlet in response.outlets) OutletCard(outlet, isOpening = state.opening == outlet.id, enabled = state.opening == null) { model.open(outlet.id) }
            }
        }
    }

    if (state.isEditing) EditorSheet(onDismiss = { model.closeForm(savedOne = false) }) {
        val form: OutletFormViewModel = viewModel(key = "outlet-form-${state.formVisit}") { OutletFormViewModel(state.editing, app.client, app::handle, createSavedStateHandle()) }
        OutletForm(form, onSaved = { model.closeForm(savedOne = true) }, onClose = { model.closeForm(savedOne = false) })
    }
}

@Composable
private fun OutletCard(outlet: OutletsResponse.Outlet, isOpening: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    val where = "${outlet.travelLabel} · ${outlet.timezone.replace('_', ' ')}"
    WHCard(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(outlet.name, "Street private".takeIf { outlet.addressPrivate }, outlet.addressLine, where).joinToString(", ") }.testTag("outletCard"),
    ) {
        Column(Modifier.padding(16.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(outlet.name, Modifier.weight(1f), style = WHType.RowName, color = WHColors.Ink)
                if (outlet.addressPrivate) Pill("Street private")
                if (isOpening) CircularProgressIndicator(Modifier.size(16.dp), color = WHColors.Ink, strokeWidth = 2.dp)
                else WHIcon(WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500)
            }
            Text(outlet.addressLine, style = WHType.Body, color = WHColors.Neutral800)
            Text(where, style = WHType.Meta, color = WHColors.Neutral700)
        }
    }
}

/** A new outlet, or one being changed (chairtime `components/shop/LocationForm.tsx`). */
@Composable
internal fun OutletForm(form: OutletFormViewModel, onSaved: () -> Unit, onClose: () -> Unit) {
    val state by form.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val focus = LocalFocusManager.current
    fun refused(field: String) = state.problem?.field == field
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)

    EditorPage(
        title = form.existing?.outlet?.name ?: "New outlet", onClose = onClose, tag = "outletForm", intro = OutletWords.ON_THE_PLAN.takeIf { form.existing == null },
        footer = { PrimaryButton(if (form.existing == null) "Add outlet" else "Save outlet", { focus.clearFocus(); form.save { onSaved() } }, Modifier.testTag("saveOutlet"), loading = state.isSaving) },
    ) {
        Problem(state.problem, "outletProblem")
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WHField("Name", draft.name, { v -> form.edit { it.copy(name = v) } }, inputModifier = Modifier.testTag("outletName"), keyboardOptions = words, placeholder = "Hackney Road, or Covers south London", isProblem = refused("name"))
            WHField("Address", draft.addressLine1, { v -> form.edit { it.copy(addressLine1 = v) } }, inputModifier = Modifier.semantics { contentType = ContentType.AddressStreet }, keyboardOptions = words, isProblem = refused("addressLine1"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WHField("Town or city", draft.city, { v -> form.edit { it.copy(city = v) } }, Modifier.weight(1f), inputModifier = Modifier.semantics { contentType = ContentType.AddressLocality }, keyboardOptions = words, isProblem = refused("city"))
                WHField("Postcode", draft.postcode, { v -> form.edit { it.copy(postcode = v) } }, Modifier.width(140.dp), inputModifier = Modifier.semantics { contentType = ContentType.PostalCode },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false), isProblem = refused("postcode"))
            }
            ChoiceField("Timezone", null, draft.timezoneChoices, draft.timezone, { v -> form.edit { it.copy(timezone = v ?: "Europe/London") } }, isProblem = refused("timezone"))
            Hint(OutletWords.TIMEZONE_HINT)
            WHCard(Modifier.padding(top = 6.dp)) {
                SwitchRow(OutletWords.PRIVATE_LABEL, draft.addressPrivate, { v -> form.edit { it.copy(addressPrivate = v) } }, Modifier.padding(16.dp).testTag("outletPrivate"), hint = OutletWords.PRIVATE_HINT)
            }

            FormGroup("Travelling to clients", Modifier.padding(top = 16.dp)) {
                Text(OutletWords.TRAVELLING, Modifier.padding(horizontal = 4.dp), style = WHType.Body, color = WHColors.Neutral800)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WHField("How far you travel (miles)", draft.servesRadiusMiles, { v -> form.edit { it.copy(servesRadiusMiles = v) } }, Modifier.weight(1f), keyboardOptions = wholeNumbers, placeholder = "10", isProblem = refused("servesRadiusMiles"))
                    WHField("Travel time to allow (minutes)", draft.defaultTravelMinutes, { v -> form.edit { it.copy(defaultTravelMinutes = v) } }, Modifier.weight(1f), keyboardOptions = wholeNumbers, placeholder = "30", isProblem = refused("defaultTravelMinutes"))
                }
                Hint(OutletWords.TRAVEL_TIME_HINT)
            }

            FormGroup("Charging for the journey", Modifier.padding(top = 16.dp)) {
                Text(OutletWords.CHARGING, Modifier.padding(horizontal = 4.dp), style = WHType.Body, color = WHColors.Neutral800)
                draft.bands.forEachIndexed { index, band -> BandRow(form, index, band, milesRefused = refused("band.$index.miles"), feeRefused = refused("band.$index.fee")) }
                if (draft.bands.size < OutletDraft.MAX_BANDS) DashedButton("Add a band", form::addBand, Modifier.testTag("addBand"))
            }
        }
    }
}

@Composable
private fun BandRow(form: OutletFormViewModel, index: Int, band: OutletDraft.BandDraft, milesRefused: Boolean, feeRefused: Boolean) {
    val n = index + 1
    val box = RoundedCornerShape(8.dp)
    @Composable
    fun Typed(value: String, says: String, refused: Boolean, width: Int, options: KeyboardOptions, tag: String, prefix: String? = null, placeholder: String, onChange: (String) -> Unit) {
        Row(Modifier.width(width.dp).clip(box).background(WHColors.Surface).border(1.dp, if (refused) WHColors.Accent else WHColors.Divider, box).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (prefix != null) Text(prefix, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Neutral700)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Neutral500)
                BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics { contentDescription = says }.testTag(tag), textStyle = WHType.FieldValue.copy(color = WHColors.Ink), singleLine = true, cursorBrush = SolidColor(WHColors.Accent), keyboardOptions = options)
            }
        }
    }
    Row(Modifier.testTag("band-$index"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Up to", Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
        Typed(band.miles, "Band $n up to miles", milesRefused, 64, wholeNumbers, "bandMiles-$index", placeholder = if (index == 0) "3" else "") { v -> form.editBand(index) { it.copy(miles = v.filter(Char::isDigit).take(3)) } }
        Text("miles", Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
        Typed(band.fee, "Band $n fee", feeRefused, 96, amounts, "bandFee-$index", prefix = "£", placeholder = if (index == 0) "0" else "") { v -> form.editBand(index) { it.copy(fee = v) } }
        Box(Modifier.size(48.dp).clip(box).clickable(role = Role.Button) { form.removeBand(index) }.semantics { contentDescription = "Remove band $n" }, contentAlignment = Alignment.Center) {
            WHIcon(WHIcons.Trash, size = 18.dp, tint = WHColors.Neutral700)
        }
    }
}
