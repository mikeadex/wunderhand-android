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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wunderhand.core.BookingCreated
import com.wunderhand.core.BookingSlotsResponse
import com.wunderhand.core.BookingStep
import com.wunderhand.core.BookingWords
import com.wunderhand.core.Durations
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.OneLine
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall

/**
 * New booking (chairtime `app/(pro)/booking/new/page.tsx`): pick a service,
 * who is doing it, anything extra, then a time — "Book Wed 11:15".
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
        state.step?.let { Eyebrow("Step ${it.number(state.hasExtras, state.needsOutlet)} of ${BookingStep.total(state.hasExtras, state.needsOutlet)}") }
    }
    Text(
        state.step?.title ?: " ", Modifier.padding(horizontal = 22.dp).padding(top = 10.dp).semantics { heading() }.testTag("bookingTitle"),
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

    // Prices show on the times only when a rule brings some of them down; otherwise every time costs the same.
    val showPrices = slots.firstDiscounted != null
    if (slots.days.isEmpty()) {
        Column(Modifier.padding(horizontal = if (wide) 0.dp else 22.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nothing free soon", style = WHType.EmptyTitle, color = WHColors.Ink)
            Text("This person has no room in the next few working days. Try another professional, or open up more hours in Settings.", style = WHType.Body, color = WHColors.Neutral800)
        }
    }
    slots.firstExact?.let { exact ->
        exact.gapMinutes?.let { gap -> NoteCard("${model.clock.time(exact.start)} closes a ${Durations.label(gap)} gap exactly.", Modifier.padding(horizontal = side).padding(top = 20.dp)) }
    }
    // Named, so the pro can explain the number rather than discover it.
    slots.firstDiscounted?.let { cheaper ->
        cheaper.price.ruleName?.let { rule ->
            Text("“$rule” brings some of these down to ${cheaper.price.pence.formatted(model.currency)}.", Modifier.padding(horizontal = if (wide) 0.dp else 22.dp).padding(top = 16.dp), style = WHType.CardMeta, color = WHColors.Accent)
        }
    }

    val across = if (wide) 4 else 3
    for (day in slots.days) {
        Column(Modifier.padding(horizontal = side).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(day.label, Modifier.padding(horizontal = 4.dp))
            for (line in day.slots.chunked(across)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (slot in line) SlotButton(slot, model, state, showPrices, Modifier.weight(1f))
                    repeat(across - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (slots.days.isNotEmpty()) WordsButton("Later days ›", { model.laterDays() }, Modifier.padding(horizontal = if (wide) 0.dp else 14.dp).padding(top = 12.dp).testTag("bookingLaterDays"))
}

@Composable
private fun SlotButton(slot: BookingSlotsResponse.Slot, model: NewBookingViewModel, state: NewBookingState, showPrice: Boolean, modifier: Modifier = Modifier) {
    val selected = state.slot == slot.start
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val time = model.clock.time(slot.start)
    val price = slot.price.pence.formatted(model.currency)
    Column(
        modifier.heightIn(min = if (showPrice) 58.dp else 48.dp).liftSmall(shape).clip(shape)
            .background(if (selected) WHColors.Ink else if (slot.closesGapExactly) WHColors.Accent100 else WHColors.Surface)
            .then(if (selected || slot.closesGapExactly) Modifier else Modifier.border(1.dp, WHColors.Divider, shape))
            .clickable(role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.select(slot.start) }
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(time, price.takeIf { showPrice }, "closes a gap exactly".takeIf { slot.closesGapExactly }).joinToString(", ")
                this.selected = selected
            }
            .padding(vertical = 6.dp).testTag("bookingSlot"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(time, Modifier.clearAndSetSemantics { }, style = WHType.Button, color = if (selected) WHColors.Bg else if (slot.closesGapExactly) WHColors.Accent else WHColors.Ink)
        if (showPrice) {
            Text(price, Modifier.clearAndSetSemantics { }, style = WHType.Tag.copy(fontWeight = if (slot.price.isDiscounted && !selected) FontWeight.Medium else FontWeight.Normal),
                color = if (selected) WHColors.Bg.copy(alpha = 0.8f) else if (slot.price.isDiscounted) WHColors.Accent else WHColors.Neutral700)
        }
    }
}

// endregion
// region Footer

@Composable
private fun Footer(model: NewBookingViewModel, state: NewBookingState, onBooked: (BookingCreated) -> Unit) {
    when (state.step) {
        BookingStep.Extras -> FooterBar(Modifier.navigationBarsPadding()) {
            PrimaryButton(BookingWords.continueWith(state.addonIds.size), { model.continueFromExtras() }, Modifier.widthIn(max = 484.dp).testTag("bookingContinue"))
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
        if (state.needsOutlet) SummaryRow("Where", state.outlet?.name ?: "Not chosen", muted = state.outlet == null)
        SummaryRow("With", state.person?.name ?: "Not chosen", muted = state.person == null)
        for (addon in state.chosenAddons) SummaryRow("Extra", "${addon.name} · ${addon.pricePence.formatted(model.currency)}", muted = false)
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
            Text("At the standard price. A quiet-time rate, if one applies, shows on the time.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = WHType.Meta, color = WHColors.Neutral700)
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
