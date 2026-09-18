package com.wunderhand.app.features.clients

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.diary.Notice
import com.wunderhand.app.features.diary.NoticeLine
import com.wunderhand.core.ClientWords
import com.wunderhand.core.HealthResponse
import com.wunderhand.core.ShopClock
import com.wunderhand.core.UnlockWords
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall

/**
 * Medical notes (chairtime `app/(pro)/clients/[id]/health/page.tsx`).
 *
 * Its own screen, never part of the profile: everything here is special
 * category data, encrypted before it reaches the database, and opening this
 * screen is a reading chairtime writes down. The log is shown at the bottom so
 * the shop sees exactly what has been recorded about them.
 *
 * Kept off the device: the app's connection caches nothing, what was loaded
 * goes when the screen does, and while it is up the window is marked secure —
 * no screenshots, no screen recording, and a blank card in the recents list.
 *
 * And kept to whoever holds the phone: the notes open with a fingerprint, a
 * face or the screen lock (`BiometricGate`), and are not fetched until they do.
 */
@Composable
fun HealthScreen(clientId: String, clientName: String?, app: AppModel, clock: ShopClock, wide: Boolean, onBack: () -> Unit) {
    val gate = LocalNotesGate.current
    val model: HealthViewModel = viewModel(key = "health-$clientId") { HealthViewModel(clientId, clientName ?: "this client", app.client, clock, gate, app::handle) }
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val activity = LocalContext.current as? Activity

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Turning the phone rebuilds the screen and should not lose half a sentence; leaving it should lose everything.
            if (activity?.isChangingConfigurations != true) model.forget()
        }
    }
    LaunchedEffect(Unit) { model.open() }
    // Gone from the app: what was read goes, and so does the unlock. Back in front: ask again.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.leftTheApp() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.open() }

    val response = state.response
    Column(Modifier.fillMaxSize().background(WHColors.Bg).imePadding().testTag("health")) {
        Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back to ${model.clientName}" }.testTag("backFromHealth"), contentAlignment = Alignment.Center) {
                WHIcon(WHIcons.ChevronLeft, size = 20.dp, tint = WHColors.Ink)
            }
            Text(model.clientName, style = WHType.Semi14, color = WHColors.Neutral700)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The log beside the notes only with room for both.
            val twoColumns = wide && maxWidth >= 760.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 26.dp else 18.dp).padding(bottom = 24.dp)) {
                Column(Modifier.padding(top = 8.dp).widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.clientName.uppercase(), Modifier.semantics { contentDescription = model.clientName }, style = WHType.Eyebrow, color = WHColors.Accent)
                    Text("Medical notes", Modifier.semantics { heading() }.testTag("healthHeading"), style = WHType.DiaryTitleWide, color = WHColors.Ink)
                    Text("Health information, kept encrypted and separately from the rest of this client’s record. Every time somebody opens it, it is written down.", style = WHType.Body, color = WHColors.Neutral800)
                }

                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.isUnprotected) NoteCard(UnlockWords.NO_SCREEN_LOCK, Modifier.testTag("notesUnprotected"))
                    state.failure?.let { NoteCard(it) }
                    if (response?.configured == false) NoteCard(ClientWords.HEALTH_OFF)
                    if (response?.unreadable == true) NoteCard(ClientWords.HEALTH_UNREADABLE)
                    state.notice?.let { (text, isProblem) -> NoticeLine(Notice(text, isProblem), Modifier.testTag("healthNotice")) }
                }

                when {
                    response != null && twoColumns -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { Record(response, model, state) }
                        val shape = RoundedCornerShape(12.dp)
                        Column(Modifier.padding(top = 20.dp).width(340.dp).liftSmall(shape).clip(shape).background(WHColors.Surface).padding(horizontal = 18.dp).padding(bottom = 16.dp)) { SignedAndSeen(response, clock, onSurface = true) }
                    }
                    response != null -> { Record(response, model, state); Column(Modifier.padding(horizontal = 4.dp)) { SignedAndSeen(response, clock, onSurface = false) } }
                    state.isLocked -> Locked(model, state, Modifier.padding(top = 20.dp))
                    state.failure == null -> Column(Modifier.padding(top = 24.dp).semantics { contentDescription = "Opening the notes" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) { SkeletonBlock(70.dp, radius = 12.dp) }
                    }
                }
            }
        }

        if (response?.canEdit == true) {
            FooterBar { PrimaryButton("Save", { focus.clearFocus(); model.save() }, Modifier.widthIn(max = 524.dp).testTag("saveHealth"), enabled = !state.isErasing, loading = state.isSaving) }
        }
    }
}

@Composable
private fun Locked(model: HealthViewModel, state: HealthState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.widthIn(max = 520.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(18.dp).testTag("notesLocked"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WHIcon(WHIcons.Lock, size = 20.dp, tint = WHColors.Ink)
            Text("Locked", style = WHType.RowName, color = WHColors.Ink)
        }
        Text(UnlockWords.locked(model.clientName, model.method), style = WHType.Medium14, color = WHColors.Neutral800)
        state.unlockProblem?.let { NoteCard(it, Modifier.testTag("unlockProblem")) }
        PrimaryButton(UnlockWords.button(model.method), { model.open() }, Modifier.testTag("unlockNotes"), loading = state.isUnlocking)
    }
}

@Composable
private fun Record(response: HealthResponse, model: HealthViewModel, state: HealthState) {
    val focus = LocalFocusManager.current
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (response.canEdit) {
            for (field in response.fields) {
                WHField(
                    field.label, state.values[field.key].orEmpty(), { model.type(field.key, it) }, inputModifier = Modifier.testTag("health-${field.key}"), singleLine = false,
                    // The keyboard does not learn words from medical notes.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                )
                if (field.hint.isNotEmpty()) Text(field.hint, Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp), style = WHType.Meta, color = WHColors.Neutral700)
            }
            Text("Working notes — parking, which chair they like — belong on the client’s main record, not here. This page is for information that changes what is safe to do.", Modifier.padding(horizontal = 4.dp).padding(top = 8.dp), style = WHType.Meta, color = WHColors.Neutral700)
        }
        response.updatedAt?.let {
            Text("Last changed ${model.clock.dayMonthYear(it)}${response.updatedByName?.let { who -> " by $who" } ?: ""}.", Modifier.padding(horizontal = 4.dp).padding(top = 8.dp), style = WHType.Meta, color = WHColors.Neutral700)
        }

        if (response.hasContent) {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Erase")
                Text("Deletes the record outright rather than hiding it. There is no undo and no copy elsewhere.", style = WHType.Summary, color = WHColors.Neutral800)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WHField("Type erase to confirm", state.confirmation, model::typeConfirmation, Modifier.weight(1f), Modifier.testTag("eraseConfirmation"),
                        KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.heightIn(min = 56.dp).clip(shape).background(WHColors.Accent100).clickable(enabled = !state.isErasing && !state.isSaving, role = Role.Button) { focus.clearFocus(); model.erase() }
                            .padding(horizontal = 18.dp).testTag("eraseHealth"),
                        contentAlignment = Alignment.Center,
                    ) { Text("Erase", style = WHType.Button, color = WHColors.Accent) }
                }
            }
        }
    }
}

@Composable
private fun SignedAndSeen(response: HealthResponse, clock: ShopClock, onSurface: Boolean) {
    var openConsent by remember { mutableStateOf<String?>(null) }

    SectionTitle("What they have signed", Modifier.padding(top = 20.dp, bottom = 8.dp))
    if (response.consents.isEmpty()) Text("Nothing recorded yet.", style = WHType.Medium14, color = WHColors.Neutral800)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (consent in response.consents) {
            val isOpen = openConsent == consent.id
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(if (onSurface) WHColors.Bg else WHColors.Surface)
                    .border(1.dp, if (consent.standing == "Withdrawn") WHColors.Accent300 else WHColors.Divider, shape)
                    .clickable(role = Role.Button, onClickLabel = if (isOpen) "Close" else "Read what was signed") { openConsent = if (isOpen) null else consent.id }
                    .padding(16.dp).testTag("consent"),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(consent.title, Modifier.weight(1f), style = WHType.Button.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = WHColors.Ink)
                    Text(clock.dayMonthYear(consent.grantedAt), style = WHType.Meta, color = WHColors.Neutral700)
                }
                Text(listOfNotNull(consent.standing, consent.recordedBy?.let { "recorded by $it" }, "tap to read".takeIf { !isOpen }).joinToString(" · "), Modifier.padding(top = 2.dp), style = WHType.CardMeta, color = WHColors.Neutral700)
                if (isOpen) {
                    RowDivider(Modifier.padding(top = 10.dp))
                    // Honest about the gap rather than papering over it.
                    Text(consent.body ?: ClientWords.CONSENT_NOT_KEPT, Modifier.padding(top = 10.dp), style = WHType.Summary, color = if (consent.body == null) WHColors.Accent else WHColors.Ink)
                }
            }
        }
    }

    SectionTitle("Who has looked at this", Modifier.padding(top = 20.dp, bottom = 8.dp))
    if (response.accessLog.isEmpty()) Text("Nothing recorded yet.", style = WHType.Medium14, color = WHColors.Neutral800)
    Column(Modifier.testTag("healthAccessLog")) {
        for (entry in response.accessLog) {
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp).semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(buildAnnotatedString { withStyle(SpanStyle(color = WHColors.Ink)) { append(entry.who) }; append(" · ${entry.what}") }, Modifier.weight(1f), style = WHType.Medium14, color = WHColors.Neutral700)
                Text(clock.dayMonthTime(entry.at), style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1)
            }
        }
    }
    Text("This list records what was opened, never what it said.", Modifier.padding(top = 4.dp), style = WHType.Meta, color = WHColors.Neutral700)
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.semantics { contentDescription = text; heading() }, style = WHType.Eyebrow, color = WHColors.Eyebrow)
}
