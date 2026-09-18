package com.wunderhand.app.features.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.app.features.shell.EditorSheet
import com.wunderhand.core.Arrival
import com.wunderhand.core.CheckoutResponse
import com.wunderhand.core.MoneyWords
import com.wunderhand.core.Receipt
import com.wunderhand.core.ShopClock
import com.wunderhand.core.TillMethod
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.Hint
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHSegmented
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall
import com.wunderhand.network.ApiError
import com.wunderhand.network.MoneyApi
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID

/** The till, in a sheet over the appointment it rings through. [onSettled]: the appointment behind is done now. */
@Composable
fun TillSheet(api: MoneyApi, handle: suspend (ApiError) -> Unit, bookingId: String, clock: ShopClock, onSettled: suspend () -> Unit, onClose: () -> Unit) {
    EditorSheet(onDismiss = onClose) {
        val model: TillViewModel = viewModel(key = "till-$bookingId-${rememberSaveable { UUID.randomUUID().toString() }}") { TillViewModel(bookingId, api, handle, createSavedStateHandle()) }
        TillScreen(model, clock, onSettled, onClose)
    }
}

/**
 * The till (chairtime `app/(pro)/checkout/[id]/page.tsx`). Not a payment
 * screen: it records what was settled and marks the appointment done.
 */
@Composable
private fun TillScreen(model: TillViewModel, clock: ShopClock, onSettled: suspend () -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val view = state.view
    // The till opens half an hour before the start; a sheet left up across that moment should open with it.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val arrived = view?.hasArrived(now) == true

    EditorPage(
        title = view?.displayName ?: " ", onClose = onClose, tag = "till", intro = view?.serviceName,
        eyebrow = view?.let { "Checkout · ${clock.time(it.startsAt)} · ${it.staffName}" } ?: "Checkout",
        footer = when {
            view == null -> null
            view.isSettled -> ({ PrimaryButton("Back to the diary", onClose, Modifier.testTag("tillDone")) })
            arrived -> ({ PrimaryButton("Mark paid and finish", { focus.clearFocus(); model.settle(onSettled) }, Modifier.testTag("markPaid"), loading = state.isSettling) })
            else -> null
        },
    ) {
        state.failure?.let { NoteCard(it, Modifier.padding(top = 16.dp)); if (view == null) WordsButton("Try again", { model.load() }) }
        when {
            view == null -> if (state.failure == null) Column(Modifier.padding(top = 24.dp).semantics { contentDescription = "Loading the bill" }, verticalArrangement = Arrangement.spacedBy(10.dp)) { repeat(3) { SkeletonBlock(52.dp, radius = 10.dp) } }
            view.receipt != null && view.settledAt != null -> Settled(view, view.receipt!!, view.settledAt!!, clock, now, state.problem)
            // Checking out marks the appointment done and counts the visit, so it waits until the client is here.
            !arrived -> {
                val shape = RoundedCornerShape(12.dp)
                Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(shape).background(WHColors.Accent100).padding(16.dp).testTag("tillTooEarly"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This has not started yet", style = WHType.RowName, color = WHColors.Accent)
                    Text("It can be checked out from ${Arrival.EARLY_MINUTES} minutes before it starts, at ${clock.time(view.openFrom)} on ${clock.weekdayDayMonth(view.startsAt)}.", style = WHType.Body, color = WHColors.Ink)
                }
            }
            else -> Open(view, state, model)
        }
    }
}

@Composable
private fun Open(view: CheckoutResponse, state: TillState, model: TillViewModel) {
    val currency = view.currency
    val o = view.owing
    // As it is typed; while what is typed is not money, the bill as chairtime sent it.
    val live = state.owing ?: o
    Column(Modifier.padding(top = 24.dp)) {
        BillLine("The booking", o.subtotalPence.formatted(currency))
        if (o.paidBeforePence.value > 0) BillLine("Paid already", "−${o.paidBeforePence.formatted(currency)}", hint = "Deposit taken when they booked, less anything refunded")
        if (live.extraPence.value > 0) BillLine("Sold in the chair", live.extraPence.formatted(currency))
        if (live.tipPence.value > 0) BillLine("Tip", live.tipPence.formatted(currency))
        BillLine(if (live.tipPence.value > 0) "To pay, tip included" else "To pay", live.duePence.formatted(currency), strong = true, tag = "tillDue")
    }
    // The price fell after the deposit was taken. Saying "nothing to pay" would hide money that belongs to the client.
    if (live.overpaidPence.value > 0) NoteCard("They have paid ${live.overpaidPence.formatted(currency)} more than the bill came to. Give it back through your Stripe dashboard — nothing here can move money.", Modifier.padding(top = 16.dp).testTag("tillOverpaid"))
    state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("tillProblem")) }

    val amounts = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WHField("Anything else sold", state.extra, { v -> model.edit { it.copy(extra = v) } }, inputModifier = Modifier.testTag("till-extra"), keyboardOptions = amounts, prefix = "£")
        Hint("A tin of pomade, a bottle of something. Leave blank if not.")
        WHField("What was it", state.extraNote, { v -> model.edit { it.copy(extraNote = v) } }, inputModifier = Modifier.testTag("till-note"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), placeholder = "Pomade")
        WHField("Tip", state.tip, { v -> model.edit { it.copy(tip = v) } }, inputModifier = Modifier.testTag("till-tip"), keyboardOptions = amounts, prefix = "£")
        Eyebrow("How they paid", Modifier.padding(top = 14.dp, start = 4.dp))
        WHSegmented(TillMethod.entries.map { it to it.label }, state.method, { m -> model.edit { it.copy(method = m) } }, Modifier.testTag("tillMethod"), compact = false)
        Hint("Recorded, not taken. The money goes through your own machine or your own hand — this is the note of it.")
    }
}

/** Already rung through. Showing the till again invites a second press, and a booking checked out twice is a day's takings twice. */
@Composable
private fun Settled(view: CheckoutResponse, r: Receipt, at: Instant, clock: ShopClock, now: Instant, problem: String?) {
    val currency = view.currency
    val shape = RoundedCornerShape(12.dp)
    problem?.let { NoteCard(it, Modifier.padding(top = 16.dp).testTag("tillProblem")) }
    Column(Modifier.padding(top = 24.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(18.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("tillSettled"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Already checked out", style = WHType.RowName, color = WHColors.Ink)
        Text("Settled at ${clock.time(at)}${if (clock.isSameDay(at, now)) "" else " on ${clock.shortDay(at)}"}. Nothing more to do — this is here so a second press cannot count the same money twice.", style = WHType.Body, color = WHColors.Neutral700)
    }
    Column(Modifier.padding(top = 20.dp)) {
        BillLine("The booking", r.subtotalPence.formatted(currency))
        if (r.paidBeforePence.value > 0) BillLine("Paid already", "−${r.paidBeforePence.formatted(currency)}")
        if (r.extraPence.value > 0) BillLine(r.extraNote?.let { "Sold: $it" } ?: "Sold in the chair", r.extraPence.formatted(currency))
        if (r.tipPence.value > 0) BillLine("Tip", r.tipPence.formatted(currency))
        BillLine("Taken · ${MoneyWords.methodLabel(r.method)}", r.takenPence.formatted(currency), strong = true, tag = "tillTaken")
    }
}

/** A line of the bill, read as one thing: a figure is never spoken without its name. */
@Composable
private fun BillLine(label: String, value: String, hint: String? = null, strong: Boolean = false, tag: String = "") {
    Column(Modifier.fillMaxWidth().background(if (strong) WHColors.Surface else WHColors.Bg).semantics(mergeDescendants = true) { contentDescription = listOfNotNull("$label: $value", hint).joinToString(". "); if (strong) liveRegion = LiveRegionMode.Polite }.testTag(tag)) {
        RowDivider()
        Row(Modifier.padding(horizontal = 4.dp, vertical = 15.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = WHType.Body, color = WHColors.Ink)
                if (hint != null) Text(hint, style = WHType.Meta, color = WHColors.Neutral700)
            }
            Text(value, style = if (strong) WHType.SheetTotal else WHType.Body.copy(fontWeight = FontWeight.Normal), color = WHColors.Ink)
        }
        if (strong) RowDivider()
    }
}
