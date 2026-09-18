package com.wunderhand.app.features.waitlist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.app.features.shell.EditorSheet
import com.wunderhand.core.Durations
import com.wunderhand.core.Flexibility
import com.wunderhand.core.GapResponse
import com.wunderhand.core.IsoDay
import com.wunderhand.core.OfferSent
import com.wunderhand.core.ShopClock
import com.wunderhand.core.WaitWords
import com.wunderhand.core.WaitingRow
import com.wunderhand.design.ChoiceField
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.Hint
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.TagTone
import com.wunderhand.design.WHChip
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHTag
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** "Wren is on the list." — said quietly, and read out when it appears. */
@Composable
private fun NoticeLine(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("waitlistNotice"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WHIcon(WHIcons.Check, size = 16.dp, tint = WHColors.Ink, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = WHType.Body, color = WHColors.Ink)
    }
}

// region Everybody waiting

/**
 * Everybody waiting (chairtime `app/(pro)/waitlist/page.tsx`), in a sheet over
 * wherever it was asked for: the diary's "3 waiting", a gap nobody fits, or a
 * client's profile — where [addFor] opens the form on them straight away.
 */
@Composable
fun WaitlistSheet(app: AppModel, clock: ShopClock, addFor: WaitingFor? = null, onClose: () -> Unit) {
    EditorSheet(onDismiss = onClose) {
        val opened = rememberSaveable { UUID.randomUUID().toString() }
        val model: WaitlistViewModel = viewModel(key = "waitlist-$opened") { WaitlistViewModel(app.client, app::handle, addStraightAway = addFor != null) }
        val state by model.state.collectAsStateWithLifecycle()

        if (state.isAdding) {
            // The system's back leaves the form, not the whole list.
            BackHandler { model.adding(false) }
            val form: WaitlistJoinViewModel = viewModel(key = "waitlist-join-$opened-${state.addVisit}") { WaitlistJoinViewModel(app.client, app.client, app::handle, addFor, createSavedStateHandle()) }
            WaitlistJoinScreen(form, clock, onJoined = model::added, onClose = { model.adding(false) })
        } else {
            WaitlistScreen(model, state, clock, onClose)
        }
    }
}

@Composable
private fun WaitlistScreen(model: WaitlistViewModel, state: WaitlistState, clock: ShopClock, onClose: () -> Unit) {
    var removing by remember { mutableStateOf<WaitingRow?>(null) }
    val response = state.response
    EditorPage(
        title = "Waiting", onClose = onClose, tag = "waitlist", eyebrow = model.eyebrow(state),
        intro = "When something comes free, the diary offers it to whoever on this list can actually take it.",
        footer = { PrimaryButton("Add someone", { model.adding(true) }, Modifier.testTag("waitlistAdd")) },
    ) {
        state.notice?.let { NoticeLine(it, Modifier.padding(top = 14.dp)) }
        state.failure?.let { NoteCard(it, Modifier.padding(top = 14.dp)); if (response == null) WordsButton("Try again", { model.load() }) }
        when {
            response == null -> if (state.failure == null) Column(Modifier.padding(top = 24.dp).semantics { contentDescription = "Loading the waitlist" }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(4) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { SkeletonBlock(14.dp, width = 160.dp); SkeletonBlock(10.dp, width = 220.dp) } }
            }
            response.waiting.isEmpty() -> Column(Modifier.padding(top = 32.dp).testTag("nobodyWaiting"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nobody waiting", style = WHType.EmptyTitle, color = WHColors.Ink)
                Text("Next time you turn someone away because the day is full, put them here. A cancellation then costs you a tap instead of an afternoon of phone calls.", style = WHType.Body, color = WHColors.Neutral700)
            }
            else -> Column(Modifier.padding(top = 20.dp)) {
                for (row in response.waiting) WaitingRowView(row, model.waited(row), clock, isRemoving = state.removingId == row.id) { removing = row }
            }
        }
    }

    removing?.let { row ->
        ConfirmDialog(
            title = "Take ${row.clientName} off the list?", message = "They will not be offered the next gap.", confirm = "Take off the list", keep = "Keep them",
            onConfirm = { model.remove(row) }, onDismiss = { removing = null },
        )
    }
}

@Composable
private fun WaitingRowView(row: WaitingRow, waited: String, clock: ShopClock, isRemoving: Boolean, onRemove: () -> Unit) {
    // Stored as the end of the last useful day; named as that day.
    val until = row.latest?.let { "Until ${clock.dayMonth(it.minus(Duration.ofMinutes(1)))}" }
    Column(Modifier.fillMaxWidth().testTag("waitlistRow")) {
        RowDivider()
        Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = listOfNotNull(row.clientName, row.detail, if (row.hasLiveOffer) "has an offer out" else "waiting $waited", until).joinToString(", ") },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.clientName, style = WHType.RowName, color = WHColors.Ink, maxLines = 1)
                    Text(row.detail, style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1)
                }
                if (row.hasLiveOffer) WHTag("Offered", TagTone.Accent, Modifier.clearAndSetSemantics { }) else Text(waited, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (until != null) Text(until, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
                // The words line up with the name above; the button's own padding hangs into the margin.
                Box(Modifier.offset(x = if (until == null) (-8).dp else 0.dp)) {
                    WordsButton("Take off the list", onRemove, Modifier.semantics { contentDescription = "Take ${row.clientName} off the list" }.testTag("waitlistRemove"), color = WHColors.Accent, loading = isRemoving)
                }
            }
        }
    }
}

// endregion
// region Who is waiting?

/** Put somebody on the list (chairtime `app/(pro)/waitlist/new/page.tsx`). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WaitlistJoinScreen(form: WaitlistJoinViewModel, clock: ShopClock, onJoined: (String) -> Unit, onClose: () -> Unit) {
    val state by form.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    /** Which date is being picked: true for "not before". */
    var picking by remember { mutableStateOf<Boolean?>(null) }

    EditorPage(
        title = "Who is waiting?", onClose = onClose, tag = "waitlistJoin", back = true, eyebrow = "Waitlist", closeLabel = "Back to the waiting list",
        footer = { PrimaryButton("Add to the list", { focus.clearFocus(); form.save(onJoined) }, Modifier.testTag("waitlistJoinSave"), enabled = state.client != null && state.serviceId.isNotEmpty(), loading = state.isSaving) },
    ) {
        state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("waitlistJoinProblem")) }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val chosen = state.client
            if (chosen != null) {
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp).testTag("waitlistClient").semantics(mergeDescendants = true) { contentDescription = "Client: ${chosen.clientName}" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CLIENT", Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
                        Text(chosen.clientName, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Ink)
                    }
                    WordsButton("Change", form::changeClient, Modifier.semantics { contentDescription = "Choose somebody else" }, color = WHColors.Neutral700)
                }
            } else {
                WHField("Client", state.query, form::search, inputModifier = Modifier.testTag("waitlistClientSearch"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search), placeholder = "Search by name or number")
                if (state.matches.isNotEmpty()) {
                    val shape = RoundedCornerShape(12.dp)
                    Column(Modifier.fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface)) {
                        state.matches.forEachIndexed { index, row ->
                            if (index > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { focus.clearFocus(); form.choose(row) }
                                    .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(row.name, row.phone).joinToString(", ") }.padding(horizontal = 16.dp).testTag("waitlistClientMatch"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(row.name, Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.RowName, color = WHColors.Ink, maxLines = 1)
                                row.phone?.let { Text(it, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700) }
                            }
                        }
                    }
                }
            }

            ChoiceField("What for", null, state.options?.services.orEmpty().map { it.id to it.name }, state.serviceId.ifEmpty { null }, { it?.let(form::service) }, Modifier.testTag("waitlistService"))
            ChoiceField("With", "Anyone available", state.options?.staff.orEmpty().map { it.id to it.name }, state.staffId, form::staff, Modifier.testTag("waitlistStaff"))
            Hint("Anyone available means more gaps will suit them.")
            DateField("Not before", state.earliest, onPick = { picking = true }, onClear = { form.earliest(null) })
            Hint("Leave blank for as soon as possible.")
            DateField("No use after", state.latest, onPick = { picking = false }, onClear = { form.latest(null) })
            Hint("A wedding, a holiday. They drop off the list on that date.")
        }

        Eyebrow("Days that work", Modifier.padding(top = 22.dp, start = 4.dp))
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in Flexibility.days) WHChip(label, value in state.days, { form.day(value) }, Modifier.testTag("waitlistDay-$value"))
        }
        Hint("None ticked means any day.")
        Eyebrow("Times that work", Modifier.padding(top = 22.dp, start = 4.dp))
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (part in Flexibility.parts) WHChip("${part.label} · ${part.sublabel.lowercase()}", part.value in state.parts, { form.part(part.value) }, Modifier.testTag("waitlistPart-${part.value}"))
        }
        Hint("None ticked means any time.")
    }

    picking?.let { isEarliest ->
        // A calendar date, picked and kept in UTC, so no travelling phone turns the 4th into the 3rd. Today is the shop's today.
        val today = LocalDate.parse(clock.isoDate(Instant.now()))
        val start = (if (isEarliest) state.earliest else state.latest)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        val floor = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = today.year..today.year + 2,
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= floor },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                WordsButton("Choose", {
                    picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }?.let { if (isEarliest) form.earliest(it) else form.latest(it) }
                    picking = null
                }, Modifier.testTag("chooseDate"))
            },
            dismissButton = { WordsButton("Cancel", { picking = null }, color = WHColors.Neutral700) },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun DateField(label: String, iso: String?, onPick: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val shown = iso?.let { runCatching { "${IsoDay.weekdayLong(it)} ${LocalDate.parse(it).dayOfMonth} ${LocalDate.parse(it).month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)}" }.getOrNull() } ?: "Pick a date"
    Row(Modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onPick).semantics { contentDescription = "$label: $shown" }.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
            Text(shown, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = if (iso == null) WHColors.Neutral700 else WHColors.Ink)
        }
        if (iso != null) WordsButton("Clear", onClear, Modifier.padding(end = 4.dp).semantics { contentDescription = "Clear ${label.lowercase()}" }, color = WHColors.Neutral700)
    }
}

// endregion
// region Fill the gap

/** Fill the gap (chairtime `app/(pro)/gaps/page.tsx`), in a sheet over the diary. */
@Composable
fun GapSheet(app: AppModel, window: GapWindow, clock: ShopClock, shopName: String, onClose: () -> Unit) {
    var showingWaitlist by rememberSaveable { mutableStateOf(false) }
    EditorSheet(onDismiss = onClose) {
        val model: GapViewModel = viewModel(key = "gap-${window.id}-${rememberSaveable { UUID.randomUUID().toString() }}") { GapViewModel(window, app.client, app::handle) }
        val state by model.state.collectAsStateWithLifecycle()
        val sent = state.sent
        if (sent != null) SentScreen(sent, shopName, onClose) else GapScreen(model, state, clock, onClose, seeWaitlist = { showingWaitlist = true })
    }
    if (showingWaitlist) WaitlistSheet(app, clock, onClose = { showingWaitlist = false })
}

@Composable
private fun GapScreen(model: GapViewModel, state: GapState, clock: ShopClock, onClose: () -> Unit, seeWaitlist: () -> Unit) {
    val window = model.window
    val response = state.response
    EditorPage(
        title = "${clock.time(window.from)}–${clock.time(window.to)}", onClose = onClose, tag = "gap", eyebrow = clock.weekdayDayMonth(window.from),
        intro = "${Durations.label(window.minutes)} free. ${model.summary(state)}",
        footer = {
            if (response?.candidates?.isEmpty() == true) SecondaryButton("See the waitlist", seeWaitlist, Modifier.fillMaxWidth().testTag("gapSeeWaitlist"))
            else PrimaryButton("Offer it", { model.offer() }, Modifier.testTag("offerGap"), enabled = state.canOffer, loading = state.isSending)
        },
    ) {
        state.failure?.let { NoteCard(it, Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("gapProblem")); if (response == null) WordsButton("Try again", { model.load() }) }
        when {
            response == null -> if (state.failure == null) Column(Modifier.padding(top = 24.dp).semantics { contentDescription = "Finding who could take it" }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(3) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SkeletonBlock(20.dp, width = 20.dp, radius = 4.dp); Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { SkeletonBlock(14.dp, width = 150.dp); SkeletonBlock(10.dp, width = 200.dp) } } }
            }
            response.candidates.isEmpty() -> Column(Modifier.padding(top = 32.dp).testTag("nobodyFits"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nobody fits this one", style = WHType.EmptyTitle, color = WHColors.Ink)
                Text("Either nobody is waiting for something that fits ${Durations.label(window.minutes)}, or the people who are cannot make this time of day. Both are worth knowing — a waitlist with nobody on it fills no gaps.", style = WHType.Body, color = WHColors.Neutral800)
            }
            else -> {
                Eyebrow("Best fit first" + if (response.candidates.size > response.suggested) " · ${response.suggested} suggested" else "", Modifier.padding(top = 24.dp, bottom = 8.dp))
                for (c in response.candidates) CandidateRow(c, c.entryId in state.ticked, clock.time(c.startsAt), model.joined(c)) { model.tick(c.entryId) }
                Text("Everyone ticked gets the same slot and the first to say yes takes it. The others keep their place on the list.", Modifier.padding(top = 20.dp), style = WHType.Meta, color = WHColors.Neutral700)
            }
        }
    }
}

/** The whole row is the control: a checkbox you have to hit exactly is a bad target between clients. */
@Composable
private fun CandidateRow(c: GapResponse.Candidate, on: Boolean, time: String, joined: String, onTick: () -> Unit) {
    // The number that decides the order, said out loud.
    val fit = if (c.closesExactly) "fills it" else "${Durations.label(c.leftoverMinutes)} left"
    Column {
        RowDivider()
        Row(
            Modifier.fillMaxWidth().background(if (on) WHColors.Surface else WHColors.Bg).toggleable(on, role = Role.Checkbox, onValueChange = { onTick() })
                .semantics(mergeDescendants = true) { contentDescription = "${c.clientName}, ${c.serviceName} at $time, $joined, $fit" }.padding(horizontal = 12.dp, vertical = 14.dp).testTag("gapCandidate"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val box = RoundedCornerShape(6.dp)
            Box(Modifier.size(22.dp).clip(box).background(if (on) WHColors.Ink else WHColors.Surface).border(1.dp, if (on) WHColors.Ink else WHColors.Neutral500, box), contentAlignment = Alignment.Center) {
                if (on) WHIcon(WHIcons.Check, size = 14.dp, tint = WHColors.Bg)
            }
            Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.clientName, style = WHType.RowName, color = WHColors.Ink, maxLines = 1)
                Text("${c.serviceName} · $time · $joined", style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1)
            }
            if (c.closesExactly) WHTag("Fills it", TagTone.Accent, Modifier.clearAndSetSemantics { }) else Text(fit, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
        }
    }
}

/**
 * What just went out, and what still has to go out by hand (chairtime
 * `app/(pro)/gaps/sent/page.tsx`). Text messages are not wired, and most
 * clients on a waitlist are a phone number with no email — so the phone's own
 * messages app is opened on their number with the words and the link already
 * in it. The app sends nothing: whoever is holding the phone presses send.
 */
@Composable
private fun SentScreen(sent: OfferSent, shopName: String, onClose: () -> Unit) {
    val n = sent.sent.size
    EditorPage(
        title = if (n == 0) "Nothing to show" else "$n ${if (n == 1) "person" else "people"} asked", onClose = onClose, tag = "offerSent", eyebrow = "Offered",
        intro = if (n == 0) "Nobody's service still fits the window, so no offer went out." else "First to say yes takes it. The links last a day and each one opens a single offer.",
        footer = { PrimaryButton("Back to the diary", onClose, Modifier.testTag("gapDone")) },
    ) {
        if (sent.emailed.isNotEmpty()) LinkSection(
            if (sent.emailWorking) "Emailed" else "Email attempted", "Email is not fully connected yet, so send these by hand too.".takeIf { !sent.emailWorking }, noteIsWarning = true,
            sent.emailed, shopName,
        ) { it.email.orEmpty() }
        if (sent.toText.isNotEmpty()) LinkSection("Text these yourself", "No email on file. Send the link in a message.", noteIsWarning = false, sent.toText, shopName) { it.phone ?: "No number" }
    }
}

@Composable
private fun LinkSection(title: String, note: String?, noteIsWarning: Boolean, links: List<OfferSent.Link>, shopName: String, reachedAt: (OfferSent.Link) -> String) {
    val context = LocalContext.current
    Eyebrow(title, Modifier.padding(top = 24.dp, bottom = 6.dp))
    if (note != null) Text(note, Modifier.padding(bottom = 8.dp), style = WHType.Meta, color = if (noteIsWarning) WHColors.Accent else WHColors.Neutral700)
    for (link in links) Column {
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(link.name, style = WHType.RowName, color = WHColors.Ink)
                Text(reachedAt(link), style = WHType.Meta, color = WHColors.Neutral700)
            }
            SecondaryButton("Send", {
                val words = WaitWords.text(link, shopName)
                // To their number where there is one; otherwise whatever the phone shares with.
                val intent = link.phone?.takeIf { it.isNotBlank() }?.let { Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(it)}")).putExtra("sms_body", words) }
                    ?: Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words), "Send the offer to ${link.firstName}")
                runCatching { context.startActivity(intent) }.onFailure {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words), "Send the offer to ${link.firstName}")) }
                }
            }, Modifier.semantics { contentDescription = "Send the offer to ${link.name}" }.testTag("offerShare"), minHeight = 48.dp)
        }
    }
}

// endregion
