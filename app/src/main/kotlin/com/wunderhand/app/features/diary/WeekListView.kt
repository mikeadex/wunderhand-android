package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wunderhand.core.Durations
import com.wunderhand.core.IsoDay
import com.wunderhand.core.WeekDay
import com.wunderhand.core.WeekSummary
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.RowDivider
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType
import kotlin.math.roundToInt

/**
 * A week of days, each carrying how full it is (chairtime `WeekView.tsx`).
 * The bar is the point: a day with six bookings and four hours of holes
 * should not look like a day that is genuinely full.
 */
@Composable
fun WeekListView(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    val days = state.response?.week.orEmpty()
    val summary = WeekSummary(days)

    Column(modifier.testTag("weekList")) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 18.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Booked", "${summary.booked}", Modifier.weight(1f))
            summary.takingsPence?.let { Tile("Taken", it.formatted(model.currency), Modifier.weight(1f)) }
            Tile("Utilised", "${(summary.utilisation * 100).roundToInt()}%", Modifier.weight(1f))
        }
        Column(Modifier.padding(top = 20.dp)) { for (day in days) DayRow(day, model) }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxHeight().clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape).padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow(label, Modifier.clearAndSetSemantics { })
        // A figure is never cut short: "£2,040.2" is a different sum from "£2,040.20". It shrinks to fit instead.
        Text(
            value, Modifier.clearAndSetSemantics { }, style = WHType.Tile, color = WHColors.Ink, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = WHType.Tile.fontSize, stepSize = 1.sp),
        )
    }
}

@Composable
private fun DayRow(day: WeekDay, model: DiaryViewModel) {
    val name = IsoDay.weekdayDayMonth(day.isoDate)
    RowDivider()
    if (!day.isOpen) {
        Row(Modifier.fillMaxWidth().alpha(0.55f).padding(horizontal = 22.dp, vertical = 15.dp).semantics(mergeDescendants = true) { contentDescription = "$name, closed" }) {
            Text(name, Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.WeekRow, color = WHColors.Ink)
            Text("Closed", Modifier.clearAndSetSemantics { }, style = WHType.CardMeta, color = WHColors.Neutral700)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open this day") { model.openDay(day.isoDate) }
            .semantics(mergeDescendants = true) { contentDescription = "$name, ${day.booked} booked, ${(day.utilisation * 100).roundToInt()} percent sold" }
            .padding(horizontal = 22.dp, vertical = 15.dp)
            .testTag("week-${day.isoDate}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.WeekRow, color = WHColors.Ink)
            day.takingsPence?.let { Text(it.formatted(model.currency), Modifier.clearAndSetSemantics { }, style = WHType.WeekRow.copy(fontSize = WHType.Button.fontSize), color = WHColors.Ink) }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(WHColors.Neutral200)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(day.utilisation.coerceIn(0.0, 1.0).toFloat()).clip(CircleShape).background(WHColors.Ink))
        }
        Text(
            buildAnnotatedString {
                append("${day.booked} booked")
                if (day.freeMinutes > 0) {
                    append(" · ")
                    withStyle(SpanStyle(color = WHColors.Accent)) { append("${Durations.label(day.freeMinutes.roundToInt())} free") }
                }
            },
            Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700,
        )
    }
}
