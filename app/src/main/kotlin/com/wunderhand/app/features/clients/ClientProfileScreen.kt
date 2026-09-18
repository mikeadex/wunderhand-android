package com.wunderhand.app.features.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.waitlist.WaitingFor
import com.wunderhand.app.features.waitlist.WaitlistSheet
import com.wunderhand.app.features.booking.NewBookingScreen
import com.wunderhand.app.features.booking.NewBookingStart
import com.wunderhand.app.features.booking.NewBookingViewModel
import com.wunderhand.app.features.diary.AppointmentSheet
import com.wunderhand.app.features.diary.Notice
import com.wunderhand.app.features.diary.NoticeLine
import com.wunderhand.app.features.diary.rememberNow
import com.wunderhand.core.ClientProfileResponse
import com.wunderhand.core.initialsOf
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.OneLine
import com.wunderhand.design.Panel
import com.wunderhand.design.PanelRow
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * One client (chairtime `app/(pro)/clients/[id]/page.tsx`).
 *
 * The four numbers a pro asks about somebody before they sit down — visits,
 * spend, how often, missed — are one sentence under the name, as the diary
 * says its day. What is coming and what happened fill the page; what is on
 * file about them sits below, with medical notes as a way in, never their
 * contents — opening those is a reading, and it is written down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientProfileScreen(id: String, app: AppModel, list: ClientsViewModel, wide: Boolean, onBack: () -> Unit) {
    val model = remember(id) { ClientProfileModel(id, app.client, list.clock, app::handle) }
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val now by rememberNow(60_000)
    var isEditing by rememberSaveable(id) { mutableStateOf(false) }
    var isWaitlisting by rememberSaveable(id) { mutableStateOf(false) }
    var rebooking by remember(id) { mutableStateOf<NewBookingStart?>(null) }
    var openVisit by rememberSaveable(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) { model.load() }

    fun rebook(client: ClientProfileResponse.Profile) {
        model.forgetNotice()
        rebooking = NewBookingStart(clientId = client.id, clientName = client.name)
    }

    Column(Modifier.fillMaxSize().background(WHColors.Bg).testTag("clientProfile")) {
        if (!wide) {
            Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back to clients" }.testTag("backToClients"), contentAlignment = Alignment.Center) {
                    WHIcon(WHIcons.ChevronLeft, size = 20.dp, tint = WHColors.Ink)
                }
                Text("Clients", style = WHType.Semi14, color = WHColors.Neutral700)
            }
        }

        val response = state.response
        when {
            response != null -> {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Beside the list on an upright tablet the profile has about 490dp: one column. Two only with room for both.
                    val twoColumns = wide && maxWidth >= 720.dp
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 26.dp else 18.dp).padding(top = if (wide) 24.dp else 4.dp, bottom = 24.dp)) {
                        Header(response, list, wide, twoColumns, onRebook = { rebook(response.client) }, onWaitlist = { isWaitlisting = true }, onEdit = { isEditing = true })
                        if (response.paysInFull) PaysInFull(Modifier.padding(top = 12.dp))
                        state.notice?.let { NoticeLine(Notice(it, false), Modifier.padding(top = 14.dp)) }

                        val visits: @Composable () -> Unit = { Visits(response, list, now, onOpen = { openVisit = it }, onBook = { rebook(response.client) }) }
                        val onFile: @Composable () -> Unit = { OnFile(response, onHealth = { list.showHealth(true) }, onEdit = { isEditing = true }) }
                        if (twoColumns) {
                            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { visits() }
                                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { onFile() }
                            }
                        } else {
                            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { visits(); onFile() }
                        }
                    }
                }
                if (!wide) {
                    FooterBar {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Edit", { isEditing = true }, Modifier.weight(1f).testTag("editClient"))
                            PrimaryButton("Rebook", { rebook(response.client) }, Modifier.weight(1f).testTag("rebook"))
                        }
                    }
                }
            }
            state.failure != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteCard(state.failure.orEmpty())
                WordsButton("Try again", { scope.launch { model.load() } })
            }
            else -> Column(Modifier.padding(horizontal = if (wide) 26.dp else 18.dp).padding(top = 18.dp).semantics { contentDescription = "Loading the client" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(11.dp, width = 110.dp); SkeletonBlock(28.dp, width = 220.dp); SkeletonBlock(13.dp, Modifier.padding(top = 6.dp), width = 260.dp)
                SkeletonBlock(90.dp, Modifier.padding(top = 18.dp), radius = 10.dp); SkeletonBlock(140.dp, Modifier.padding(top = 8.dp), radius = 10.dp)
            }
        }
    }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profile = state.response?.client
    if (isWaitlisting && profile != null) WaitlistSheet(app, list.clock, addFor = WaitingFor(profile.id, profile.name), onClose = { isWaitlisting = false })
    if (isEditing && profile != null) {
        ModalBottomSheet(onDismissRequest = { isEditing = false }, sheetState = sheet, containerColor = WHColors.Bg, dragHandle = null) {
            val form: ClientFormViewModel = viewModel(key = "client-form-${profile.id}") { ClientFormViewModel(profile, app.client, app::handle, createSavedStateHandle()) }
            ClientFormScreen(
                form,
                onSaved = { isEditing = false; scope.launch { model.load() }; list.load() },
                onRemoved = { isEditing = false; list.removed() },
                onClose = { isEditing = false },
            )
        }
    }
    rebooking?.let { start ->
        ModalBottomSheet(onDismissRequest = { rebooking = null }, sheetState = sheet, containerColor = WHColors.Bg, dragHandle = null, sheetMaxWidth = 980.dp) {
            val booking: NewBookingViewModel = viewModel(key = "booking-${start.id}") {
                NewBookingViewModel(start, app.client, list.clock, list.currency, app::handle, createSavedStateHandle())
            }
            NewBookingScreen(booking, onBooked = { created -> rebooking = null; scope.launch { model.bookedIn(created.startsAt) }; list.load() }, onClose = { rebooking = null })
        }
    }
    openVisit?.let { visit ->
        ModalBottomSheet(onDismissRequest = { openVisit = null }, sheetState = sheet, containerColor = WHColors.Bg, dragHandle = null) {
            AppointmentSheet(visit, app.client, list.clock, changed = { model.load(); list.load().join() }, app::handle, onClose = { openVisit = null }, onRebook = { openVisit = null; rebooking = it })
        }
    }
}

@Composable
private fun Header(response: ClientProfileResponse, list: ClientsViewModel, wide: Boolean, twoColumns: Boolean, onRebook: () -> Unit, onWaitlist: () -> Unit, onEdit: () -> Unit) {
    val client = response.client
    val status = response.status(list.clock)
    @Composable
    fun actions() {
        PrimaryButton("Rebook", onRebook, Modifier.testTag("rebook"), fill = false)
        // For when the day they want is full: the next gap that suits them is offered to them.
        WordsButton("Add to waitlist", onWaitlist, Modifier.testTag("addToWaitlist"))
        WordsButton("Edit", onEdit, Modifier.testTag("editClient"), color = WHColors.Neutral700)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (wide) Box(Modifier.size(52.dp).clip(CircleShape).background(WHColors.Neutral200).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text(initialsOf(client.name), style = WHType.RowName, color = WHColors.Neutral700)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(status.text.uppercase(), Modifier.semantics { contentDescription = status.text }.testTag("clientStatus"), style = WHType.Eyebrow, color = if (status.isDue) WHColors.Accent else WHColors.Eyebrow)
            Text(client.name, Modifier.semantics { heading() }.testTag("clientName"), style = if (wide) WHType.DiaryTitleWide else WHType.DiaryTitle, color = WHColors.Ink, maxLines = 2)
        }
        if (twoColumns) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }

    SelectionContainer {
        Text(listOfNotNull(client.phone, client.email).joinToString(" · ").ifEmpty { "No contact details" }, Modifier.padding(top = 12.dp), style = WHType.Medium14, color = WHColors.Neutral700)
    }

    // "7 visits · £456 spent · usually every 5 weeks · last in 3 Aug · 1 missed"
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = WHColors.Ink)
    Text(
        buildAnnotatedString {
            withStyle(strong) { append("${client.visitCount} ${if (client.visitCount == 1) "visit" else "visits"}") }
            client.spendPence?.let { append(" · "); withStyle(strong) { append(it.formatted(list.currency)) }; append(" spent") }
            response.usualWeeks?.let { append(" · usually every $it ${if (it == 1) "week" else "weeks"}") }
            client.lastVisitAt?.let { append(" · last in ${list.clock.dayMonth(it)}") }
            if (client.noShowCount > 0) { append(" · "); withStyle(strong.copy(color = WHColors.Accent)) { append("${client.noShowCount} missed") } }
        },
        Modifier.padding(top = 12.dp).testTag("clientStats"), style = WHType.SummaryWide, color = WHColors.Neutral700,
    )

    if (wide && !twoColumns) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    // On a phone Rebook and Edit are pinned under the screen; this sits with what is known about them.
    if (!wide) Box(Modifier.padding(top = 4.dp).offset(x = (-8).dp)) { WordsButton("Add to waitlist", onWaitlist, Modifier.testTag("addToWaitlist")) }
}

@Composable
private fun PaysInFull(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WHColors.Accent100).padding(horizontal = 16.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(WHColors.Accent))
        Text("Two or more no-shows — this client now pays in full when booking online.", Modifier.weight(1f), style = WHType.Summary, color = WHColors.Ink)
    }
}

@Composable
private fun Visits(response: ClientProfileResponse, list: ClientsViewModel, now: Instant, onOpen: (String) -> Unit, onBook: () -> Unit) {
    if (response.projects.isNotEmpty()) {
        Panel("In progress") {
            for (piece in response.projects) PanelRow {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(piece.name, style = WHType.Semi14, color = WHColors.Ink)
                        Text(piece.line(list.currency), style = WHType.Meta, color = WHColors.Neutral700)
                    }
                    piece.paidPence?.let { Text("${it.formatted(list.currency)} paid", style = WHType.Semi14, color = WHColors.Ink) }
                }
            }
        }
    }

    val upcoming = response.upcoming(now)
    Panel("Coming up") {
        if (upcoming.isEmpty()) PanelRow(vertical = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (response.due) "Nothing booked, and they are due." else "Nothing booked.", Modifier.weight(1f), style = WHType.Medium14, color = WHColors.Neutral700)
                WordsButton("+ Book them in", onBook, Modifier.testTag("bookThemIn"), color = WHColors.Accent)
            }
        } else for (visit in upcoming) VisitRow(visit, list, upcoming = true, onOpen)
    }

    val past = response.past(now)
    Panel("History") {
        if (past.isEmpty()) PanelRow(vertical = 16.dp) { Text("No visits yet. Their first appointment will show here.", style = WHType.Medium14, color = WHColors.Neutral700) }
        else for (visit in past) VisitRow(visit, list, upcoming = false, onOpen)
    }
}

@Composable
private fun VisitRow(visit: ClientProfileResponse.Visit, list: ClientsViewModel, upcoming: Boolean, onOpen: (String) -> Unit) {
    val clock = list.clock
    val day = if (upcoming) clock.shortDay(visit.startsAt) else clock.dayMonthYear(visit.startsAt)
    // An owner's column. Anyone else is sent no prices, and gets no column of dashes.
    val price = if (list.seesMoney) visit.pricePence?.formatted(list.currency) ?: "—" else null
    val spoken = listOfNotNull(day, clock.time(visit.startsAt).takeIf { upcoming }, visit.serviceName, visit.tag, price?.takeIf { it != "—" }).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button, onClickLabel = "Open the appointment") { onOpen(visit.id) }
            .semantics(mergeDescendants = true) { contentDescription = spoken }.testTag("clientVisit"),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (upcoming) WHColors.Ink else WHColors.Surface))
        PanelRow(Modifier.weight(1f).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(day, style = WHType.Summary.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Ink, maxLines = 1)
                    if (upcoming) Text(clock.time(visit.startsAt), style = WHType.Meta, color = WHColors.Neutral700)
                }
                Text(
                    visit.serviceName, Modifier.weight(1f), style = WHType.Medium14, maxLines = 1,
                    color = if (visit.isMissed) WHColors.Neutral700 else WHColors.Ink, textDecoration = if (visit.isMissed) TextDecoration.LineThrough else null,
                )
                visit.tag?.let { tag ->
                    val missed = visit.status == "no_show"
                    Text(tag, Modifier.clip(RoundedCornerShape(5.dp)).background(if (missed) WHColors.Accent100 else WHColors.Well).padding(horizontal = 7.dp, vertical = 2.dp), style = WHType.Tag, color = if (missed) WHColors.Accent else WHColors.Neutral700, maxLines = 1)
                }
                price?.let { Text(it, style = WHType.Semi14, color = WHColors.Ink, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun OnFile(response: ClientProfileResponse, onHealth: () -> Unit, onEdit: () -> Unit) {
    val client = response.client
    Panel("Medical notes") {
        val has = response.hasHealthRecord
        Box(Modifier.clickable(role = Role.Button, onClick = onHealth).semantics(mergeDescendants = true) { }.testTag("medicalNotes")) {
            PanelRow {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (has) "Allergies and history on file" else "Nothing recorded", style = WHType.Semi14, color = WHColors.Ink)
                        Text(if (has) "Kept encrypted. Opening it is written down." else "Allergies, skin conditions, patch tests", style = WHType.Meta, color = WHColors.Neutral700)
                    }
                    Text(if (has) "Open" else "+ Add", style = WHType.BarAction, color = if (has) WHColors.Ink else WHColors.Accent)
                }
            }
        }
    }
    Panel("Standing formula") {
        PanelRow { SelectionContainer { Text(client.standingFormula ?: "None saved.", style = WHType.Medium14, color = if (client.standingFormula == null) WHColors.Neutral700 else WHColors.Ink) } }
    }
    Panel("Notes", action = "Edit", actionTag = "editNotes", onAction = onEdit) {
        PanelRow { SelectionContainer { Text(client.notes ?: "Nothing noted.", style = WHType.Medium14, color = if (client.notes == null) WHColors.Neutral700 else WHColors.Ink) } }
    }
}
