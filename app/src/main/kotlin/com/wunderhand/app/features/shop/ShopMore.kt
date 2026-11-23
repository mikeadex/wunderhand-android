package com.wunderhand.app.features.shop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.core.CalendarLinkResponse
import com.wunderhand.core.CalendarWords
import com.wunderhand.core.ConsentDocument
import com.wunderhand.core.ConsentWords
import com.wunderhand.core.Offer
import com.wunderhand.core.OfferWords
import com.wunderhand.core.QuietTimesResponse
import com.wunderhand.core.QuietWords
import com.wunderhand.core.ShopClock
import com.wunderhand.design.ChoiceField
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.DashedButton
import com.wunderhand.design.EmptyNote
import com.wunderhand.design.Hint
import com.wunderhand.design.InkButton
import com.wunderhand.design.NoteCard
import com.wunderhand.design.Panel
import com.wunderhand.design.PanelRow
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.WHCard
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

// Consent forms, quiet times, offers and a person's calendar link: the Shop
// rows that opened the web until 1.3. Nothing here names what Wunderhand costs.

// region What these screens share

/** What just happened, said as it appears. */
@Composable
internal fun Notice(text: String?, tag: String, modifier: Modifier = Modifier) {
    if (text == null) return
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.padding(top = 16.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WHIcon(WHIcons.Check, size = 16.dp, tint = WHColors.Ink, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = WHType.Body, color = WHColors.Ink)
    }
}

/** A heading and what it means, on the accent's lightest tint: something missing that matters. */
@Composable
private fun Warning(title: String, says: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(WHColors.Accent100).border(1.dp, WHColors.Accent300, shape).padding(16.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = WHType.Semi14, color = WHColors.Accent800)
        Text(says, style = WHType.Summary, color = WHColors.Accent800)
    }
}

// endregion
// region Consent forms

/**
 * Consent forms (chairtime `app/(pro)/shop/consent`): the five kinds, each
 * with the version that is live and how many times it has been signed. The
 * wording itself opens on its own page, where there is room to read it.
 */
@Composable
internal fun ConsentScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: ConsentViewModel = viewModel(key = "shop-consent") { ConsentViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response

    SettingPage("Consent forms", "consent", onBack, intro = ConsentWords.INTRO) {
        Failure(state.failure, response == null) { model.load() }
        if (response == null) { if (state.failure == null) Loading("the consent forms", 5, 62); return@SettingPage }
        if (response.missing > 0) Warning(ConsentWords.missingTitle(response.missing, response.documents.size), ConsentWords.MISSING, Modifier.padding(top = 16.dp).testTag("consentMissing"))
        WHCard(Modifier.padding(top = 16.dp)) {
            response.documents.forEachIndexed { index, document ->
                if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                ConsentRow(document) { shop.push(ShopRoute.ConsentForm(document.kind)) }
            }
        }
        Hint(ConsentWords.FOOT, Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun ConsentRow(document: ConsentDocument, onOpen: () -> Unit) {
    val title = ConsentWords.title(document.kind)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$title, ${document.versionLine}. ${ConsentWords.open(document)}" }.testTag("consent-${document.kind}"),
    ) {
        Row(Modifier.weight(1f).clearAndSetSemantics { }.padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = WHType.RowName, color = WHColors.Ink)
                Text(document.versionLine, style = WHType.Meta, color = if (document.isWritten) WHColors.Neutral700 else WHColors.Accent)
            }
            Text(if (document.isWritten) "Edit" else "Add", style = WHType.Semi14, color = WHColors.Neutral800)
            WHIcon(WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500)
        }
    }
}

/** One kind's wording: what it is for, the words, which version they are, and a save that starts the next. */
@Composable
internal fun ConsentFormScreen(kind: String, app: AppModel, visit: Int, onBack: (() -> Unit)?) {
    val model: ConsentFormViewModel = viewModel(key = "shop-consent-$kind") { ConsentFormViewModel(kind, app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val document = state.document

    SettingPage(
        ConsentWords.title(kind), "consentForm", onBack, intro = ConsentWords.about(kind).ifEmpty { null }, eyebrow = "Consent forms",
        save = document?.saveLabel?.takeIf { state.isChanged || state.isSaving }, onSave = { model.save() }, isSaving = state.isSaving,
    ) {
        Failure(state.failure, document == null) { model.load() }
        if (document == null) { if (state.failure == null) Loading("the wording", 1, 220); return@SettingPage }
        Notice(state.note, "consentSaved")
        Problem(state.problem, "consentProblem")
        WHField(
            "Wording", state.text, model::type, Modifier.padding(top = 20.dp), inputModifier = Modifier.heightIn(min = 180.dp).testTag("field-consentBody"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), singleLine = false, isProblem = state.problem?.field == "body", placeholder = ConsentWords.PLACEHOLDER,
            // Read whole before it is saved: the page scrolls, not the box.
            maxLines = Int.MAX_VALUE,
        )
        Text(document.versionLine, Modifier.padding(top = 10.dp, start = 4.dp).testTag("consentVersion"), style = WHType.Semi14, color = if (document.isWritten) WHColors.Ink else WHColors.Accent)
        Hint(ConsentWords.KEEPS, Modifier.padding(top = 6.dp))
    }
}

// endregion
// region Quiet times

/**
 * Quiet times (chairtime `app/(pro)/shop/pricing`): the week as parts of the
 * day, shaded by how much of each sold; the parts worth pricing lower, each
 * with a discount to try; and the price rules that are live.
 */
@Composable
internal fun QuietTimesScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: QuietTimesViewModel = viewModel(key = "shop-quiet") { QuietTimesViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response
    val currency = shop.me.shop.currency
    var stopping by remember { mutableStateOf<QuietTimesResponse.PriceRule?>(null) }

    SettingPage("Quiet times", "quietTimes", onBack, intro = response?.intro) {
        Failure(state.failure, response == null) { model.load() }
        if (response == null) { if (state.failure == null) Loading("your week", 4, 80); return@SettingPage }
        Notice(state.notice, "quietNotice")
        state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp).testTag("quietProblem")) }

        response.unsold(currency)?.let { Warning(it, QuietWords.ROUGH, Modifier.padding(top = 16.dp).testTag("quietUnsold")) }

        if (response.demand.isNotEmpty()) Panel(QuietWords.WEEK, Modifier.padding(top = 20.dp).testTag("quietWeek")) {
            PanelRow {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.width(40.dp))
                        for ((_, label) in QuietWords.parts) Text(label, Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.Tag, color = WHColors.Neutral700, textAlign = TextAlign.Center)
                    }
                    for (weekday in response.days) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(QuietWords.shortDay(weekday), Modifier.width(40.dp).clearAndSetSemantics { }, style = WHType.Semi14, color = WHColors.Ink)
                        for ((part, _) in QuietWords.parts) WeekCell(weekday, part, response.sold(weekday, part)?.soldPercent, response.suggestion(weekday, part) != null, Modifier.weight(1f))
                    }
                    Text(QuietWords.WEEK_HINT, Modifier.padding(top = 4.dp), style = WHType.Meta, color = WHColors.Neutral700)
                }
            }
        }

        if (response.suggestions.isNotEmpty()) {
            Panel(QuietWords.WORTH, Modifier.padding(top = 20.dp).testTag("quietSuggestions")) {
                for (suggestion in response.suggestions) PanelRow {
                    val sold = response.sold(suggestion.weekday, suggestion.part)?.soldPercent
                    val detail = listOfNotNull(
                        sold?.let { "$it% booked" },
                        suggestion.wouldEarnPence.takeIf { it.value > 0 }?.let { "about ${it.formatted(currency)} a week unsold" },
                    ).joinToString(" · ")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(suggestion.title, style = WHType.RowName, color = WHColors.Ink)
                            if (detail.isNotEmpty()) Text(detail, style = WHType.Meta, color = WHColors.Neutral700)
                        }
                        val key = QuietTimesViewModel.key(suggestion)
                        InkButton(suggestion.button, { model.accept(suggestion) }, Modifier.testTag("quietTry-$key"), enabled = state.busy == null || state.busy == key, loading = state.busy == key)
                    }
                }
            }
            Hint(QuietWords.STARTING_POINT, Modifier.padding(top = 10.dp))
        }

        Panel(response.rulesTitle, Modifier.padding(top = 20.dp).testTag("quietRules")) {
            if (response.rules.isEmpty()) PanelRow { Text(QuietWords.NO_RULES, style = WHType.Summary, color = WHColors.Neutral700) }
            for (rule in response.rules) PanelRow(vertical = 8.dp) {
                Column {
                    Column(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(rule.name, style = WHType.RowName, color = WHColors.Ink)
                        Text(rule.line(currency), style = WHType.Meta, color = WHColors.Neutral700)
                    }
                    SwitchRow(QuietWords.SHOW_SAVING, rule.showSaving, { model.showSaving(rule, it) }, Modifier.testTag("quietSaving-${rule.id}"), hint = rule.savingLine, enabled = state.busy == null)
                    WordsButton(QuietWords.STOP, { stopping = rule }, Modifier.testTag("quietStop-${rule.id}"), color = WHColors.Accent, enabled = state.busy == null || state.busy == rule.id, loading = state.busy == rule.id)
                }
            }
        }
    }

    stopping?.let { rule ->
        ConfirmDialog(
            title = "Stop “${rule.name}”?", message = "Bookings already taken at that price are untouched. New ones go back to the usual price.",
            confirm = QuietWords.STOP, onConfirm = { stopping = null; model.stop(rule) }, onDismiss = { stopping = null },
        )
    }
}

/** One part of one day: darker the more of it sold, outlined where it is worth a look, blank where the shop is shut. */
@Composable
private fun WeekCell(weekday: Int, part: String, soldPercent: Int?, isQuiet: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val sold = soldPercent?.coerceIn(0, 100)
    val fill = if (sold == null) WHColors.Bg else WHColors.Ink.copy(alpha = 0.06f + 0.84f * sold / 100f)
    val says = "${QuietWords.title(weekday, part)}, ${sold?.let { "$it% booked" } ?: "closed"}${if (isQuiet) ", worth a look" else ""}"
    Box(
        modifier.heightIn(min = 40.dp).clip(shape).background(WHColors.Surface).background(fill)
            .then(if (isQuiet) Modifier.border(2.dp, WHColors.Accent, shape) else Modifier.border(1.dp, WHColors.Divider, shape))
            .semantics { contentDescription = says }.testTag("quietCell-$weekday-$part"),
        contentAlignment = Alignment.Center,
    ) {
        Text(sold?.let { "$it%" } ?: "—", Modifier.clearAndSetSemantics { }, style = WHType.Semi14, color = if ((sold ?: 0) >= 50) WHColors.Bg else WHColors.Ink)
    }
}

// endregion
// region Offers

/**
 * Offers (chairtime `app/(pro)/shop/promotions`): what is running and what
 * each has done — used, given away, how many new clients came back — and,
 * under them, a form to start one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OffersScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: OffersViewModel = viewModel(key = "shop-offers") { OffersViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response
    val currency = shop.me.shop.currency
    var stopping by remember { mutableStateOf<Offer?>(null) }
    var pickingEnd by remember { mutableStateOf(false) }

    SettingPage(OfferWords.TITLE, "offers", onBack, intro = OfferWords.INTRO, eyebrow = OfferWords.EYEBROW, closeLabel = "Back to the shop") {
        Failure(state.failure, response == null) { model.load() }
        if (response == null) { if (state.failure == null) Loading("the offers", 2, 150); return@SettingPage }
        Notice(state.notice, "offersNotice")
        if (!state.isStarting) Problem(state.problem, "offersProblem")

        if (response.offers.isEmpty()) EmptyNote(OfferWords.NONE, OfferWords.NONE_SAYS, Modifier.padding(top = 20.dp).testTag("offersNone"))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (offer in response.running) OfferCard(offer, currency, isStopping = state.stopping == offer.id, canStop = state.stopping == null) { stopping = offer }
            if (response.stopped.isNotEmpty()) {
                Text("Stopped", Modifier.padding(top = 8.dp, start = 4.dp).semantics { heading() }, style = WHType.Semi14, color = WHColors.Neutral700)
                for (offer in response.stopped) OfferCard(offer, currency, isStopping = false, canStop = false, onStop = null)
            }
        }

        if (!state.isStarting) DashedButton(OfferWords.START, { model.starting(true) }, Modifier.padding(top = 20.dp).testTag("offerStart"))
        else {
            val draft = state.draft
            Panel(OfferWords.START, Modifier.padding(top = 20.dp).testTag("offerForm"), action = "Close", actionTag = "offerFormClose", onAction = { model.starting(false) }) {
                PanelRow {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Problem(state.problem, "offersProblem")
                        WHField(OfferWords.NAME, draft.name, { v -> model.edit { it.copy(name = v) } }, inputModifier = Modifier.testTag("field-offerName"),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next), isProblem = state.problem?.field == "name", placeholder = OfferWords.NAME_EXAMPLE)
                        ChoiceField(OfferWords.WHO, null, OfferWords.audiences, draft.audience, { v -> if (v != null) model.edit { it.copy(audience = v) } }, Modifier.testTag("field-offerAudience"))
                        Hint(OfferWords.WHO_HINT)
                        if (draft.audience == "lapsed") WHField(OfferWords.A_WHILE_LABEL, draft.lapsedAfterDays, { v -> model.edit { it.copy(lapsedAfterDays = v.filter(Char::isDigit).take(4)) } }, inputModifier = Modifier.testTag("field-offerDays"),
                            keyboardOptions = wholeNumbers, isProblem = state.problem?.field == "lapsedAfterDays", placeholder = "${OfferWords.A_WHILE}")
                        ChoiceField(OfferWords.HOW_MUCH, null, OfferWords.kinds, draft.kind, { v -> if (v != null) model.edit { it.copy(kind = v) } }, Modifier.testTag("field-offerKind"))
                        WHField(OfferWords.AMOUNT, draft.amount, { v -> model.edit { it.copy(amount = v) } }, inputModifier = Modifier.testTag("field-offerAmount"),
                            keyboardOptions = if (draft.kind == "percent") wholeNumbers else amounts, prefix = if (draft.kind == "percent") null else "£", isProblem = state.problem?.field == "value")
                        Hint(OfferWords.AMOUNT_HINT)
                        WHField(OfferWords.CODE, draft.code, { v -> model.edit { it.copy(code = v.uppercase().take(40)) } }, inputModifier = Modifier.testTag("field-offerCode"),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done), isProblem = state.problem?.field == "code", placeholder = OfferWords.CODE_EXAMPLE)
                        Hint(OfferWords.CODE_HINT)
                        WHField(OfferWords.STOP_AFTER, draft.maxRedemptions, { v -> model.edit { it.copy(maxRedemptions = v.filter(Char::isDigit).take(6)) } }, inputModifier = Modifier.testTag("field-offerStopAfter"),
                            keyboardOptions = wholeNumbers, isProblem = state.problem?.field == "maxRedemptions")
                        Hint(OfferWords.STOP_AFTER_HINT)
                        SwitchRow(OfferWords.QUIET_ONLY, draft.quietOnly, { on -> model.edit { it.copy(quietOnly = on) } }, Modifier.testTag("offerQuietOnly"), hint = OfferWords.QUIET_ONLY_HINT)
                        EndsField(draft.endsOn.ifEmpty { null }, state.problem?.field == "endsOn", onPick = { pickingEnd = true }, onClear = { model.edit { it.copy(endsOn = "") } })
                        Text(OfferWords.DAYS.uppercase(), style = WHType.FieldLabel, color = if (state.problem?.field == "weekdays") WHColors.Accent else WHColors.Neutral700)
                        FlowRow(Modifier.testTag("offerDays"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (day in OfferWords.week) TradeChip(QuietWords.day(day), day in draft.weekdays, Modifier.testTag("offerDay-$day")) { model.edit { it.toggled(day) } }
                        }
                        Hint(OfferWords.DAYS_HINT)
                        Text(OfferWords.ONLY_ONE, style = WHType.Meta, color = WHColors.Neutral700)
                        PrimaryButton(OfferWords.START_IT, { model.start() }, Modifier.testTag("offerStartIt"), loading = state.isSaving)
                    }
                }
            }
        }
    }

    stopping?.let { offer ->
        ConfirmDialog(
            title = "Stop “${offer.name}”?", message = "Bookings already made keep the price they were made at. Nobody new gets it.",
            confirm = OfferWords.STOP, onConfirm = { stopping = null; model.stop(offer) }, onDismiss = { stopping = null },
        )
    }

    if (pickingEnd) {
        // A calendar date, picked and kept in UTC, so no travelling phone turns the 4th into the 3rd. Today is the shop's today.
        val today = remember { LocalDate.parse(ShopClock(shop.me.shop.timezone).isoDate(Instant.now())) }
        val start = runCatching { LocalDate.parse(state.draft.endsOn) }.getOrNull() ?: today.plusMonths(1)
        val floor = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = today.year..today.year + 3,
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= floor },
        )
        DatePickerDialog(
            onDismissRequest = { pickingEnd = false },
            confirmButton = {
                WordsButton("Choose", {
                    picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }?.let { day -> model.edit { it.copy(endsOn = day) } }
                    pickingEnd = false
                }, Modifier.testTag("chooseDate"))
            },
            dismissButton = { WordsButton("Cancel", { pickingEnd = false }, color = WHColors.Neutral700) },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun OfferCard(offer: Offer, currency: String, isStopping: Boolean, canStop: Boolean, onStop: (() -> Unit)?) {
    WHCard(Modifier.testTag("offer-${offer.id}")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = if (onStop != null) 4.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(offer.name, style = WHType.RowName, color = if (offer.stopped) WHColors.Neutral700 else WHColors.Ink)
                Text(offer.line(currency), style = WHType.Meta, color = WHColors.Neutral700)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Used", "${offer.redemptions}", Modifier.weight(1f))
                Stat("Given away", offer.givenAwayPence.formatted(currency), Modifier.weight(1f))
                Stat("Came back", offer.cameBackValue, Modifier.weight(1f))
            }
            offer.verdict(currency)?.let { Text(it, style = WHType.Summary, color = WHColors.Neutral800) }
            offer.claimed?.let { Text(it, style = WHType.Meta, color = WHColors.Neutral700) }
            if (onStop != null) WordsButton(OfferWords.STOP, onStop, Modifier.testTag("offerStop"), color = WHColors.Accent, enabled = canStop || isStopping, loading = isStopping)
        }
    }
}

/** A figure with its name under it. */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(WHColors.Bg).padding(horizontal = 10.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, Modifier.clearAndSetSemantics { }, style = WHType.SheetTotal, color = WHColors.Ink, maxLines = 1)
        Text(label, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1)
    }
}

@Composable
private fun EndsField(iso: String?, isProblem: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val shown = iso?.let(OfferWords::date) ?: "No end date"
    Row(Modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, if (isProblem) WHColors.Accent else WHColors.Divider, shape).testTag("field-offerEnds"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onPick).semantics { contentDescription = "${OfferWords.ENDS}: $shown" }.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(OfferWords.ENDS.uppercase(), Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
            Text(shown, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = if (iso == null) WHColors.Neutral700 else WHColors.Ink)
        }
        if (iso != null) WordsButton("Clear", onClear, Modifier.padding(end = 4.dp).semantics { contentDescription = "Clear the end date" }, color = WHColors.Neutral700)
    }
}

// endregion
// region Your calendar

/**
 * Your calendar (chairtime `app/(pro)/shop/calendar`): a person's own diary as
 * a link a calendar app follows. Anybody's, not only an owner's. The link is
 * shown once, when it is made; after that there is only whether one exists.
 */
@Composable
internal fun CalendarScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: CalendarViewModel = viewModel(key = "shop-calendar") { CalendarViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val response = state.response
    val clock = remember(shop.me.shop.timezone) { ShopClock(shop.me.shop.timezone) }
    var confirmingNew by remember { mutableStateOf(false) }
    var confirmingStop by remember { mutableStateOf(false) }

    SettingPage(CalendarWords.TITLE, "calendar", onBack, intro = response?.words?.intro?.ifEmpty { null }) {
        Failure(state.failure, response == null) { model.load() }
        if (response == null) { if (state.failure == null) Loading("your calendar link", 2, 110); return@SettingPage }
        state.problem?.let { NoteCard(it, Modifier.padding(top = 16.dp).testTag("calendarProblem")) }

        val url = response.url
        when {
            url != null -> FreshLink(url, response)
            response.hasLink -> WHCard(Modifier.padding(top = 20.dp).testTag("calendarHas")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(CalendarWords.HAS, style = WHType.RowName, color = WHColors.Ink)
                    response.status(clock)?.let { Text(it, style = WHType.Meta, color = WHColors.Neutral700) }
                }
            }
        }

        if (!response.hasLink) PrimaryButton(CalendarWords.MAKE, { model.make() }, Modifier.padding(top = 20.dp).testTag("calendarMake"), loading = state.isBusy)
        else {
            SecondaryButton(CalendarWords.MAKE_NEW, { confirmingNew = true }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("calendarMakeNew"), loading = state.isBusy)
            Hint(CalendarWords.REPLACES, Modifier.padding(top = 8.dp))
        }

        if (response.words.points.isNotEmpty()) Panel(response.words.heading.ifEmpty { "What goes into it" }, Modifier.padding(top = 20.dp)) {
            for (point in response.words.points) PanelRow { Text(point, style = WHType.Summary, color = WHColors.Neutral800) }
        }
        if (response.words.careful.isNotEmpty()) NoteCard(response.words.careful, Modifier.padding(top = 16.dp))

        if (response.hasLink) Box(Modifier.padding(top = 8.dp)) {
            WordsButton(CalendarWords.STOP, { confirmingStop = true }, Modifier.testTag("calendarStop"), color = WHColors.Accent, enabled = !state.isBusy)
        }
    }

    if (confirmingNew) ConfirmDialog(
        title = CalendarWords.NEW_TITLE, message = CalendarWords.REPLACES, confirm = CalendarWords.MAKE_NEW,
        onConfirm = { confirmingNew = false; model.make() }, onDismiss = { confirmingNew = false },
    )
    if (confirmingStop) ConfirmDialog(
        title = CalendarWords.STOP_TITLE, message = CalendarWords.STOP_SAYS, confirm = "Stop sharing",
        onConfirm = { confirmingStop = false; model.stop() }, onDismiss = { confirmingStop = false },
    )
}

/** The link just made: add it to a calendar now, or copy it, because it will not be shown again. */
@Composable
private fun FreshLink(url: String, response: CalendarLinkResponse) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    var copied by remember(url) { mutableStateOf(false) }
    val webcal = response.webcalUrl ?: url

    WHCard(Modifier.padding(top = 20.dp).testTag("calendarReady")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(CalendarWords.READY, style = WHType.RowName, color = WHColors.Ink)
                Text(CalendarWords.ONCE, style = WHType.Summary, color = WHColors.Neutral800)
            }
            InkButton(CalendarWords.GOOGLE, { runCatching { links.openUri(CalendarWords.google(webcal)) } }, Modifier.fillMaxWidth().testTag("calendarGoogle"))
            SecondaryButton(CalendarWords.OUTLOOK, { runCatching { links.openUri(CalendarWords.outlook(url)) } }, Modifier.fillMaxWidth().testTag("calendarOutlook"), minHeight = 48.dp)
            Text(CalendarWords.COPY_HINT, style = WHType.Meta, color = WHColors.Neutral700)
            Text(url, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WHColors.Bg).padding(10.dp).testTag("calendarUrl"), style = WHType.Meta, color = WHColors.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordsButton(if (copied) CalendarWords.COPIED else CalendarWords.COPY, {
                    val clip = ClipData.newPlainText(CalendarWords.LINK_LABEL, url)
                    // A key, not a sentence: kept out of the clipboard's own preview where Android can do that.
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                    copied = true
                }, Modifier.testTag("calendarCopy"))
                WordsButton(CalendarWords.SHARE, {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), CalendarWords.LINK_LABEL)) }
                }, Modifier.testTag("calendarShare"))
            }
        }
    }
}

// endregion
