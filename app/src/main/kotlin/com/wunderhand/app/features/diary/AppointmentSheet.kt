package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import com.wunderhand.core.Loadable
import com.wunderhand.core.RepeatOptions
import com.wunderhand.core.SeriesView
import com.wunderhand.core.ShopClock
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.liftSmall
import com.wunderhand.network.ApiError
import com.wunderhand.network.DiaryApi
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.Instant

/**
 * One appointment, opened over the diary (chairtime
 * `components/diary/AppointmentDetail.tsx`).
 *
 * The order is the order of the job: who and when, then the money — "what do
 * I take?" is asked with the client at the desk — then anything that has to
 * happen before they sit down, what is known about them, and last whether it
 * repeats. What can be done to it — check out, move, cancel — arrives with
 * the diary's actions, in A2.
 */
@Composable
fun AppointmentSheet(id: String, api: DiaryApi, clock: ShopClock, handle: suspend (ApiError) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var loaded by remember(id) { mutableStateOf<Loadable<AppointmentResponse>>(Loadable.Loading) }
    val scope = rememberCoroutineScope()
    val now by rememberNow()

    suspend fun load() {
        loaded = Loadable.Loading
        loaded = try {
            Loadable.Loaded(api.appointment(id))
        } catch (error: ApiError) {
            if (error is ApiError.Unauthorized || error is ApiError.NotMember || error is ApiError.UpgradeRequired) handle(error)
            Loadable.Failed(error.message)
        }
    }
    LaunchedEffect(id) { load() }

    Column(modifier.fillMaxSize().background(WHColors.Bg).testTag("appointmentSheet")) {
        val response = loaded.valueOrNull
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close appointment" }.testTag("closeAppointment"),
                contentAlignment = Alignment.Center,
            ) { WHIcon(WHIcons.X, size = 18.dp, tint = WHColors.Neutral700) }
            Text(response?.let { clock.weekdayDayMonth(it.appointment.startsAt) }.orEmpty(), Modifier.weight(1f), style = WHType.Summary.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Ink, maxLines = 1)
            response?.let { StatusChip(it.appointment.status, it.appointment.isInTheChair(now), Modifier.testTag("appointmentStatus")) }
        }
        RowDivider()

        when (val state = loaded) {
            is Loadable.Loaded -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 32.dp)) {
                AppointmentBody(state.value, clock)
            }
            is Loadable.Failed -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.message, style = WHType.Body, color = WHColors.Neutral800)
                TextButton("Try again", WHColors.Ink, { scope.launch { load() } })
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
}

@Composable
private fun AppointmentBody(response: AppointmentResponse, clock: ShopClock) {
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

    if (appt.isAtClient) WhereSection(appt, Modifier.padding(top = 20.dp))
    response.bill?.let { BillCard(it, appt, Modifier.padding(top = 20.dp)) }
    if (appt.depositState == "uncollected" && !appt.isClosed) MissedDeposit(response, Modifier.padding(top = 12.dp))
    appt.project?.let { ProjectCard(it, currency, Modifier.padding(top = 12.dp)) }
    if (appt.needsConsent && appt.clientId != null && !appt.isClosed) ConsentSection(response, Modifier.padding(top = 20.dp))
    if (response.replies.isNotEmpty()) RepliesSection(response, clock, Modifier.padding(top = 20.dp))
    if (appt.standingFormula != null || appt.clientNotes != null) OnFileSection(appt, Modifier.padding(top = 20.dp))
    response.series?.let { SeriesCard(it, appt, clock, Modifier.padding(top = 20.dp)) }
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
 *  shown; recording that they signed arrives with the diary's actions, in A2. */
@Composable
private fun ConsentSection(response: AppointmentResponse, modifier: Modifier = Modifier) {
    SheetSection("Consent form still needed", modifier, accent = true) {
        val wording = response.consentWording
        if (wording == null) {
            Text(
                "Your medical history wording is not set up yet, so there is nothing to record consent against. Add the form your insurer or licence expects on the web, then come back.",
                style = WHType.Summary, color = WHColors.Ink,
            )
        } else {
            val first = response.appointment.clientName?.substringBefore(' ') ?: "the client"
            Text("Go through this with $first before they sit down.", style = WHType.Summary, color = WHColors.Ink)
            // Scrollable rather than cut short: an abridged consent form is not the form.
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(WHColors.Bg).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(wording.body, style = WHType.CardMeta.copy(lineHeight = WHType.Body.lineHeight), color = WHColors.Ink)
            }
            Text("Version ${wording.version} — what gets recorded against their name.", style = WHType.Meta, color = WHColors.Neutral700)
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

/** Once repeating: the dates booked and the dates that could not be
 *  (chairtime `SeriesCard.tsx`). Starting and stopping a repeat is A2's. */
@Composable
private fun SeriesCard(series: SeriesView, appt: AppointmentDetail, clock: ShopClock, modifier: Modifier = Modifier) {
    val booked = series.upcoming.filter { it.appointmentId != appt.id }
    val title = if (series.active) "Repeats · ${RepeatOptions.label(series.intervalWeeks).lowercase()}" else DiaryWords.seriesEnded(series.endedReason, series.staffName)
    SheetSection(title, modifier.testTag("series")) {
        for (occurrence in booked) {
            Row {
                Text(clock.shortDay(occurrence.startsAt), Modifier.weight(1f), style = WHType.Body, color = WHColors.Ink)
                Text(series.localTime, style = WHType.Body, color = WHColors.Neutral700)
            }
        }
        if (booked.isEmpty() && series.skipped.isEmpty()) Text("Nothing further is booked yet.", style = WHType.CardMeta, color = WHColors.Neutral700)
        if (series.skipped.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WHColors.Accent100).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not booked", style = WHType.CardMeta.copy(fontWeight = FontWeight.Medium), color = WHColors.Accent)
                for (skip in series.skipped) {
                    Text("${clock.shortDay(skip.at)} — ${DiaryWords.skipReason(skip.reason, series.staffName)}. Book another time by hand if they still want it.", style = WHType.CardMeta, color = WHColors.Accent)
                }
            }
        }
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
