package com.wunderhand.app.features.clients

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Add a client, or change one (chairtime `components/clients/ClientForm.tsx`).
 *
 * The two notes boxes are working notes, not a medical record, and the hint
 * says so plainly: a free-text box otherwise quietly becomes a medical file
 * nobody is protecting. Allergies and history belong in medical notes, which
 * are encrypted and access-logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientFormScreen(form: ClientFormViewModel, onSaved: (String) -> Unit, onRemoved: () -> Unit, onClose: () -> Unit) {
    val state by form.state.collectAsStateWithLifecycle()
    val input = state.input
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var pickingBirthday by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }
    // The system's own picker: it hands over the one number chosen, and the app never sees the rest.
    val contacts = rememberLauncherForActivityResult(ContactReader.PickNumber()) { picked ->
        picked?.let { ContactReader.read(context, it) }?.let(form::fill)
    }

    Column(Modifier.fillMaxSize().background(WHColors.Bg).imePadding().testTag("clientForm")) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
                Box(Modifier.padding(top = 12.dp).size(48.dp).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close" }.testTag("closeClientForm"), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.size(40.dp).liftSmall(CircleShape).clip(CircleShape).background(WHColors.Surface).border(1.dp, WHColors.Divider, CircleShape), contentAlignment = Alignment.Center) {
                        WHIcon(WHIcons.X, size = 18.dp, tint = WHColors.Ink)
                    }
                }
                Text(form.existing?.name ?: "New client", Modifier.padding(top = 10.dp).semantics { heading() }, style = WHType.SheetName, color = WHColors.Ink)
                state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp).testTag("clientFormProblem")) }

                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (form.existing == null) {
                        // A new client only: picking a contact over somebody already on file would quietly replace what the shop had.
                        val shape = RoundedCornerShape(10.dp)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape)
                                .clickable(role = Role.Button) { focus.clearFocus(); runCatching { contacts.launch(Unit) } }.testTag("clientFromContacts"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WHIcon(WHIcons.UserPlus, size = 18.dp, tint = WHColors.Ink)
                            Text("From your contacts", style = WHType.Semi14, color = WHColors.Ink)
                        }
                        state.fromContacts?.let { Text(it, Modifier.padding(horizontal = 4.dp).testTag("clientFromContactsSaid"), style = WHType.CardMeta, color = WHColors.Neutral800) }
                    }

                    // A field chairtime refused is outlined, so the sentence above has somewhere to point.
                    fun refused(key: String) = if (state.problemField == key) Modifier.border(1.dp, WHColors.Accent, RoundedCornerShape(12.dp)) else Modifier
                    WHField("Name", input.name, { v -> form.edit { it.copy(name = v) } }, refused("name"),
                        Modifier.testTag("clientNameField").semantics { contentType = ContentType.PersonFullName },
                        KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                    WHField("Mobile", input.phone, { v -> form.edit { it.copy(phone = v) } }, refused("phone"),
                        Modifier.testTag("clientPhoneField").semantics { contentType = ContentType.PhoneNumber }, KeyboardOptions(keyboardType = KeyboardType.Phone))
                    WHField("Email", input.email, { v -> form.edit { it.copy(email = v) } }, refused("email"),
                        Modifier.testTag("clientEmailField").semantics { contentType = ContentType.EmailAddress },
                        KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email))

                    DateOfBirthField(input.dateOfBirth, onPick = { pickingBirthday = true }, onClear = { form.edit { it.copy(dateOfBirth = "") } }, refused("dateOfBirth"))
                    Hint("Only needed for age-restricted work. Photo ID is still checked in person.")

                    WHField("Usual formula or preference", input.standingFormula, { v -> form.edit { it.copy(standingFormula = v) } }, singleLine = false,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    Hint("Colour recipe, clipper guard, needle preference.")

                    WHField("Notes", input.notes, { v -> form.edit { it.copy(notes = v) } }, inputModifier = Modifier.testTag("clientNotesField"), singleLine = false,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    Hint("Practical things — parks round the back, prefers the window chair. Keep allergies and medical history out of here: those belong in medical notes, which are encrypted and access-logged.")
                }

                if (form.existing != null) {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        WordsButton("Remove this client", { confirmingRemove = true }, Modifier.testTag("removeClient"), color = WHColors.Accent, enabled = !state.isSaving, loading = state.isRemoving)
                        Text(
                            "Hides them from your list. Their past appointments stay, so your takings still add up, and so do the consent forms they signed. Their medical notes are locked away and deleted automatically when your retention period ends. If they have asked to be erased, erase the notes from their medical notes first.",
                            Modifier.padding(horizontal = 8.dp), style = WHType.Meta, color = WHColors.Neutral700,
                        )
                    }
                }
            }
        }
        FooterBar(Modifier.navigationBarsPadding()) {
            PrimaryButton(if (form.existing == null) "Add client" else "Save", { focus.clearFocus(); form.save(onSaved) }, Modifier.widthIn(max = 524.dp).testTag("saveClient"), enabled = !state.isRemoving, loading = state.isSaving)
        }
    }

    if (pickingBirthday) {
        // A date of birth is a calendar date, not an instant: kept at midnight UTC so no clock change or travelling phone turns the 4th into the 3rd.
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = runCatching { LocalDate.parse(input.dateOfBirth) }.getOrDefault(today.minusYears(30))
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1900..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingBirthday = false },
            confirmButton = {
                WordsButton("Choose", {
                    picker.selectedDateMillis?.let { millis -> form.edit { it.copy(dateOfBirth = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()) } }
                    pickingBirthday = false
                }, Modifier.testTag("chooseBirthday"))
            },
            dismissButton = { WordsButton("Cancel", { pickingBirthday = false }, color = WHColors.Neutral700) },
        ) { DatePicker(picker) }
    }

    if (confirmingRemove) {
        ConfirmDialog(
            title = "Remove ${form.existing?.name ?: "this client"} from your clients?",
            message = "Their appointments, consent forms and medical notes are kept.",
            confirm = "Remove", keep = "Keep them",
            onConfirm = { form.remove(onRemoved) }, onDismiss = { confirmingRemove = false },
        )
    }
}

private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

@Composable
private fun DateOfBirthField(iso: String, onPick: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val date = runCatching { LocalDate.parse(iso) }.getOrNull()
    val shown = date?.format(longDate) ?: "Add a date of birth"
    Row(modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onPick).semantics { contentDescription = "Date of birth: $shown" }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("clientBirthday"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("DATE OF BIRTH", Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
            Text(shown, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Ink)
        }
        if (date != null) WordsButton("Clear", onClear, Modifier.padding(end = 8.dp), color = WHColors.Neutral700)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp), style = WHType.Meta, color = WHColors.Neutral700)
}
