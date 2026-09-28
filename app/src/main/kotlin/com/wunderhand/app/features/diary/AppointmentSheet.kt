package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wunderhand.core.AppointmentDetail
import com.wunderhand.core.AppointmentResponse
import com.wunderhand.core.Bill
import com.wunderhand.core.DiaryWords
import com.wunderhand.core.Durations
import com.wunderhand.app.features.booking.NewBookingStart
import com.wunderhand.core.ActionWords
import com.wunderhand.core.Arrival
import com.wunderhand.core.CloseOutcome
import com.wunderhand.core.RepeatOptions
import com.wunderhand.core.SeriesView
import com.wunderhand.core.ShopClock
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.InkButton
import com.wunderhand.app.features.money.TillSheet
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.WordsButton
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.liftSmall
import com.wunderhand.network.ApiError
import com.wunderhand.network.WunderhandApi
import kotlinx.coroutines.launch
import java.net.URLEncoder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import com.wunderhand.design.WHField

/**
 * One appointment, opened over the diary (chairtime
 * `components/diary/AppointmentDetail.tsx`).
 *
 * The order is the order of the job: who and when, then the money — "what do
 * I take?" is asked with the client at the desk — then anything that has to
 * happen before they sit down, what is known about them, and last whether it
 * repeats. The actions sit pinned underneath: the one filled button means
 * forward; ending an appointment badly is quiet red words.
 *
 * @param changed the day underneath is no longer what it was.
 */
@Composable
fun AppointmentSheet(
    id: String, api: WunderhandApi, clock: ShopClock, changed: suspend () -> Unit, handle: suspend (ApiError) -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier, onRebook: (NewBookingStart) -> Unit = {},
    /** Name the outlet on the sheet: only at a shop with more than one. */
    namesOutlet: Boolean = false,
) {
    val model = remember(id) { AppointmentModel(id, api, clock, changed, handle) }
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    // Moving it is a second screen within the sheet, swapped in place.
    var isRescheduling by rememberSaveable(id) { mutableStateOf(false) }
    var isTilling by rememberSaveable(id) { mutableStateOf(false) }

    LaunchedEffect(id) { model.load() }

    val response = state.response
    if (isRescheduling && response != null) {
        RescheduleScreen(response.appointment, model, state, onDone = { isRescheduling = false; model.forgetSlots() }, modifier)
        return
    }

    Column(modifier.fillMaxSize().background(WHColors.Bg).testTag("appointmentSheet")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close appointment" }.testTag("closeAppointment"),
                contentAlignment = Alignment.Center,
            ) { WHIcon(WHIcons.X, size = 18.dp, tint = WHColors.Neutral700) }
            Text(response?.let { clock.weekdayDayMonth(it.appointment.startsAt) }.orEmpty(), Modifier.weight(1f), style = WHType.Summary.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Ink, maxLines = 1)
            response?.let { StatusChip(it.appointment.status, it.appointment.isInTheChair(now), Modifier.testTag("appointmentStatus")) }
        }
        RowDivider()

        when {
            response != null -> {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 24.dp)) {
                    state.notice?.let { NoticeLine(it, Modifier.padding(bottom = 16.dp)) }
                    AppointmentBody(response, model, state, namesOutlet)
                }
                AppointmentActions(response, model, state, now, onReschedule = { isRescheduling = true }, onTill = { isTilling = true }, onRebook)
            }
            state.loadFailure != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.loadFailure.orEmpty(), style = WHType.Body, color = WHColors.Neutral800)
                TextButton("Try again", WHColors.Ink, { scope.launch { model.load() } })
            }
            else -> Column(Modifier.padding(20.dp).semantics { contentDescription = "Loading the appointment" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(12.dp, width = 180.dp)
                SkeletonBlock(28.dp, width = 220.dp)
                SkeletonBlock(12.dp, width = 160.dp)
                SkeletonBlock(120.dp, Modifier.padding(top = 14.dp), radius = 12.dp)
                SkeletonBlock(70.dp, Modifier.padding(top = 8.dp), radius = 12.dp)
            }
        }
    }

    // Rung through: this appointment is done now, and so the day underneath is not what it was.
    if (isTilling && response != null) {
        TillSheet(api, handle, response.appointment.bookingId, clock, onSettled = { model.load(); changed() }, onClose = { isTilling = false })
    }
}

/** What happened, or why it did not: a dot and a sentence above the appointment. */
@Composable
fun NoticeLine(notice: Notice, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().then(if (notice.isProblem) Modifier else Modifier.liftSmall(shape)).clip(shape)
            .background(if (notice.isProblem) WHColors.Accent100 else WHColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp)
            // Said as it appears, so somebody who cannot see the sheet change still hears that it did.
            .testTag("sheetNotice").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(if (notice.isProblem) WHColors.Accent else WHColors.Ink))
        Text(notice.text, Modifier.weight(1f), style = WHType.Summary, color = WHColors.Ink)
    }
}

/**
 * What can be done to the appointment now, pinned under it (chairtime
 * AppointmentDetail's footer). The till for whoever sees the money; Mark done for everyone.
 */
@Composable
private fun AppointmentActions(response: AppointmentResponse, model: AppointmentModel, state: AppointmentState, now: Instant, onReschedule: () -> Unit, onTill: () -> Unit, onRebook: (NewBookingStart) -> Unit) {
    val appt = response.appointment
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<CloseOutcome?>(null) }
    val idle = state.busy == null

    RowDivider()
    Column(Modifier.fillMaxWidth().background(WHColors.Bg).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 14.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (appt.isClosed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ActionWords.closedLine(appt.status, appt.completedAt, appt.startsAt, model.clock), Modifier.weight(1f).padding(vertical = 6.dp).testTag("closedLine"), style = WHType.Summary, color = WHColors.Neutral700)
                // Somebody to book again; a walk-in has nobody.
                if (appt.clientId != null) InkButton("Book them again", { onRebook(NewBookingStart(clientId = appt.clientId, clientName = appt.clientName)) }, Modifier.testTag("bookThemAgain"))
            }
            return@Column
        }

        val toTake = response.bill?.toTakePence
        if (Arrival.hasArrived(appt.startsAt, now)) {
            if (toTake != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The forward button is the till: it takes the money down and marks it done in one.
                PrimaryButton("Check out · ${toTake.formatted(appt.currency)}", onTill, Modifier.weight(1f).testTag("checkOut"), enabled = idle)
                SecondaryButton("Mark done", { scope.launch { model.close(CloseOutcome.Completed) } }, Modifier.testTag("markDone"), enabled = idle, loading = state.busy == SheetAction.Done)
            } else {
                PrimaryButton("Mark done", { scope.launch { model.close(CloseOutcome.Completed) } }, Modifier.testTag("markDone"), enabled = idle, loading = state.busy == SheetAction.Done)
            }
        } else {
            // Not offered rather than offered and refused: marking a future
            // appointment done counts a visit that has not happened.
            val opens = model.clock.time(appt.startsAt.minus(Duration.ofMinutes(Arrival.EARLY_MINUTES)))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = WHColors.Ink)) { append(if (response.seesMoney) "Check out and Mark done open when they arrive" else "Mark done opens when they arrive") }
                    append(" — from $opens on the day.")
                },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WHColors.Well).padding(horizontal = 16.dp, vertical = 12.dp).testTag("opensLater"),
                style = WHType.Summary, color = WHColors.Neutral700,
            )
        }

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WordsButton("Reschedule", onReschedule, Modifier.testTag("reschedule"), enabled = idle)
            Row {
                if (Arrival.hasStarted(appt.startsAt, now)) {
                    WordsButton("Did not turn up", { confirming = CloseOutcome.NoShow }, Modifier.testTag("noShow"), color = WHColors.Accent, enabled = idle, loading = state.busy == SheetAction.NoShow)
                }
                WordsButton("Cancel appointment", { confirming = CloseOutcome.Cancelled }, Modifier.testTag("cancelAppointment"), color = WHColors.Accent, enabled = idle, loading = state.busy == SheetAction.Cancel)
            }
        }
    }

    confirming?.let { outcome ->
        val first = appt.clientName?.substringBefore(' ') ?: "the client"
        ConfirmDialog(
            title = if (outcome == CloseOutcome.NoShow) "${appt.displayName} did not turn up?" else "Cancel ${appt.displayName}’s appointment?",
            message = if (outcome == CloseOutcome.NoShow) "This cancels the booking, counts against $first and keeps any deposit they paid."
            else "It comes out of the diary, and a deposit paid online is refunded in full.",
            confirm = if (outcome == CloseOutcome.NoShow) "Record a no-show" else "Cancel appointment",
            onConfirm = { scope.launch { model.close(outcome) } },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun AppointmentBody(response: AppointmentResponse, model: AppointmentModel, state: AppointmentState, namesOutlet: Boolean = false) {
    val clock = model.clock
    val appt = response.appointment
    val currency = appt.currency

    // "14:30–16:00 · 1h 30m · with Dez"
    Text(
        "${clock.time(appt.startsAt)}–${clock.time(appt.endsAt)} · ${Durations.short(appt.minutes)}" + (appt.staffName?.let { " · with $it" } ?: ""),
        style = WHType.CardMeta.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Neutral700,
    )
    Text(appt.displayName, Modifier.padding(top = 8.dp).semantics { heading() }.testTag("appointmentName"), style = WHType.SheetName, color = WHColors.Ink)
    // "Root tint · 9th visit · usually every 6 weeks"
    val about = DiaryWords.about(appt.clientVisitCount, appt.clientId != null, appt.averageIntervalDays)
    Text(if (about.isEmpty()) appt.serviceName else "${appt.serviceName} · $about", Modifier.padding(top = 6.dp), style = WHType.Medium14, color = WHColors.Neutral700)
    // Which outlet, where the shop has more than one; a home visit says where below instead.
    val outlet = appt.outletName
    if (namesOutlet && !appt.isAtClient && outlet != null) {
        Text("At $outlet", Modifier.padding(top = 4.dp).testTag("appointmentOutlet"), style = WHType.Medium14, color = WHColors.Neutral700)
    }

    if (appt.isAtClient) WhereSection(appt, Modifier.padding(top = 20.dp))
    response.bill?.let { BillCard(it, appt, Modifier.padding(top = 20.dp)) }
    if (appt.depositState == "uncollected" && !appt.isClosed) MissedDeposit(response, Modifier.padding(top = 12.dp))
    appt.project?.let { ProjectCard(it, currency, Modifier.padding(top = 12.dp)) }
    if (appt.clientId == null && appt.status !in setOf("cancelled", "expired", "held")) AddClientSection(model, state, Modifier.padding(top = 20.dp))
    if (appt.needsConsent && appt.clientId != null && !appt.isClosed) ConsentSection(response, model, state, Modifier.padding(top = 20.dp))
    if (response.replies.isNotEmpty()) RepliesSection(response, clock, Modifier.padding(top = 20.dp))
    if (appt.standingFormula != null || appt.clientNotes != null) OnFileSection(appt, Modifier.padding(top = 20.dp))
    RepeatSection(response, model, state, Modifier.padding(top = 20.dp))
}

@Composable
fun SheetSection(title: String, modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().then(if (accent) Modifier else Modifier.liftSmall(shape)).clip(shape)
            .background(if (accent) WHColors.Accent100 else WHColors.Surface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), Modifier.semantics { contentDescription = title; heading() }, style = WHType.FieldLabel, color = if (accent) WHColors.Accent else WHColors.Eyebrow)
        content()
    }
}

@Composable
private fun WhereSection(appt: AppointmentDetail, modifier: Modifier = Modifier) {
    val links = LocalUriHandler.current
    SheetSection("At the client's", modifier) {
        val lines = listOfNotNull(appt.clientAddress, appt.clientPostcode).filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            Text("No address was given. Ask the client before you set off.", style = WHType.Medium14, color = WHColors.Neutral700)
        } else {
            SelectionContainer { Text(lines.joinToString("\n"), style = WHType.FieldValue, color = WHColors.Ink) }
            // A geo: link, so it opens in whichever maps app this phone uses.
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                    runCatching { links.openUri("geo:0,0?q=" + URLEncoder.encode(lines.joinToString(", "), "UTF-8")) }
                }.testTag("directions"),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Directions", style = WHType.Semi14, color = WHColors.Ink, textDecoration = TextDecoration.Underline) }
        }
        appt.travelMinutes?.takeIf { it > 0 }?.let {
            Text("${Durations.short(it)} kept free afterwards for travel.", style = WHType.Meta, color = WHColors.Neutral700)
        }
    }
}

@Composable
private fun BillCard(bill: Bill, appt: AppointmentDetail, modifier: Modifier = Modifier) {
    val currency = appt.currency
    WHCard(modifier.testTag("bill")) {
        appt.lineItems.forEachIndexed { index, item ->
            // With a discount shown beneath it, the service is at its list
            // price — otherwise the lines would not add up.
            val price = if (bill.discountPence.value > 0 && appt.lineItems.size == 1) appt.listPricePence ?: item.pricePence else item.pricePence
            if (index > 0) RowDivider()
            BillLine(item.name, price?.formatted(currency).orEmpty(), muted = false)
        }
        if (bill.discountPence.value > 0) {
            RowDivider()
            BillLine(appt.promotionName ?: "Off-peak price", "−${bill.discountPence.formatted(currency)}", muted = true)
        }
        val deposit = appt.depositPence
        if (bill.depositPaid && deposit != null) {
            RowDivider()
            BillLine("Deposit paid online", "−${deposit.formatted(currency)}", muted = true)
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().background(WHColors.Block).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (appt.isClosed) "Total" else "To take in the shop", Modifier.weight(1f), style = WHType.Semi14, color = WHColors.Ink)
            Text((if (appt.isClosed) bill.chargePence else bill.toTakePence).formatted(currency), Modifier.testTag("toTake"), style = WHType.SheetTotal, color = WHColors.Ink)
        }
    }
}

@Composable
private fun BillLine(label: String, value: String, muted: Boolean) {
    val color = if (muted) WHColors.Neutral700 else WHColors.Ink
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = WHType.Medium14, color = color)
        Text(value, style = if (muted) WHType.Medium14 else WHType.Semi14, color = color, maxLines = 1)
    }
}

@Composable
private fun MissedDeposit(response: AppointmentResponse, modifier: Modifier = Modifier) {
    val appt = response.appointment
    val deposit = appt.depositPence
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WHColors.Accent100).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(WHColors.Accent))
        Text(
            buildAnnotatedString {
                val semi = SpanStyle(fontWeight = FontWeight.SemiBold)
                if (response.seesMoney && deposit != null) {
                    withStyle(semi) { append("The ${deposit.formatted(appt.currency)} deposit was not taken") }
                    append(" — Stripe could not charge when they booked. It is included in what to take.")
                } else {
                    withStyle(semi) { append("The deposit was not taken") }
                    append(" — Stripe could not charge when they booked.")
                }
            },
            Modifier.weight(1f), style = WHType.Summary, color = WHColors.Ink,
        )
    }
}

@Composable
private fun ProjectCard(project: AppointmentDetail.Project, currency: String, modifier: Modifier = Modifier) {
    SheetSection("Sitting ${project.sittingNumber}", modifier) {
        Text(project.name, style = WHType.Button, color = WHColors.Ink)
        if (project.depositPence.value > 0) {
            val deposit = project.depositPence.formatted(currency)
            Text(
                if (project.isFinalBooked) "Last one booked — the $deposit deposit comes off today." else "$deposit deposit is held against the last sitting, not this one.",
                style = WHType.CardMeta, color = WHColors.Neutral700,
            )
        }
    }
}

/** A form still to go through, signed in the chair. The words themselves are
 *  shown: a button beside no wording would attest to a document nobody saw. */
/** A walk-in gave nothing about themselves; this is where they can. Their name is enough — a phone or email is what makes them findable next time. */
@Composable
private fun AddClientSection(model: AppointmentModel, state: AppointmentState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    SheetSection("Who was it?", modifier.testTag("addWalkIn")) {
        Text("A walk-in with a name is a client: the visit counts, and there is somebody to book again.", style = WHType.Summary, color = WHColors.Ink)
        WHField("Name", name, { name = it }, inputModifier = Modifier.testTag("walkInName"), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), placeholder = "Their name")
        WHField("Mobile", phone, { phone = it }, inputModifier = Modifier.testTag("walkInPhone"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), placeholder = "07…")
        WHField("Email", email, { email = it }, inputModifier = Modifier.testTag("walkInEmail"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), placeholder = "Optional")
        InkButton("Add to clients", { focus.clearFocus(); scope.launch { model.addClient(name.trim(), phone, email) } }, Modifier.fillMaxWidth().testTag("addWalkInSave"), enabled = state.busy == null && name.isNotBlank(), loading = state.busy == SheetAction.AddClient)
    }
}

@Composable
private fun ConsentSection(response: AppointmentResponse, model: AppointmentModel, state: AppointmentState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    SheetSection("Consent form still needed", modifier.testTag("consent"), accent = true) {
        val wording = response.consentWording
        if (wording == null) {
            Text(
                "Your medical history wording is not set up yet, so there is nothing to record consent against. Add the form your insurer or licence expects on the web, then come back.",
                style = WHType.Summary, color = WHColors.Ink,
            )
        } else {
            val first = response.appointment.clientName?.substringBefore(' ') ?: "the client"
            Text("Go through this with $first and record it once they have signed.", style = WHType.Summary, color = WHColors.Ink)
            // Scrollable rather than cut short: an abridged consent form is not the form.
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(WHColors.Bg).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(wording.body, style = WHType.CardMeta.copy(lineHeight = WHType.Body.lineHeight), color = WHColors.Ink)
            }
            Text("Version ${wording.version} — what gets recorded against their name.", style = WHType.Meta, color = WHColors.Neutral700)
            InkButton("They have read and signed this", { scope.launch { model.recordConsent() } }, Modifier.fillMaxWidth().testTag("recordConsent"), enabled = state.busy == null, loading = state.busy == SheetAction.Consent)
        }
    }
}

@Composable
private fun RepliesSection(response: AppointmentResponse, clock: ShopClock, modifier: Modifier = Modifier) {
    SheetSection(if (response.replies.size == 1) "Reply from the client" else "Replies from the client", modifier) {
        for (reply in response.replies) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(clock.dayMonthTime(reply.receivedAt) + (reply.subject?.let { " · $it" } ?: ""), style = WHType.Meta, color = WHColors.Neutral700)
                val body = reply.body
                if (body != null) SelectionContainer { Text(body, style = WHType.Medium14, color = WHColors.Ink) }
                else Text("They replied, but the text could not be fetched. It is in the ${reply.fromEmail} thread in your mail.", style = WHType.Summary, color = WHColors.Neutral700)
            }
        }
    }
}

@Composable
private fun OnFileSection(appt: AppointmentDetail, modifier: Modifier = Modifier) {
    SheetSection("On file", modifier) {
        for ((label, text) in listOf("Formula" to appt.standingFormula, "Notes" to appt.clientNotes)) {
            if (text == null) continue
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = WHType.Meta.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Neutral700)
                SelectionContainer { Text(text, style = WHType.Medium14, color = WHColors.Ink) }
            }
        }
    }
}

/**
 * "Same time in N weeks?" and, once repeating, the dates booked, the dates
 * that could not be, and the way to stop (chairtime `SeriesCard.tsx`).
 */
@Composable
private fun RepeatSection(response: AppointmentResponse, model: AppointmentModel, state: AppointmentState, modifier: Modifier = Modifier) {
    val appt = response.appointment
    // Nothing to offer a walk-in, or an appointment that did not happen.
    val canRepeat = appt.clientId != null && appt.status !in setOf("cancelled", "no_show", "expired", "held") && response.repeatOptions != null
    val series = response.series
    when {
        series != null -> SeriesCard(series, response, canRepeat, model, state, modifier)
        canRepeat -> SheetSection("Book this again?", modifier.testTag("repeat")) { RepeatForm(response.repeatOptions!!, model, state) }
    }
}

@Composable
private fun RepeatForm(options: RepeatOptions, model: AppointmentModel, state: AppointmentState) {
    val scope = rememberCoroutineScope()
    var weeks by rememberSaveable { mutableStateOf<Int?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val chosen = weeks ?: options.suggestedWeeks
    val shape = RoundedCornerShape(9.dp)

    Text("Same time, same person. The next ${options.keepAhead} are booked now, and another as each one passes.", style = WHType.CardMeta, color = WHColors.Neutral700)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(WHColors.Bg).border(1.dp, WHColors.Divider, shape)
                    .clickable(role = Role.DropdownList) { choosing = true }
                    .semantics { contentDescription = "How often: ${RepeatOptions.label(chosen)}" }.padding(horizontal = 12.dp).testTag("repeatEvery"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(RepeatOptions.label(chosen), Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.Medium14, color = WHColors.Ink)
                WHIcon(WHIcons.ChevronsUpDown, size = 14.dp, tint = WHColors.Ink)
            }
            DropdownMenu(choosing, onDismissRequest = { choosing = false }, containerColor = WHColors.Surface) {
                for (option in options.intervals) {
                    DropdownMenuItem(text = { Text(RepeatOptions.label(option), style = WHType.Medium14, color = WHColors.Ink) }, onClick = { weeks = option; choosing = false })
                }
            }
        }
        InkButton("Repeat", { scope.launch { model.startRepeat(chosen) } }, Modifier.testTag("startRepeat"), enabled = state.busy == null, loading = state.busy == SheetAction.RepeatStart)
    }
}

@Composable
private fun SeriesCard(series: SeriesView, response: AppointmentResponse, canRepeat: Boolean, model: AppointmentModel, state: AppointmentState, modifier: Modifier = Modifier) {
    val appt = response.appointment
    val clock = model.clock
    val scope = rememberCoroutineScope()
    var confirmingCancel by remember { mutableStateOf(false) }
    val booked = series.upcoming.filter { it.appointmentId != appt.id }
    val title = if (series.active) "Repeats · ${RepeatOptions.label(series.intervalWeeks).lowercase()}" else DiaryWords.seriesEnded(series.endedReason, series.staffName)

    SheetSection(title, modifier.testTag("series")) {
        for (occurrence in booked) {
            Row {
                Text(clock.shortDay(occurrence.startsAt), Modifier.weight(1f), style = WHType.Body, color = WHColors.Ink)
                Text(series.localTime, style = WHType.Body, color = WHColors.Neutral700)
            }
        }
        if (series.skipped.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WHColors.Accent100).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not booked", style = WHType.CardMeta.copy(fontWeight = FontWeight.Medium), color = WHColors.Accent)
                for (skip in series.skipped) {
                    Text("${clock.shortDay(skip.at)} — ${DiaryWords.skipReason(skip.reason, series.staffName)}. Book another time by hand if they still want it.", style = WHType.CardMeta, color = WHColors.Accent)
                }
            }
        }
        if (series.active) {
            Column {
                WordsButton("Stop repeating", { scope.launch { model.stopRepeat(cancelUpcoming = false) } }, Modifier.testTag("stopRepeat"), enabled = state.busy == null, loading = state.busy == SheetAction.RepeatStop)
                if (series.after.isNotEmpty()) {
                    val count = if (series.after.size == 1) "one" else "${series.after.size}"
                    WordsButton("Stop and cancel the $count booked after this", { confirmingCancel = true }, color = WHColors.Accent, enabled = state.busy == null, loading = state.busy == SheetAction.RepeatStopAndCancel)
                }
            }
        } else if (canRepeat) {
            // Stopped is not final: the same appointment can start again.
            RepeatForm(response.repeatOptions!!, model, state)
        }
    }

    if (confirmingCancel) {
        ConfirmDialog(
            title = "Stop repeating and cancel the ${series.after.size} booked after this?",
            message = "Those appointments come out of the diary. This one stays.",
            confirm = "Stop and cancel them", keep = "Keep them",
            onConfirm = { scope.launch { model.stopRepeat(cancelUpcoming = true) } },
            onDismiss = { confirmingCancel = false },
        )
    }
}

@Composable
fun StatusChip(status: String, inTheChair: Boolean, modifier: Modifier = Modifier) {
    val chip = DiaryWords.status(status, inTheChair)
    val (ink, ground) = when (chip.tone) {
        DiaryWords.StatusTone.Ink -> WHColors.Bg to WHColors.Ink
        DiaryWords.StatusTone.Red -> WHColors.Accent to WHColors.Accent100
        DiaryWords.StatusTone.Grey -> WHColors.Neutral700 to WHColors.Well
        DiaryWords.StatusTone.Plain -> WHColors.Ink to WHColors.Surface
    }
    Text(
        chip.label,
        modifier.clip(CircleShape).background(ground)
            .then(if (chip.tone == DiaryWords.StatusTone.Plain) Modifier.border(1.dp, WHColors.Divider, CircleShape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = WHType.Tag.copy(fontSize = WHType.Meta.fontSize), color = ink, maxLines = 1,
    )
}
