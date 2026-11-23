package com.wunderhand.app.features.booking

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wunderhand.core.BookingCreated
import com.wunderhand.core.BookingSlotsResponse
import com.wunderhand.core.BookingStep
import com.wunderhand.core.BookingWords
import com.wunderhand.core.Durations
import com.wunderhand.core.VisitMode
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.InkButton
import com.wunderhand.design.NoteCard
import com.wunderhand.design.OneLine
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall

/**
 * New booking (chairtime `app/(pro)/booking/new/page.tsx`): pick a service,
 * who is doing it, anything extra, where it is when it can be a home visit,
 * then a time — "Book Wed 11:15".
 *
 * Tapping an answer moves on by itself; only extras, which can be several,
 * wait for "Continue". Where there is room the booking so far sits beside the
 * steps, filling in as each choice is made, as the web's laptop layout has it.
 */
@Composable
fun NewBookingScreen(model: NewBookingViewModel, onBooked: (BookingCreated) -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    fun goBack() { if (!model.back()) onClose() }
    BackHandler(onBack = ::goBack)

    BoxWithConstraints(Modifier.fillMaxSize().background(WHColors.Bg).testTag("newBooking")) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Header(model, state, wide, ::goBack)
                if (wide) {
                    Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { Steps(model, state, wide = true) }
                        Summary(model, state, Modifier.width(320.dp).padding(top = 20.dp))
                    }
                } else {
                    Steps(model, state, wide = false)
                }
            }
            Footer(model, state, onBooked)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(model: NewBookingViewModel, state: NewBookingState, wide: Boolean, onBack: () -> Unit) {
    val first = state.step == BookingStep.Service
    Row(Modifier.padding(start = 14.dp, top = 12.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = if (first) "Close" else "Back" }.testTag("bookingBack"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).liftSmall(CircleShape).clip(CircleShape).background(WHColors.Surface).border(1.dp, WHColors.Divider, CircleShape), contentAlignment = Alignment.Center) {
                WHIcon(if (first) WHIcons.X else WHIcons.ChevronLeft, size = 18.dp, tint = WHColors.Ink)
            }
        }
        state.stepNumber?.let { Eyebrow("Step $it of ${state.stepTotal}") }
    }
    Text(
        state.step?.let { if (it == BookingStep.Where) BookingWords.whereTitle(state.visitMode) else it.title } ?: " ", Modifier.padding(horizontal = 22.dp).padding(top = 10.dp).semantics { heading() }.testTag("bookingTitle"),
        style = WHType.SheetName, color = WHColors.Ink,
    )

    // What has been chosen so far, as tags under the title on a phone.
    if (!wide) {
        val tags = listOfNotNull(
            model.start.clientName?.let { "For $it" },
            state.service?.let { "${it.name} · ${it.durationLabel}" },
            state.service?.pricePence?.formatted(model.currency),
            state.outlet?.name,
            state.person?.name,
            // Which way, once it is answered — never the address itself, which stays in its form.
            // Said once it is answered: before the where step it is not yet known.
            when { !state.visitMode.asksWhere || !state.whereDone || state.step == BookingStep.Where -> null; state.isVisit -> BookingWords.AT_CLIENT; else -> BookingWords.AT_SHOP },
            state.visit.quote?.takeIf { state.isVisit && state.whereDone }?.let { q -> if (!q.hasFee) null else if (state.visit.chargeTravel) "${q.fee(model.currency)} travel" else "Travel not charged" },
        )
        if (tags.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 22.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in tags) {
                    Text(
                        tag, Modifier.clip(CircleShape).background(WHColors.Surface).border(1.dp, WHColors.Divider, CircleShape).padding(horizontal = 11.dp, vertical = 6.dp),
                        style = WHType.CardMeta.copy(fontWeight = FontWeight.Medium), color = WHColors.Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun Steps(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    val side = if (wide) 0.dp else 18.dp
    val notes = buildList {
        when (val refusal = state.refusal) {
            is Refusal.Taken -> add(BookingWords.taken(refusal.at, model.clock))
            is Refusal.Rule -> add(refusal.message)
            is Refusal.Problem -> add(refusal.message)
            null -> Unit
        }
        state.loadProblem?.let(::add)
    }
    for (note in notes) NoteCard(note, Modifier.padding(horizontal = side).padding(top = 16.dp).testTag("bookingNote"))

    when (state.step) {
        BookingStep.Service -> ServiceStep(model, state, wide)
        BookingStep.Outlet -> OutletStep(model, state, wide)
        BookingStep.Person -> PersonStep(model, state, wide)
        BookingStep.Extras -> ExtrasStep(model, state, wide)
        BookingStep.Where -> WhereStep(model, state, wide)
        BookingStep.Time -> TimeStep(model, state, wide)
        null -> if (state.loadProblem == null) RowsSkeleton(wide)
    }
}

// region Rows

/** A tappable row: full width with a rule above on a phone, a lifted card where there is room. */
@Composable
private fun ChoiceRow(wide: Boolean, isOn: Boolean, tag: String, spoken: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        if (!wide) RowDivider()
        Box(
            (if (wide) Modifier.liftSmall(shape).clip(shape).background(WHColors.Surface).border(2.dp, if (isOn) WHColors.Ink else WHColors.Surface, shape)
            else Modifier.background(if (isOn) WHColors.Surface else WHColors.Bg))
                .fillMaxWidth().then(if (wide) Modifier.weight(1f) else Modifier).heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = spoken; selected = isOn }
                .padding(horizontal = if (wide) 16.dp else 22.dp, vertical = 15.dp).testTag(tag),
            contentAlignment = Alignment.CenterStart,
        ) { Box(Modifier.clearAndSetSemantics { }) { content() } }
    }
}

/** Rows on a phone, a grid of cards two across where there is room. */
@Composable
private fun <T> Rows(items: List<T>, wide: Boolean, row: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 0.dp)) {
        if (!wide) items.forEach { row(it, Modifier) }
        else items.chunked(2).forEach { pair ->
            // Two cards in a row are the same height, whichever has the longer name.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { row(it, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EmptyStep(text: String, wide: Boolean) {
    Text(text, Modifier.padding(horizontal = if (wide) 0.dp else 22.dp).padding(top = 24.dp), style = WHType.Body, color = WHColors.Neutral800)
}

@Composable
private fun RowsSkeleton(wide: Boolean) {
    Column(Modifier.padding(horizontal = if (wide) 0.dp else 22.dp).padding(top = 28.dp).semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { SkeletonBlock(14.dp, width = 150.dp); SkeletonBlock(10.dp, width = 90.dp) }
                SkeletonBlock(14.dp, width = 44.dp)
            }
        }
    }
}

@Composable
private fun ServiceStep(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    val services = state.services
    when {
        services == null -> if (state.loadProblem == null) RowsSkeleton(wide)
        services.isEmpty() -> EmptyStep("No services yet. Add one in the Menu before taking a booking.", wide)
        else -> Rows(services, wide) { service, modifier ->
            val meta = listOfNotNull(service.durationLabel, service.categoryName).joinToString(" · ")
            val price = service.priceLabel(model.currency)
            ChoiceRow(wide, false, "bookingService", "${service.name}, $meta, $price", { model.choose(service) }, modifier) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(service.name, style = WHType.RowName, color = WHColors.Ink)
                        Text(meta, style = WHType.Meta, color = WHColors.Neutral700)
                    }
                    Text(price, style = WHType.Button, color = WHColors.Ink, maxLines = 1)
                }
            }
        }
    }
}

/** Which outlet, at a shop where more than one does the service — asked
 *  before who, since the people offered are those who work there. */
@Composable
private fun OutletStep(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    Rows(state.outlets, wide) { outlet, modifier ->
        val meta = listOfNotNull(outlet.area, "Does home visits".takeIf { outlet.travels }).joinToString(" · ")
        ChoiceRow(wide, false, "bookingOutlet", listOfNotNull(outlet.name, meta.ifEmpty { null }).joinToString(", "), { model.choose(outlet) }, modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(outlet.name, style = WHType.RowName, color = WHColors.Ink)
                if (meta.isNotEmpty()) Text(meta, style = WHType.Meta, color = WHColors.Neutral700)
            }
        }
    }
}

@Composable
private fun PersonStep(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    val staff = state.detail?.staff ?: return
    if (staff.isEmpty()) return EmptyStep("Nobody is set up to perform this service yet. Assign someone in the Menu.", wide)
    Rows(staff, wide) { person, modifier ->
        val price = person.pricePence?.formatted(model.currency)
        ChoiceRow(wide, false, "bookingPerson", listOfNotNull(person.name, person.roleTitle, price).joinToString(", "), { model.choose(person) }, modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(WHColors.Neutral200), contentAlignment = Alignment.Center) {
                    Text(person.initials, style = WHType.Semi14, color = WHColors.Neutral700)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(person.name, style = WHType.RowName, color = WHColors.Ink)
                    person.roleTitle?.let { Text(it, style = WHType.Meta, color = WHColors.Neutral700) }
                }
                price?.let { Text(it, style = WHType.Button, color = WHColors.Ink, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ExtrasStep(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    Rows(state.detail?.addons.orEmpty(), wide) { addon, modifier ->
        val isOn = addon.id in state.addonIds
        val meta = addon.pricePence.formatted(model.currency) + if (addon.minutes > 0) " · ${Durations.short(addon.minutes)} longer" else ""
        ChoiceRow(wide, isOn, "bookingExtra", listOfNotNull(addon.name, meta, addon.description).joinToString(", "), { model.toggle(addon) }, modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(addon.name, style = WHType.RowName, color = WHColors.Ink)
                    Text(meta, style = WHType.Meta, color = WHColors.Neutral700)
                    addon.description?.let { Text(it, Modifier.padding(top = 4.dp), style = WHType.Summary, color = WHColors.Neutral800) }
                }
                val box = RoundedCornerShape(8.dp)
                Box(Modifier.size(26.dp).clip(box).background(if (isOn) WHColors.Ink else WHColors.Surface).border(1.dp, if (isOn) WHColors.Ink else WHColors.Divider, box), contentAlignment = Alignment.Center) {
                    if (isOn) WHIcon(WHIcons.Check, size = 15.dp, tint = WHColors.Bg)
                }
            }
        }
    }
    if (state.extraMinutes > 0) {
        Text("${Durations.short(state.extraMinutes)} longer than the service on its own.", Modifier.padding(horizontal = if (wide) 0.dp else 22.dp).padding(top = 16.dp), style = WHType.Medium14, color = WHColors.Accent)
    }
}

/**
 * Where it happens, and what the journey costs (the web's `WhereStep`).
 *
 * Three parts, in the order they are answered: for a service done either way,
 * at the shop or at the client's; the address, offered from their last home
 * visit; and the fee, once chairtime has measured the journey — charged by
 * default as a booking made online would be, and droppable by whoever is
 * going. Further than the outlet says it travels is said, not refused.
 */
@Composable
private fun WhereStep(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    val side = if (wide) 0.dp else 18.dp
    val visit = state.visit
    val focus = LocalFocusManager.current

    if (state.visitMode == VisitMode.Either) {
        Rows(listOf(false, true), wide) { atClient, modifier ->
            val title = if (atClient) BookingWords.AT_CLIENT else BookingWords.AT_SHOP
            val says = if (atClient) BookingWords.AT_CLIENT_SAYS else BookingWords.AT_SHOP_SAYS
            ChoiceRow(wide, visit.atClient == atClient, if (atClient) "bookingAtClient" else "bookingAtShop", "$title, $says", { model.where(atClient) }, modifier) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = WHType.RowName, color = WHColors.Ink)
                        Text(says, style = WHType.Meta, color = WHColors.Neutral700)
                    }
                    WHIcon(WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500)
                }
            }
        }
        if (!wide) RowDivider()
    }
    if (!state.isVisit) return

    val quote = visit.quote
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(horizontal = side).padding(top = 20.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(16.dp).testTag("bookingAddress"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(BookingWords.WHERE_TO_GO)
        Text(BookingWords.whereKept(quote), style = WHType.Summary, color = WHColors.Neutral800)
        if (visit.isPrefilled && quote == null) Text(BookingWords.LAST_VISIT, Modifier.testTag("bookingLastVisit"), style = WHType.Summary, color = WHColors.Accent)
        visit.problem?.let { NoteCard(it.text, Modifier.testTag("bookingAddressProblem")) }

        val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
        WHField(BookingWords.ADDRESS, visit.draft.addressLine1, { v -> model.typeAddress { it.copy(addressLine1 = v.take(200)) } }, inputModifier = Modifier.testTag("field-addressLine1"), keyboardOptions = words, isProblem = visit.problem?.field == "addressLine1")
        WHField(BookingWords.ADDRESS_2, visit.draft.addressLine2, { v -> model.typeAddress { it.copy(addressLine2 = v.take(200)) } }, inputModifier = Modifier.testTag("field-addressLine2"), keyboardOptions = words)
        WHField(BookingWords.TOWN, visit.draft.town, { v -> model.typeAddress { it.copy(town = v.take(100)) } }, inputModifier = Modifier.testTag("field-town"), keyboardOptions = words, isProblem = visit.problem?.field == "town")
        WHField(
            BookingWords.POSTCODE, visit.draft.postcode, { v -> model.typeAddress { it.copy(postcode = v.take(20)) } }, inputModifier = Modifier.testTag("field-postcode"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); model.checkAddress() }), isProblem = visit.problem?.field == "postcode",
        )
        InkButton(if (quote != null) BookingWords.CHECK_AGAIN else BookingWords.CHECK, { focus.clearFocus(); model.checkAddress() }, Modifier.fillMaxWidth().testTag("bookingCheckAddress"), loading = visit.isChecking)
    }

    // The fee, once there is an address to price. The figure is chairtime's; whether it is charged is hers, and the booking records which it was.
    if (quote != null) Column(Modifier.padding(horizontal = side).padding(top = 14.dp).fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).testTag("bookingTravel")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(quote.distance, Modifier.weight(1f), style = WHType.Medium14, color = WHColors.Neutral800)
            Text(
                quote.fee(model.currency), Modifier.testTag("bookingTravelFee"),
                style = WHType.SheetTotal.copy(textDecoration = if (quote.hasFee && !visit.chargeTravel) TextDecoration.LineThrough else TextDecoration.None),
                color = if (quote.hasFee && !visit.chargeTravel) WHColors.Neutral700 else WHColors.Ink,
            )
        }
        quote.outside?.let {
            RowDivider()
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("bookingOutsideArea"), style = WHType.Summary.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Ink)
        }
        RowDivider()
        if (quote.hasFee) SwitchRow(BookingWords.CHARGE, visit.chargeTravel, model::chargeTravel, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("bookingChargeTravel"), hint = quote.explains(visit.chargeTravel))
        else Text(quote.explains(true), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = WHType.Summary, color = WHColors.Neutral800)
    }
}

@Composable
private fun TimeStep(model: NewBookingViewModel, state: NewBookingState, wide: Boolean) {
    val side = if (wide) 0.dp else 18.dp
    val slots = state.slots
    if (slots == null) {
        if (state.loadProblem != null) return
        Column(Modifier.padding(horizontal = side).padding(top = 12.dp).semantics { contentDescription = "Loading times" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                SkeletonBlock(10.dp, Modifier.padding(top = 14.dp), width = 140.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { SkeletonBlock(48.dp, Modifier.weight(1f), radius = 10.dp) } }
            }
        }
        return
    }

    if (slots.days.isEmpty()) {
        Column(Modifier.padding(horizontal = if (wide) 0.dp else 22.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nothing free soon", style = WHType.EmptyTitle, color = WHColors.Ink)
            Text("This person has no room in the next few working days. Try another professional, or open up more hours in Settings.", style = WHType.Body, color = WHColors.Neutral800)
        }
        return
    }
    slots.firstExact?.let { exact ->
        exact.gapMinutes?.let { gap -> NoteCard("${model.clock.time(exact.start)} closes a ${Durations.label(gap)} gap exactly.", Modifier.padding(horizontal = side).padding(top = 20.dp)) }
    }
    TimeStripPicker(
        days = slots.days, selectedIso = state.dayIso, onSelectDay = model::selectDay, chosen = state.slot,
        clock = model.clock, wide = wide, currency = model.currency,
        canGoEarlier = model.canGoEarlier, canGoLater = slots.nextFrom != null,
        onEarlier = { model.earlierDays() }, onLater = { model.laterDays() },
        onPick = { model.select(it.start) },
        cheaperDays = slots.days.filter { it.hasCheaper }.map { it.isoDate }.toSet(),
        cheaperRule = slots.firstDiscounted?.price?.ruleName,
        price = { s -> if (s.price.isDiscounted) s.price.pence to s.price.listPence else null },
    )
}

// endregion
// region Footer

@Composable
private fun Footer(model: NewBookingViewModel, state: NewBookingState, onBooked: (BookingCreated) -> Unit) {
    when (state.step) {
        BookingStep.Extras -> FooterBar(Modifier.navigationBarsPadding()) {
            PrimaryButton(BookingWords.continueWith(state.addonIds.size), { model.continueFromExtras() }, Modifier.widthIn(max = 484.dp).testTag("bookingContinue"))
        }
        // Only once the journey is priced: the button names what it is agreeing to.
        BookingStep.Where -> state.visit.quote?.takeIf { state.isVisit }?.let { quote ->
            FooterBar(Modifier.navigationBarsPadding()) {
                PrimaryButton(quote.continueLabel(state.visit.chargeTravel, model.currency), { model.continueFromWhere() }, Modifier.widthIn(max = 484.dp).testTag("bookingWhereContinue"))
            }
        }
        BookingStep.Time -> FooterBar(Modifier.navigationBarsPadding()) {
            Column(Modifier.widthIn(max = 484.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val refusal = state.refusal
                if (refusal is Refusal.Rule && refusal.rule.isOverridable) OverrideCard(model, state)
                val slot = state.slot
                if (slot != null) PrimaryButton("Book ${model.clock.weekdayTime(slot)}", { model.book(onBooked) }, Modifier.testTag("bookButton"), loading = state.isBooking)
                else Text("Tap a time to book it.", style = WHType.CardMeta, color = WHColors.Neutral700)
            }
        }
        else -> Unit
    }
}

/** "The Consultation was done, just not through here." Only for a prerequisite: an age limit has no box to tick. */
@Composable
private fun OverrideCard(model: NewBookingViewModel, state: NewBookingState) {
    val name = state.detail?.requirements?.prerequisiteName ?: "first appointment"
    val ticked = state.overridePrerequisite
    val box = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WHColors.Accent100)
            .clickable(role = Role.Checkbox) { model.setOverride(!ticked) }
            .semantics(mergeDescendants = true) { selected = ticked }.padding(16.dp).testTag("bookingOverride"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp).clip(box).background(if (ticked) WHColors.Accent800 else WHColors.Bg).border(2.dp, WHColors.Accent800, box), contentAlignment = Alignment.Center) {
            if (ticked) WHIcon(WHIcons.Check, size = 14.dp, tint = WHColors.Bg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("The $name was done, just not through here.", style = WHType.Semi14, color = WHColors.Accent800)
            Text("Tick this to book anyway.", style = WHType.CardMeta, color = WHColors.Accent800)
        }
    }
}

// endregion
// region Summary

/** The booking so far, as a receipt that fills in. */
@Composable
private fun Summary(model: NewBookingViewModel, state: NewBookingState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.liftSmall(shape).clip(shape).background(WHColors.Surface).testTag("bookingSummary")) {
        Eyebrow("This booking", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        SummaryRow("Client", model.start.clientName ?: "Walk-in", muted = model.start.clientName == null)
        SummaryRow("Service", state.service?.name ?: "Not chosen", muted = state.service == null)
        if (state.needsOutlet) SummaryRow(if (state.visitMode.asksWhere) "Outlet" else "Where", state.outlet?.name ?: "Not chosen", muted = state.outlet == null)
        SummaryRow("With", state.person?.name ?: "Not chosen", muted = state.person == null)
        for (addon in state.chosenAddons) SummaryRow("Extra", "${addon.name} · ${addon.pricePence.formatted(model.currency)}", muted = false)
        // Where it happens and what the journey costs, for a service that can be a visit. The fee is beside the total, not in it: chairtime adds it as it books.
        if (state.visitMode.asksWhere) {
            val quote = state.visit.quote?.takeIf { state.isVisit }
            val where = when {
                quote != null -> listOf(quote.address.addressLine1, quote.address.postcode).filter { it.isNotBlank() }.joinToString(", ")
                state.isVisit -> BookingWords.AT_CLIENT
                state.visit.atClient == false -> BookingWords.AT_SHOP
                else -> "Not chosen"
            }
            SummaryRow("Where", where, muted = state.visitMode == VisitMode.Either && state.visit.atClient == null)
            if (quote != null) SummaryRow("Travel", if (!quote.hasFee) "No fee" else if (state.visit.chargeTravel) quote.fee(model.currency) else "Not charged", muted = !quote.hasFee || !state.visit.chargeTravel)
        }
        SummaryRow("When", state.slot?.let(model.clock::shortDayTime) ?: "Not chosen", muted = state.slot == null)

        state.service?.let { service ->
            // chairtime's sum once the times are in; before then, only a figure it has
            // already given — a person's price, when there are no extras to add.
            val total = state.slots?.standard?.let { it.pence?.formatted(model.currency) }
                ?: if (state.slots == null && state.addonIds.isEmpty()) (state.person?.pricePence ?: service.pricePence)?.formatted(model.currency) else null
            RowDivider()
            Row(Modifier.fillMaxWidth().background(WHColors.Block).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Durations.short(state.slots?.standard?.minutes ?: (service.minutes + state.extraMinutes)), Modifier.weight(1f), style = WHType.Summary.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Ink)
                Text(total ?: "—", style = WHType.SheetTotal, color = WHColors.Ink)
            }
            RowDivider()
            // The figure above is chairtime's for the service; the journey is added as it books, so it is said, not summed here.
            val travel = state.visit.quote?.takeIf { state.isVisit && it.hasFee && state.visit.chargeTravel }?.fee(model.currency)
            Text(
                if (travel != null) "At the standard price, before $travel travel. A quiet-time rate, if one applies, shows on the time." else "At the standard price. A quiet-time rate, if one applies, shows on the time.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("bookingSummaryNote"), style = WHType.Meta, color = WHColors.Neutral700,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, muted: Boolean) {
    RowDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = WHType.CardMeta, color = WHColors.Neutral700)
        Spacer(Modifier.weight(1f))
        OneLine(value, if (muted) WHType.Medium14 else WHType.Semi14, if (muted) WHColors.Neutral700 else WHColors.Ink)
    }
}

// endregion
