package com.wunderhand.app.features.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.shell.EditorSheet
import com.wunderhand.core.Durations
import com.wunderhand.core.MenuServiceResponse
import com.wunderhand.design.CloseButton
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.Fact
import com.wunderhand.design.FooterBar
import com.wunderhand.design.InkButton
import com.wunderhand.design.NoteCard
import com.wunderhand.design.Panel
import com.wunderhand.design.PanelRow
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import kotlinx.coroutines.launch
import java.util.UUID
import com.wunderhand.app.app.WebLink

private enum class Editing { Service, Steps, Performers, Extras }

/**
 * One service (chairtime `app/(pro)/menu/[id]/page.tsx`): price and time, how
 * the time is used step by step — the part that makes this schema worth
 * having, since time the pro is free inside their own appointment can be sold —
 * the rules, who does it at what price, and the extras. The hub the editing
 * hangs off: "Edit service" for what it is, "Change" on each part.
 */
@Composable
fun ServiceDetailScreen(id: String, app: AppModel, menu: MenuViewModel, wide: Boolean, onBack: () -> Unit) {
    val model = remember(id) { ServiceModel(id, app.client, app::handle) }
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(id) { mutableStateOf<Editing?>(null) }
    // A new editor each time one is opened: what was typed and abandoned last time is not this time's.
    var opened by rememberSaveable(id) { mutableStateOf("") }
    fun edit(which: Editing) { opened = UUID.randomUUID().toString(); editing = which }

    LaunchedEffect(id) { model.load() }

    val response = state.response
    val live = response != null && response.service.isArchived != true
    val isMine = response?.performers?.any { it.id == menu.myStaffId } == true

    Column(Modifier.fillMaxSize().background(WHColors.Bg).testTag("service")) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val twoColumns = maxWidth >= 700.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 26.dp else 18.dp).padding(bottom = 28.dp)) {
                if (!wide) CloseButton(onBack, Modifier.padding(top = 10.dp).testTag("serviceBack"), back = true, label = "Back to the menu")
                Column(Modifier.padding(top = if (wide) 24.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("Menu")
                    Text(response?.service?.name ?: " ", Modifier.semantics { heading() }.testTag("serviceHeading"), style = WHType.SheetName, color = WHColors.Ink)
                    if (response != null) {
                        Text(response.summaryLine(menu.currency), Modifier.testTag("serviceSummary"), style = WHType.Medium14.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = WHColors.Neutral700)
                        response.service.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.widthIn(max = 560.dp), style = WHType.Body, color = WHColors.Neutral800) }
                    }
                }
                state.failure?.let {
                    NoteCard(it, Modifier.padding(top = 16.dp))
                    if (response == null) WordsButton("Try again", { scope.launch { model.load() } })
                }
                if (response != null) {
                    Content(response, menu, twoColumns, canEdit = menu.isOwner && live, photo = { m ->
                        ServicePhotoSection(id, response.service.imageUrl, app.client, handle = { e -> app.handle(e) }, changed = { scope.launch { model.load() }; menu.load() }, modifier = m)
                    }, onEdit = ::edit)
                } else if (state.failure == null) {
                    Column(Modifier.padding(top = 20.dp).semantics { contentDescription = "Loading the service" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { SkeletonBlock(120.dp, radius = 12.dp) }
                    }
                }
            }
        }
        if (live) {
            // Beside a rail the page runs to the bottom edge, so the footer keeps clear of the system's gesture bar itself.
            val clear = if (wide) Modifier.navigationBarsPadding() else Modifier
            if (menu.isOwner) FooterBar(clear) { PrimaryButton("Edit service", { edit(Editing.Service) }, Modifier.widthIn(max = 604.dp).testTag("editService")) }
            // Anybody else is offered the one thing on it that is theirs: their own price for a service they already do.
            else if (isMine) FooterBar(clear) { PrimaryButton("Your price", { edit(Editing.Performers) }, Modifier.widthIn(max = 604.dp).testTag("yourPrice")) }
        }
    }

    // Each part of a service has its own screen, as on the web; each hands back the service as it now is.
    val which = editing
    if (which != null && response != null) {
        val took: (MenuServiceResponse) -> Unit = { model.took(it); editing = null; menu.load() }
        EditorSheet(onDismiss = { editing = null }) {
            when (which) {
                Editing.Service -> {
                    val form: ServiceFormViewModel = viewModel(key = "service-form-$opened") { ServiceFormViewModel(response, app.client, app::handle, createSavedStateHandle()) }
                    ServiceFormScreen(form, onSaved = took, onArchived = { editing = null; menu.archived() }, onClose = { editing = null })
                }
                Editing.Steps -> {
                    val steps: StepsViewModel = viewModel(key = "steps-$opened") { StepsViewModel(response, app.client, app::handle, createSavedStateHandle()) }
                    StepsEditorScreen(steps, onSaved = took, onClose = { editing = null })
                }
                Editing.Performers -> {
                    val performers: PerformersViewModel = viewModel(key = "performers-$opened") { PerformersViewModel(response, menu.isOwner, menu.myStaffId, app.client, app::handle) }
                    PerformersEditorScreen(performers, menu.currency, onSaved = took, onClose = { editing = null })
                }
                Editing.Extras -> {
                    val extras: ExtrasViewModel = viewModel(key = "extras-$opened") { ExtrasViewModel(response, app.client, app::handle) }
                    // Closed: whatever changed, the service's page should look again.
                    ExtrasEditorScreen(extras, menu.currency, onClose = {
                        editing = null
                        if (extras.state.value.changedAnything) { scope.launch { model.load() }; menu.load() }
                    })
                }
            }
        }
    }
}

@Composable
private fun Content(r: MenuServiceResponse, menu: MenuViewModel, twoColumns: Boolean, canEdit: Boolean, photo: @Composable (Modifier) -> Unit, onEdit: (Editing) -> Unit) {
    val currency = menu.currency
    Column(Modifier.padding(top = 20.dp)) {
        if (r.performers.isEmpty()) {
            // The one thing that stops a new service working, said where it cannot be missed.
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(shape).background(WHColors.Accent100).border(1.dp, WHColors.Accent300, shape).padding(16.dp).testTag("nobodyAssigned"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nobody can do this yet", style = WHType.RowName, color = WHColors.Accent)
                Text(
                    if (menu.isOwner) "A service needs at least one person assigned before it appears on your booking page or can be booked in the diary."
                    else "A service needs somebody assigned before it can be booked. Who does what is the owner's to set.",
                    style = WHType.Body, color = WHColors.Ink,
                )
                if (canEdit) InkButton("Choose who does it", { onEdit(Editing.Performers) }, Modifier.padding(top = 8.dp).testTag("choosePerformers"))
            }
        }

        @Composable
        fun left() = Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Price and time") {
                Fact("Price", r.service.priceLabel(currency), hint = "Quoted at consultation".takeIf { r.service.pricingMode == "hourly" })
                Fact("Duration", r.service.durationLabel)
                val deposit = r.service.depositPence?.formatted(currency) ?: r.service.depositPercent?.let { "$it%" }
                if (deposit != null) Fact("Deposit", deposit)
            }
            Panel("How the time is used", action = "Change".takeIf { canEdit }, actionTag = "changeSteps", onAction = { onEdit(Editing.Steps) }) {
                StepsBar(r.segments, Modifier.padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp))
                for (segment in r.segments) Fact(segment.name, Durations.short(segment.minutes), hint = "You are free — this time can be sold".takeIf { !segment.staffBusy }, accent = !segment.staffBusy)
                if (r.sellsFreeTime) PanelRow { Text("Takes ${Durations.short(r.totalMinutes)} of chair but only ${Durations.short(r.busyMinutes)} of your time.", style = WHType.Meta, color = WHColors.Accent) }
            }
            Panel("Rules") {
                Fact("Bookable online", if (r.service.bookableOnline) "Yes" else "No")
                r.service.prerequisiteName?.let { Fact("Needs first", it, hint = r.service.prerequisiteLeadHours?.let { h -> "at least $h hours before" }) }
                r.service.minAgeYears?.let { Fact("Minimum age", "$it", hint = "Checked against photo ID in person", accent = true) }
                if (r.service.requiresConsent) Fact("Consent form", "Required", accent = true)
                r.service.resourceTypeName?.let { Fact("Needs a", it) }
            }
        }

        @Composable
        fun right() = Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("Performed by", action = (if (r.performers.isEmpty()) "Add someone" else "Change").takeIf { canEdit }, actionTag = "changePerformers", onAction = { onEdit(Editing.Performers) }) {
                if (r.performers.isEmpty()) PanelRow { Text("Nobody is assigned, so this cannot be booked.", style = WHType.Body, color = WHColors.Neutral700) }
                else for (p in r.performers) Fact(p.name, p.pricePence?.formatted(currency) ?: "—", Modifier.testTag("performer"))
            }
            Panel("Extras", action = (if (r.addons.isEmpty()) "Add one" else "Change").takeIf { canEdit }, actionTag = "changeExtras", onAction = { onEdit(Editing.Extras) }) {
                if (r.addons.isEmpty()) PanelRow { Text("Nothing offered alongside this yet — a beard trim, a treatment, nail art.", style = WHType.Body, color = WHColors.Neutral700) }
                else for (a in r.addons) Fact(a.name, a.pricePence.formatted(currency), Modifier.testTag("addon"), hint = a.timeLabel)
            }
        }

        if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { left() }
            Box(Modifier.width(340.dp)) { right() }
        } else {
            left()
            Box(Modifier.padding(top = 16.dp)) { right() }
        }

        if (r.service.isArchived == true) {
            NoteCard("This has left the menu. It is kept because appointments refer to it.", Modifier.padding(top = 20.dp))
        } else if (canEdit) {
            photo(Modifier.padding(top = 20.dp))
        }
    }
}

/**
 * The service's steps as one bar: the pro's own time in ink, the time they are
 * free — and could sell — outlined in red, as the web's `StepsBar`.
 */
@Composable
fun StepsBar(segments: List<MenuServiceResponse.Segment>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    val said = "${segments.size} ${if (segments.size == 1) "step" else "steps"}"
    Row(modifier.fillMaxWidth().height(10.dp).clearAndSetSemantics { contentDescription = said }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (segment in segments) {
            Box(
                Modifier.weight(segment.minutes.coerceAtLeast(1).toFloat()).height(10.dp).clip(shape)
                    .then(if (segment.staffBusy) Modifier.background(WHColors.Ink) else Modifier.background(WHColors.Accent100).border(1.dp, WHColors.Accent, shape)),
            )
        }
    }
}
