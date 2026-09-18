package com.wunderhand.app.features.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.core.DurationMode
import com.wunderhand.core.LocationMode
import com.wunderhand.core.MenuServiceResponse
import com.wunderhand.core.PricingMode
import com.wunderhand.design.ChoiceField
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.FormGroup
import com.wunderhand.design.Hint
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHSegmented
import com.wunderhand.design.WordsButton

// Done on the number pad puts it away: there is no other key on it that would.
private val money = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
private val number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
private val sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

/**
 * A new service, or one being changed (chairtime `components/menu/ServiceForm.tsx`).
 *
 * A service is where this trade's awkwardness lives — a fixed-price cut, a
 * tint with a gap in the middle, a sitting quoted by the hour behind a
 * consultation and an age check are all this one form. The web shows every
 * field whatever the mode and explains in the hints; a phone can show a field
 * only when it means something, so an hourly rate appears for "by the hour"
 * and a range's limits for "a range". The words are the web's.
 */
@Composable
fun ServiceFormScreen(form: ServiceFormViewModel, onSaved: (MenuServiceResponse) -> Unit, onArchived: () -> Unit, onClose: () -> Unit) {
    val state by form.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val existing = form.existing
    val focus = LocalFocusManager.current
    var confirmingArchive by remember { mutableStateOf(false) }
    fun refused(field: String) = state.problem?.field == field

    EditorPage(
        title = if (existing == null) "New service" else "Edit service", onClose = onClose, tag = "serviceForm",
        intro = "Start with a name and a price. It gets an hour of time to begin with, which you can shape afterwards.".takeIf { existing == null },
        footer = {
            PrimaryButton(if (existing == null) "Create service" else "Save service", { focus.clearFocus(); form.save(onSaved) }, Modifier.testTag("saveService"), enabled = !state.isArchiving, loading = state.isSaving)
        },
    ) {
        state.problem?.let { NoteCard(it.text, Modifier.padding(top = 16.dp).testTag("serviceFormProblem")) }

        FormGroup("What it is", Modifier.padding(top = 22.dp)) {
            WHField("Name", draft.name, { v -> form.edit { it.copy(name = v) } }, inputModifier = Modifier.testTag("serviceNameField"), keyboardOptions = sentences, isProblem = refused("name"))
            WHField("Description", draft.description, { v -> form.edit { it.copy(description = v) } }, singleLine = false, keyboardOptions = sentences)
            Hint("Shown to clients on the booking page.")
            val categories = state.options?.categories.orEmpty()
            if (categories.isNotEmpty()) ChoiceField("Category", "None", categories.map { it.id to it.name }, draft.categoryId, { v -> form.edit { it.copy(categoryId = v) } }, isProblem = refused("categoryId"))
            WHField(if (categories.isEmpty()) "Category" else "Or a new category", draft.newCategory, { v -> form.edit { it.copy(newCategory = v) } },
                inputModifier = Modifier.testTag("serviceNewCategory"), keyboardOptions = sentences, placeholder = "Hair, Colour, Barber, Nails")
            Hint(if (categories.isEmpty()) "Groups services on your booking page. Leave blank if you only do one kind of thing." else "Fill this in to create a new one instead of picking above.")
        }

        FormGroup("What it costs", Modifier.padding(top = 26.dp)) {
            WHSegmented(PricingMode.entries.map { it to it.label }, draft.pricingMode, { v -> form.edit { it.copy(pricingMode = v) } }, compact = false)
            Hint("“From” shows a starting price; “each” multiplies by the number of people, for a bridal party.")
            if (draft.pricingMode == PricingMode.Hourly) {
                WHField("Hourly rate", draft.hourlyRate, { v -> form.edit { it.copy(hourlyRate = v) } }, inputModifier = Modifier.testTag("serviceHourlyRate"), keyboardOptions = money, prefix = "£", isProblem = refused("hourlyRatePence"))
            } else {
                WHField("Price", draft.price, { v -> form.edit { it.copy(price = v) } }, inputModifier = Modifier.testTag("servicePriceField"), keyboardOptions = money, prefix = "£", isProblem = refused("pricePence"))
                Hint("Leave blank for a free consultation.")
            }
            WHField("Deposit to book", draft.deposit, { v -> form.edit { it.copy(deposit = v) } }, inputModifier = Modifier.testTag("serviceDeposit"), keyboardOptions = money, prefix = "£", isProblem = refused("depositPence"))
        }

        FormGroup("How long it takes", Modifier.padding(top = 26.dp)) {
            WHSegmented(DurationMode.entries.map { it to it.label }, draft.durationMode, { v -> form.edit { it.copy(durationMode = v) } }, compact = false)
            Hint("A fixed service takes its length from the blocks of time you set next. A range is for work like a tattoo sitting that might run two hours or five.")
            if (draft.durationMode != DurationMode.Fixed) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WHField("Shortest (minutes)", draft.minMinutes, { v -> form.edit { it.copy(minMinutes = v) } }, Modifier.weight(1f), keyboardOptions = number, isProblem = refused("minMinutes"))
                WHField("Longest (minutes)", draft.maxMinutes, { v -> form.edit { it.copy(maxMinutes = v) } }, Modifier.weight(1f), keyboardOptions = number, isProblem = refused("maxMinutes"))
            }
        }

        FormGroup("Where it happens", Modifier.padding(top = 26.dp)) {
            WHSegmented(LocationMode.entries.map { it to it.label }, draft.locationMode, { v -> form.edit { it.copy(locationMode = v) } }, compact = false)
            Hint("At theirs, the client gives their address when they book, and the outlet's travel time is kept free after it.")
        }

        FormGroup("Rules", Modifier.padding(top = 26.dp)) {
            WHCard {
                SwitchRow("Clients can book this online", draft.bookableOnline, { v -> form.edit { it.copy(bookableOnline = v) } }, Modifier.padding(16.dp).testTag("serviceOnline"), hint = "Turn off for anything you would rather arrange yourself.")
                RowDivider(Modifier.padding(start = 16.dp))
                SwitchRow("Needs a signed consent form", draft.requiresConsent, { v -> form.edit { it.copy(requiresConsent = v) } }, Modifier.padding(16.dp).testTag("serviceConsent"), hint = "Kept separately from ordinary client notes, encrypted, and access to it is logged.")
            }
            // Itself is not a thing it can need first.
            val others = state.options?.services.orEmpty().filter { it.id != existing?.service?.id }
            ChoiceField("Needs another service first", "Nothing", others.map { it.id to it.name }, draft.prerequisiteServiceId, { v -> form.edit { it.copy(prerequisiteServiceId = v) } }, isProblem = refused("prerequisiteServiceId"))
            Hint("A patch test before a tint, a consultation before a large piece.")
            if (draft.prerequisiteServiceId != null) {
                WHField("How long before (hours)", draft.prerequisiteLeadHours, { v -> form.edit { it.copy(prerequisiteLeadHours = v) } }, keyboardOptions = number, isProblem = refused("prerequisiteLeadHours"))
                Hint("48 for a patch test.")
            }
            WHField("Minimum age", draft.minAgeYears, { v -> form.edit { it.copy(minAgeYears = v) } }, keyboardOptions = number, isProblem = refused("minAgeYears"))
            Hint("Blocks online booking below this age. The artist still checks photo ID in person — under-18 tattooing is an offence in the UK whatever a parent says.")
            val kit = state.options?.resourceTypes.orEmpty()
            if (kit.isNotEmpty()) {
                ChoiceField("Needs a room or piece of kit", "Nothing", kit.map { it.id to it.name }, draft.requiresResourceTypeId, { v -> form.edit { it.copy(requiresResourceTypeId = v) } }, isProblem = refused("requiresResourceTypeId"))
                Hint("A booking will only be offered when one is free.")
            }
        }

        if (existing != null) Column(Modifier.padding(top = 28.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box { WordsButton("Archive this service", { confirmingArchive = true }, Modifier.testTag("archiveService"), color = WHColors.Accent, enabled = !state.isSaving, loading = state.isArchiving) }
            Hint("It leaves the menu and stops being bookable. Past appointments keep it, so your takings still make sense.", Modifier.padding(horizontal = 4.dp))
        }
    }

    if (confirmingArchive) {
        ConfirmDialog(
            title = "Archive ${existing?.service?.name ?: "this service"}?",
            // The web archives on one tap and says nothing of what is booked.
            message = if ((existing?.timesBooked ?: 0) > 0) "It leaves the menu and stops being bookable. Anything already booked stays booked." else "It leaves the menu and stops being bookable.",
            confirm = "Archive", keep = "Keep it",
            onConfirm = { confirmingArchive = false; form.archive(onArchived) }, onDismiss = { confirmingArchive = false },
        )
    }
}
