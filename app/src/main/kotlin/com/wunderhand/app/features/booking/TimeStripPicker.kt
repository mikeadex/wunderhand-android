package com.wunderhand.app.features.booking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.wunderhand.core.Pence
import com.wunderhand.core.ShopClock
import com.wunderhand.core.StripDay
import com.wunderhand.core.StripSlot
import com.wunderhand.core.TimeStrip
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.liftSmall

/**
 * The web's time picker (chairtime `components/diary/ProTimePicker.tsx`), on
 * the phone: a month heading, seven days across with Previous and Next week
 * beside it, and the chosen day's times under Morning, Afternoon and Evening.
 * Tapping a day is instant — the week is already here.
 *
 * A time that closes a gap exactly is tinted; the chosen one is dark. A price
 * shows only on a cheaper time (~~£20~~ £16), and a day with one gets a dot,
 * with one line naming the rule.
 *
 * @param chosen the chosen time, drawn dark; null where a tap is the act (moving).
 * @param moving the time being moved to, drawn as a spinner.
 * @param price a cheaper time's price and the price it was; null at the list price.
 */
@Composable
fun <S : StripSlot> TimeStripPicker(
    days: List<StripDay<S>>,
    selectedIso: String?,
    onSelectDay: (String) -> Unit,
    chosen: java.time.Instant?,
    clock: ShopClock,
    wide: Boolean,
    currency: String,
    canGoEarlier: Boolean,
    canGoLater: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onPick: (S) -> Unit,
    moving: java.time.Instant? = null,
    cheaperDays: Set<String> = emptySet(),
    cheaperRule: String? = null,
    price: (S) -> Pair<Pence, Pence>? = { null },
) {
    val side = if (wide) 0.dp else 18.dp
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = side + 4.dp).padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(TimeStrip.monthHeading(days), Modifier.weight(1f).semantics { heading() }.testTag("stripMonth"), style = WHType.Semi14.copy(fontSize = WHType.Semi14.fontSize * 1.2f), color = WHColors.Ink)
            WeekButton(WHIcons.ChevronLeft, "Previous week", canGoEarlier, onEarlier, Modifier.testTag("previousWeek"))
            Spacer(Modifier.size(8.dp))
            WeekButton(WHIcons.ChevronRight, "Next week", canGoLater, onLater, Modifier.testTag("nextWeek"))
        }

        Row(Modifier.padding(horizontal = side).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val open = selectedIso ?: days.firstOrNull()?.isoDate
            for (day in days) DayChip(day, on = day.isoDate == open, dot = day.isoDate in cheaperDays, onClick = { onSelectDay(day.isoDate) }, Modifier.weight(1f))
        }

        if (cheaperRule != null) {
            Text("Days with a dot have cheaper times, from “$cheaperRule”.", Modifier.padding(horizontal = side + 4.dp).padding(top = 12.dp), style = WHType.CardMeta, color = WHColors.Accent)
        }

        val day = days.firstOrNull { it.isoDate == selectedIso } ?: days.firstOrNull()
        if (day != null) {
            val groups = TimeStrip.grouped(day.slots, clock)
            if (groups.isEmpty()) Text("Nothing free on this day.", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp), style = WHType.Body, color = WHColors.Neutral800)
            val across = if (wide) 4 else 3
            for ((period, slots) in groups) {
                Column(Modifier.padding(horizontal = side).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(TimeStrip.title(period), Modifier.padding(horizontal = 4.dp))
                    for (line in slots.chunked(across)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (slot in line) SlotChip(slot, chosen == slot.start, moving, clock, currency, price(slot), { onPick(slot) }, Modifier.weight(1f))
                            repeat(across - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekButton(icon: WHIcons, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(36.dp).clip(CircleShape).background(WHColors.Surface).border(1.dp, WHColors.Divider, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { WHIcon(icon, size = 14.dp, tint = if (enabled) WHColors.Ink else WHColors.Neutral500) }
}

@Composable
private fun DayChip(day: StripDay<*>, on: Boolean, dot: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val empty = day.slots.isEmpty()
    val spoken = listOfNotNull("${TimeStrip.dow(day)} ${TimeStrip.dom(day)} ${TimeStrip.month(day)}", if (empty) "nothing free" else "${day.slots.size} time${if (day.slots.size == 1) "" else "s"}", "has cheaper times".takeIf { dot }).joinToString(", ")
    Column(
        modifier.heightIn(min = 58.dp).clip(shape).background(if (on) WHColors.Ink else WHColors.Surface)
            .then(if (on) Modifier else Modifier.border(1.dp, WHColors.Divider, shape))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken; selected = on }
            .padding(vertical = 8.dp).testTag("stripDay"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val colour = if (on) WHColors.Bg else if (empty) WHColors.Neutral500 else WHColors.Ink
        Text(TimeStrip.dow(day), Modifier.clearAndSetSemantics { }, style = WHType.Tag.copy(fontWeight = FontWeight.Medium), color = colour)
        Text(TimeStrip.dom(day), Modifier.clearAndSetSemantics { }, style = WHType.RowName, color = colour)
        Box(Modifier.size(5.dp).alpha(if (dot) 1f else 0f).clip(CircleShape).background(if (on) WHColors.Bg else WHColors.Accent))
    }
}

@Composable
private fun SlotChip(slot: StripSlot, selected: Boolean, moving: java.time.Instant?, clock: ShopClock, currency: String, cheaper: Pair<Pence, Pence>?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    val time = clock.time(slot.start)
    val isMoving = moving == slot.start
    val spoken = listOfNotNull(time, cheaper?.let { "${it.first.formatted(currency)}, was ${it.second.formatted(currency)}" }, "closes a gap exactly".takeIf { slot.closesGapExactly }).joinToString(", ")
    Box(
        modifier.heightIn(min = if (cheaper == null) 48.dp else 58.dp).liftSmall(shape).clip(shape)
            .background(if (selected) WHColors.Ink else if (slot.closesGapExactly) WHColors.Accent100 else WHColors.Surface)
            .then(if (selected || slot.closesGapExactly) Modifier else Modifier.border(1.dp, WHColors.Divider, shape))
            .clickable(enabled = moving == null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = spoken; this.selected = selected }
            .padding(vertical = 6.dp).testTag("bookingSlot"),
        contentAlignment = Alignment.Center,
    ) {
        if (isMoving) CircularProgressIndicator(Modifier.size(18.dp), color = WHColors.Ink, strokeWidth = 2.dp)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, Modifier.clearAndSetSemantics { }, style = WHType.Button, color = if (selected) WHColors.Bg else if (slot.closesGapExactly) WHColors.Accent else WHColors.Ink)
            if (cheaper != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cheaper.second.formatted(currency), Modifier.clearAndSetSemantics { }, style = WHType.Tag.copy(textDecoration = TextDecoration.LineThrough), color = if (selected) WHColors.Bg.copy(alpha = 0.7f) else WHColors.Neutral700)
                Text(cheaper.first.formatted(currency), Modifier.clearAndSetSemantics { }, style = WHType.Tag.copy(fontWeight = FontWeight.Medium), color = if (selected) WHColors.Bg else WHColors.Accent)
            }
        }
    }
}
