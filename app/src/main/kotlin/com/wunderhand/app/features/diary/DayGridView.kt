package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wunderhand.core.DiaryAppointment
import com.wunderhand.core.DiaryBreak
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.core.DiaryGap
import com.wunderhand.core.DiaryMember
import com.wunderhand.core.Durations
import com.wunderhand.design.OneLine
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType
import com.wunderhand.design.hatch
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A time grid is a picture of the day: every block is drawn to the minutes it
 * takes, so type that grows without limit turns 09:00 into a stack of digits
 * and every booking into an ellipsis. Inside a grid the type stops growing at
 * 130%. Day and Week scale the whole way and are the better screens at large
 * sizes; this one stays a picture.
 */
@Composable
fun GridTypeCeiling(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f)), content = content)
}

private val DP_PER_HOUR = 96.dp
private val GUTTER = 42.dp
/** Below this a block cannot show its own text. */
private val MIN_BLOCK = 28.dp

private fun between(a: Instant, b: Instant): Dp = minutesToDp(Duration.between(a, b).toMillis() / 60_000.0, DP_PER_HOUR)

/**
 * One person's day as a time grid, where height is duration
 * (chairtime `components/diary/DayGrid.tsx`).
 *
 * The list answers "what is on today"; this answers "where does it sit". A
 * 45-minute cut and a six-hour sitting are the same size in a list and wildly
 * different here, which is the point — and it is the view to move things in:
 * hold a booking to pick it up, drag its handle to change how long it takes
 * (see BlockDrag).
 */
@Composable
fun DayGridView(member: DiaryMember, model: DiaryViewModel, state: DiaryState, now: Instant, modifier: Modifier = Modifier) {
    val day = member.day
    val start = day.open.minOfOrNull { it.start } ?: return
    val end = day.open.maxOf { it.end }
    val hours = Duration.between(start, end).toMillis() / 3_600_000.0

    GridTypeCeiling { QuickerHold {
        Column(modifier) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(DP_PER_HOUR * hours.toFloat() + 10.dp)
                    .semantics { contentDescription = "${member.name}'s day by the hour" }.testTag("dayGrid"),
            ) {
                HourRules(ceil(hours).toInt(), start, model)
                // Gaps underneath, then breaks, bookings on top, as the web's team grid stacks them.
                for (gap in day.gaps) GapBlock(gap, start, model)
                for (block in day.breaks) BreakBlock(block, start, end, model, mayRemove = model.me.staff.isOwner || member.id == model.me.staff.id)
                for (row in day.appointments) AppointmentBlock(row, start, model, state, now, day.slotIntervalMinutes)
                if (now >= start && now <= end) NowLine(between(start, now), model.clock.time(now))
            }
            Text(
                "Hold a booking to pick it up · drag the handle to change how long it takes · hatched time is free to sell",
                Modifier.padding(horizontal = 18.dp, vertical = 16.dp), style = WHType.Meta, color = WHColors.Neutral700,
            )
        }
    } }
}

@Composable
private fun BoxScope.HourRules(count: Int, start: Instant, model: DiaryViewModel) {
    for (i in 0..count) {
        val y = DP_PER_HOUR * i
        if (i < count) Box(Modifier.offset(y = y).padding(start = GUTTER).fillMaxWidth().height(1.dp).background(WHColors.Divider))
        Text(
            model.clock.time(start.plusSeconds(i * 3600L)),
            Modifier.offset(y = y - 7.dp).width(GUTTER - 8.dp).clearAndSetSemantics { },
            style = WHType.Meta, color = WHColors.Neutral700, textAlign = TextAlign.End, maxLines = 1,
        )
    }
}

@Composable
private fun BoxScope.NowLine(y: Dp, time: String) {
    Row(Modifier.offset(y = y - 9.dp).padding(start = GUTTER - 4.dp).fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(WHColors.Accent))
        Box(Modifier.weight(1f).height(1.dp).background(WHColors.Accent))
        Text(
            time, Modifier.padding(start = 4.dp).clip(CircleShape).background(WHColors.Accent).padding(horizontal = 8.dp, vertical = 2.dp),
            style = WHType.GridHour, color = WHColors.Bg,
        )
    }
}

@Composable
private fun BoxScope.GapBlock(gap: DiaryGap, start: Instant, model: DiaryViewModel) {
    val height = maxOf(between(gap.startsAt, gap.endsAt) - 4.dp, MIN_BLOCK)
    val radius = 14.dp
    Box(
        Modifier.offset(y = between(start, gap.startsAt)).padding(start = GUTTER + 8.dp).fillMaxWidth().height(height)
            .clip(RoundedCornerShape(radius)).background(WHColors.Accent100)
            .drawBehind {
                drawRoundRect(
                    WHColors.Accent300, cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clearAndSetSemantics { contentDescription = "${Durations.label(gap.minutes)} free from ${model.clock.time(gap.startsAt)}" }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        OneLine("${Durations.label(gap.minutes)} free", WHType.CardMeta, WHColors.Accent)
    }
}

/**
 * Time blocked out. The web's phone grid and the iPhone's do not draw these,
 * which leaves a break looking like unexplained empty space — and leaves
 * somebody who blocked the wrong hour on their phone with no way to take it
 * back without a laptop. Here it is drawn as on the team grid, and a tap
 * offers to remove it.
 */
@Composable
private fun BoxScope.BreakBlock(block: DiaryBreak, start: Instant, end: Instant, model: DiaryViewModel, mayRemove: Boolean) {
    val from = maxOf(block.startsAt, start)
    val to = minOf(block.endsAt, end)
    if (to <= from) return
    val height = maxOf(between(from, to) - 4.dp, MIN_BLOCK)
    val label = "${block.label} · ${model.clock.time(block.startsAt)}–${model.clock.time(block.endsAt)}"
    var removing by remember(block.id) { mutableStateOf(false) }
    Box(
        Modifier.offset(y = between(start, from)).padding(start = GUTTER + 8.dp).fillMaxWidth().height(height)
            .clip(RoundedCornerShape(14.dp)).background(WHColors.Break).hatch()
            .then(if (mayRemove) Modifier.clickable(role = Role.Button, onClickLabel = "Remove it") { removing = true } else Modifier)
            .testTag("break-${block.id}").clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { OneLine(label, WHType.CardMeta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), WHColors.Muted) }

    if (removing) {
        ConfirmDialog(
            title = "Remove ${block.label.lowercase()} at ${model.clock.time(block.startsAt)}?",
            message = "The time goes back to being bookable.",
            confirm = "Remove it",
            onConfirm = { model.unblock(block) },
            onDismiss = { removing = false },
        )
    }
}

@Composable
private fun BoxScope.AppointmentBlock(row: DiaryAppointment, start: Instant, model: DiaryViewModel, state: DiaryState, now: Instant, slotMinutes: Int) {
    val drag = rememberBlockDrag(row, model, state, dpPerMinute = DP_PER_HOUR.value / 60f, slotMinutes = slotMinutes)
    val adjust = drag.adjust
    val lifted = adjust.isLifted
    val height = maxOf(between(row.startsAt, row.endsAt) - 4.dp + adjust.stretchMinutes.minutesAt(DP_PER_HOUR.value / 60f), MIN_BLOCK)
    // Stop the label where the first hatched stretch begins, so the name never
    // ends up underneath it; drop the second line when it will not fit.
    val labelRoom = row.freeInside.firstOrNull()?.let { maxOf(between(row.startsAt, it.start) - 6.dp, 18.dp) } ?: height
    val meta = if (lifted) row.liftedTimes(adjust, model) else listOfNotNull(row.serviceName, row.pricePence?.formatted(model.currency)).joinToString(" · ")
    val shape = RoundedCornerShape(14.dp)

    Box(
        Modifier.zIndex(if (lifted || adjust.isSaving) 1f else 0f)
            .offset(y = between(start, row.startsAt) + adjust.shiftMinutes.minutesAt(DP_PER_HOUR.value / 60f)).padding(start = GUTTER + 8.dp).fillMaxWidth().height(height)
            .then(if (lifted) Modifier.shadow(10.dp, shape, ambientColor = WHColors.Shadow, spotColor = WHColors.Shadow) else Modifier)
            .alpha(if (adjust.isSaving) 0.5f else 1f)
            .clip(shape).background(WHColors.Surface).border(1.dp, if (lifted) WHColors.Accent300 else WHColors.Divider, shape)
            .clickable(role = Role.Button, onClickLabel = "Open the appointment") { model.open(row.id) }
            .then(drag.pickUp)
            .semantics(mergeDescendants = true) { contentDescription = row.spoken(model.clock, model.currency, now) }
            .testTag("appointment-${row.id}"),
    ) {
        // A quarter-hour block is barely taller than its own name: less padding, so the name is whole.
        Column(Modifier.clearAndSetSemantics { }.padding(horizontal = 12.dp, vertical = if (height < 40.dp) 3.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneLine(row.displayName, WHType.CardName.copy(fontSize = WHType.Button.fontSize), WHColors.Ink, Modifier.weight(1f, fill = false))
                if (row.repeats) RepeatMark(Modifier.padding(start = 4.dp))
            }
            if (labelRoom >= 40.dp || lifted) OneLine(meta, WHType.Meta, if (lifted) WHColors.Accent else WHColors.Neutral700)
        }
        if (!lifted) {
            for (free in row.freeInside) {
                val inside = between(free.start, free.end)
                if (inside >= 12.dp) {
                    // A tint's developing time: not the client's and not idle — sellable.
                    Box(
                        Modifier.offset(y = between(row.startsAt, free.start)).fillMaxWidth().height(inside).clip(RoundedCornerShape(6.dp))
                            .background(WHColors.Bg).hatch(WHColors.Accent100, degrees = 45f)
                            .clearAndSetSemantics { contentDescription = "${Durations.label(free.minutes.roundToInt())} free while this develops" },
                        contentAlignment = Alignment.CenterEnd,
                    ) { Text("free", Modifier.padding(horizontal = 8.dp), style = WHType.GridHour, color = WHColors.Accent) }
                }
            }
        }
        drag.handle(this)
    }
}
