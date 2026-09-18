package com.wunderhand.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// What a form is made of: the sheets the Menu and the Shop tabs change things in.

/** The round cross at the top of a sheet, or the arrow back out of a screen. */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier, back: Boolean = false, label: String = if (back) "Back" else "Close") {
    Box(modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(40.dp).liftSmall(CircleShape).clip(CircleShape).background(WHColors.Surface).border(1.dp, WHColors.Divider, CircleShape), contentAlignment = Alignment.Center) {
            WHIcon(if (back) WHIcons.ChevronLeft else WHIcons.X, size = 18.dp, tint = WHColors.Ink)
        }
    }
}

/** The name of a sheet: what is being changed. */
@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = WHType.SheetName, color = WHColors.Ink)
}

/** A run of fields under one small heading: "What it costs". */
@Composable
fun FormGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), Modifier.padding(horizontal = 4.dp).semantics { contentDescription = title; heading() }, style = WHType.Eyebrow, color = WHColors.Eyebrow)
        content()
    }
}

/** The sentence under a field: what it is for, what blank means. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp), style = WHType.Meta, color = WHColors.Neutral700)
}

/** One of a short list, or none: a category, a service to need first, a room. */
@Composable
fun ChoiceField(
    label: String, none: String?, options: List<Pair<String, String>>, selection: String?, onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier, isProblem: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selection }?.second ?: none ?: "Choose"
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, if (isProblem) WHColors.Accent else WHColors.Divider, shape)
                .clickable(role = Role.DropdownList) { open = true }.semantics(mergeDescendants = true) { contentDescription = "$label: $shown" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label.uppercase(), style = WHType.FieldLabel, color = WHColors.Neutral700)
                Text(shown, style = WHType.FieldValue, color = if (selection == null) WHColors.Neutral700 else WHColors.Ink, maxLines = 1)
            }
            WHIcon(WHIcons.ChevronsUpDown, size = 16.dp, tint = WHColors.Neutral500)
        }
        DropdownMenu(open, { open = false }, containerColor = WHColors.Surface) {
            val all = (if (none != null) listOf<Pair<String?, String>>(null to none) else emptyList()) + options
            for ((id, name) in all) {
                DropdownMenuItem(
                    text = { Text(name, style = WHType.FieldValue.copy(fontWeight = if (id == selection) FontWeight.SemiBold else FontWeight.Normal), color = WHColors.Ink) },
                    onClick = { open = false; onSelect(id) },
                    trailingIcon = if (id == selection) ({ WHIcon(WHIcons.Check, size = 16.dp, tint = WHColors.Ink) }) else null,
                )
            }
        }
    }
}

/** The app's switch: ink when on, as the web's. */
@Composable
fun WHSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Switch(
        checked, onCheckedChange = null, modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = WHColors.Surface, checkedTrackColor = WHColors.Ink, checkedBorderColor = WHColors.Ink,
            uncheckedThumbColor = WHColors.Neutral500, uncheckedTrackColor = WHColors.Well, uncheckedBorderColor = WHColors.Neutral500,
        ),
    )
}

/** A sentence and a switch. The whole row is the switch, so it is easy to hit and read as one thing. */
@Composable
fun SwitchRow(
    label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, hint: String? = null, enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(label, hint).joinToString(". "); stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = WHType.Semi14.copy(fontSize = WHType.Semi14.fontSize * 1.07f), color = if (enabled) WHColors.Ink else WHColors.Neutral700)
            if (hint != null) Text(hint, style = WHType.Meta, color = WHColors.Neutral700)
        }
        WHSwitch(checked, Modifier.clearAndSetSemantics { })
    }
}

/** "Add a step", "Add a new one": an empty place, outlined in dashes, that something goes into. */
@Composable
fun DashedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val line = WHColors.Neutral500
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .drawBehind { drawRoundRect(line, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))) }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        WHIcon(WHIcons.Plus, size = 16.dp, tint = if (enabled) WHColors.Ink else WHColors.Neutral500)
        Text(text, style = WHType.Semi14, color = if (enabled) WHColors.Ink else WHColors.Neutral500)
    }
}

/** A label and its value on one line of a panel, with a quieter line under the label. */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null, accent: Boolean = false) {
    PanelRow(modifier.semantics(mergeDescendants = true) { contentDescription = listOfNotNull("$label: $value", hint).joinToString(". ") }) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = WHType.Medium14.copy(fontWeight = FontWeight.Normal), color = WHColors.Ink)
                if (hint != null) Text(hint, style = WHType.Meta, color = if (accent) WHColors.Accent else WHColors.Neutral700)
            }
            Text(value, style = WHType.Semi14, color = if (accent) WHColors.Accent else WHColors.Ink)
        }
    }
}

/** "Saved." — said quietly beside the button that did it, and read out when it appears. */
@Composable
fun SavedLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("savedLine"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        WHIcon(WHIcons.Check, size = 16.dp, tint = WHColors.Neutral800)
        Text(text, style = WHType.Semi14, color = WHColors.Neutral800)
    }
}
