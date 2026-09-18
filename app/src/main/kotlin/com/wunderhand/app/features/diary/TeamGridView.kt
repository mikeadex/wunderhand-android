package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wunderhand.core.DiaryAppointment
import com.wunderhand.core.DiaryBreak
import com.wunderhand.core.DiaryGap
import com.wunderhand.core.DiaryMember
import com.wunderhand.core.Durations
import com.wunderhand.core.GridGeometry
import com.wunderhand.core.Span
import com.wunderhand.design.OneLine
import com.wunderhand.design.TagTone
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHTag
import com.wunderhand.design.WHType
import com.wunderhand.design.hatch
import java.time.Duration
import java.time.Instant

private val GUTTER = 52.dp
private val COLUMN_GAP = 10.dp
/** Past six people a column keeps this much, and the grid scrolls sideways. */
private val WIDE_COLUMN = 150.dp
/** However few people, a column is never narrower than a name and a time. */
private val NARROWEST_COLUMN = 112.dp
/** A minute is a dp: an hour is 60dp high, as on the web. */
private fun minutes(a: Instant, b: Instant): Dp = (Duration.between(a, b).toMillis() / 60_000.0).toFloat().dp

/**
 * The day, one column per person, one dp per minute
 * (chairtime `components/diary/TeamGrid.tsx`) — the front desk's view,
 * answering "who is free at three?".
 *
 * Columns share the width; past six people each keeps 150dp and the grid
 * scrolls sideways.
 */
@Composable
fun TeamGridView(model: DiaryViewModel, state: DiaryState, now: Instant, modifier: Modifier = Modifier) {
    val response = state.response ?: return
    val members = response.team.members
    val range = GridGeometry.range(
        members.flatMap { it.day.open },
        fallback = Span(response.dayStart.plus(Duration.ofHours(9)), response.dayStart.plus(Duration.ofHours(18))),
    )
    val height = minutes(range.start, range.end)
    val wide = members.size > 6

    GridTypeCeiling {
        BoxWithConstraints(modifier.fillMaxWidth().testTag("teamGrid")) {
            val fits = maxWidth - 52.dp
            val needed = GUTTER + ((if (wide) WIDE_COLUMN else NARROWEST_COLUMN) + COLUMN_GAP) * members.size
            val width = maxOf(fits, needed)

            Box(Modifier.horizontalScroll(rememberScrollState(), enabled = needed > fits).padding(horizontal = 26.dp).padding(bottom = 12.dp)) {
                Column(Modifier.width(width)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                        Spacer(Modifier.width(GUTTER))
                        for (member in members) ColumnHead(member, range, model, Modifier.weight(1f))
                    }
                    Box {
                        Row(horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                            HourGutter(range, height, now, model)
                            for (member in members) TeamColumn(member, range, height, response.team.isClosed, model, now, Modifier.weight(1f))
                        }
                        if (now >= range.start && now <= range.end) {
                            Box(Modifier.offset(y = minutes(range.start, now)).padding(start = GUTTER).fillMaxWidth().height(2.dp).background(WHColors.Accent.copy(alpha = 0.85f)))
                        }
                    }
                }
            }

            if (response.team.isClosed) {
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 120.dp).shadow(12.dp, shape, ambientColor = WHColors.Shadow, spotColor = WHColors.Shadow)
                        .clip(shape).background(WHColors.Surface).padding(horizontal = 24.dp, vertical = 20.dp).testTag("closedDay"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Nobody is working this day", style = WHType.WeekRow.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Ink)
                    Text("Hours are set per person, per weekday.", style = WHType.Summary, color = WHColors.Neutral700)
                }
            }
        }
    }
}

@Composable
private fun HourGutter(range: Span, height: Dp, now: Instant, model: DiaryViewModel) {
    val hours = (Duration.between(range.start, range.end).toMinutes() / 60).toInt()
    Box(Modifier.width(GUTTER).height(height).clearAndSetSemantics { }) {
        for (i in 0..hours) {
            Text(
                model.clock.time(range.start.plusSeconds(i * 3600L)),
                Modifier.offset(y = (i * 60).dp - 7.dp).fillMaxWidth().padding(end = 10.dp),
                style = WHType.GridHour, color = WHColors.Muted, textAlign = TextAlign.End, maxLines = 1,
            )
        }
        if (now >= range.start && now <= range.end) {
            Text(
                model.clock.time(now),
                Modifier.align(Alignment.TopEnd).offset(y = minutes(range.start, now) - 9.dp).padding(end = 6.dp)
                    .clip(RoundedCornerShape(5.dp)).background(WHColors.Accent).padding(horizontal = 6.dp, vertical = 2.dp),
                style = WHType.GridHour.copy(fontWeight = FontWeight.Bold), color = WHColors.Surface, maxLines = 1,
            )
        }
    }
}

@Composable
private fun ColumnHead(member: DiaryMember, range: Span, model: DiaryViewModel, modifier: Modifier = Modifier) {
    val day = member.day
    val short = if (day.isClosed) "Off" else "${day.bookedCount} in"
    val firstStart = day.open.firstOrNull()?.start
    val detail = when {
        day.isClosed -> "Off"
        firstStart != null && firstStart > range.start && day.bookedCount == 0 -> "from ${model.clock.time(firstStart)}"
        else -> "${day.bookedCount} in" + (day.takingsPence?.let { " · ${it.formatted(model.currency)}" } ?: "")
    }
    // Narrow columns keep the person recognisable before they keep the takings.
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(WHColors.Surface)
            .clearAndSetSemantics { contentDescription = "${member.name}, $detail" }.padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        val room = maxWidth
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(WHColors.Neutral200), contentAlignment = Alignment.Center) {
                Text(member.initials, style = WHType.TagSmall.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Neutral700, maxLines = 1)
            }
            if (room >= 96.dp) {
                OneLine(if (room >= 190.dp) member.name else member.firstName, WHType.CardMeta.copy(fontWeight = FontWeight.SemiBold), WHColors.Ink, Modifier.weight(1f))
                Text(if (room >= 190.dp) detail else short, style = WHType.GridMeta, color = WHColors.Eyebrow, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TeamColumn(member: DiaryMember, range: Span, height: Dp, closed: Boolean, model: DiaryViewModel, now: Instant, modifier: Modifier = Modifier) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)).background(WHColors.Surface)
            .semantics { contentDescription = "${member.name}'s day" },
    ) {
        for (i in 0 until maxOf((height.value / 60).toInt(), 1)) {
            Box(Modifier.offset(y = (i * 60).dp).fillMaxWidth().height(1.dp).background(WHColors.Divider))
        }
        for (off in GridGeometry.offDuty(member.day.open, range)) OffDutyPanel(off, member, range, closed, model)
        // The web's stacking: gaps underneath, then breaks, then bookings — so a
        // gap that overlaps a booking never writes across it.
        for (gap in member.day.gaps) GapOutline(gap, range, model)
        for (block in member.day.breaks) BreakBlock(block, range, height, model)
        for (row in member.day.appointments) GridAppointment(row, range, height, model, now)
    }
}

@Composable
private fun BoxScope.OffDutyPanel(off: GridGeometry.OffDuty, member: DiaryMember, range: Span, closed: Boolean, model: DiaryViewModel) {
    val height = off.span.minutes.toFloat().dp
    val label = if (closed) null else when (off.position) {
        GridGeometry.OffDutyPosition.AllDay -> "${member.name} is not in"
        GridGeometry.OffDutyPosition.Before -> member.day.open.firstOrNull()?.let { "${member.name} starts at ${model.clock.time(it.start)}" }
        GridGeometry.OffDutyPosition.After -> member.day.open.lastOrNull()?.let { "Finished at ${model.clock.time(it.end)}" }
        GridGeometry.OffDutyPosition.Between -> "Not working"
    }
    Box(Modifier.offset(y = minutes(range.start, off.span.start)).fillMaxWidth().height(height).background(WHColors.Break).hatch(), contentAlignment = Alignment.Center) {
        if (label != null && height >= 24.dp) {
            Text(label, Modifier.padding(horizontal = 8.dp), style = WHType.Meta, color = WHColors.Muted, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

@Composable
private fun BoxScope.BreakBlock(block: DiaryBreak, range: Span, gridHeight: Dp, model: DiaryViewModel) {
    val top = maxOf(minutes(range.start, block.startsAt), 0.dp)
    val height = minOf(minutes(block.startsAt, block.endsAt), gridHeight - top)
    if (height <= 0.dp) return
    val label = "${block.label} · ${model.clock.time(block.startsAt)}"
    Box(
        Modifier.offset(y = top + 1.dp).padding(horizontal = 4.dp).fillMaxWidth().height(maxOf(height - 2.dp, 2.dp))
            .clip(RoundedCornerShape(7.dp)).background(WHColors.Break).hatch().clearAndSetSemantics { contentDescription = label },
        contentAlignment = if (height >= 28.dp) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (height >= 14.dp) OneLine(label, WHType.GridMeta.copy(fontWeight = FontWeight.SemiBold), WHColors.Muted, Modifier.padding(horizontal = 9.dp).padding(top = if (height >= 28.dp) 6.dp else 0.dp))
    }
}

/** The outline says how long the stretch is. Offering it to the waiting list arrives with that list, in A6. */
@Composable
private fun BoxScope.GapOutline(gap: DiaryGap, range: Span, model: DiaryViewModel) {
    val height = maxOf(gap.minutes.dp - 2.dp, 2.dp)
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.offset(y = minutes(range.start, gap.startsAt) + 1.dp).padding(horizontal = 4.dp).fillMaxWidth().height(height).border(1.dp, WHColors.Divider, shape)
            .clearAndSetSemantics { contentDescription = "${Durations.label(gap.minutes)} free from ${model.clock.time(gap.startsAt)}" },
        contentAlignment = Alignment.Center,
    ) {
        if (height >= 16.dp) OneLine("${Durations.short(gap.minutes)} free", WHType.Meta, WHColors.Muted)
    }
}

@Composable
private fun BoxScope.GridAppointment(row: DiaryAppointment, range: Span, gridHeight: Dp, model: DiaryViewModel, now: Instant) {
    val top = minutes(range.start, row.startsAt)
    val density = GridGeometry.density(row.minutes)
    val inChair = row.isInTheChair(now)
    val unpaid = row.depositMissing && !inChair
    // Never past the bottom of the grid.
    val drawn = minOf(maxOf(row.minutes, 5).dp, gridHeight - top)
    val meta = listOfNotNull(row.serviceName, model.clock.time(row.startsAt), row.pricePence?.formatted(model.currency)).joinToString(" · ")
    val edge = if (unpaid) WHColors.Accent else WHColors.Ink

    val base = Modifier.offset(y = top + 1.dp).padding(horizontal = 4.dp).fillMaxWidth()
        .clickable(role = Role.Button, onClickLabel = "Open the appointment") { model.open(row.id) }
        .semantics(mergeDescendants = true) { contentDescription = row.spoken(model.clock, model.currency, now) }
        .testTag("appointment-${row.id}")

    if (density == GridGeometry.Density.Tick) {
        // Too short for words: a mark where it sits, still big enough to find with a finger.
        Box(base.height(maxOf(drawn, 12.dp)), contentAlignment = Alignment.TopStart) {
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)).background(edge))
        }
        return
    }

    val shape = RoundedCornerShape(7.dp)
    Row(
        base.height(maxOf(drawn - 2.dp, 2.dp))
            .then(if (inChair) Modifier.shadow(4.dp, shape, ambientColor = WHColors.Shadow, spotColor = WHColors.Shadow) else Modifier)
            .clip(shape).background(if (inChair) WHColors.Ink else if (unpaid) WHColors.BlockUnpaid else WHColors.Block),
    ) {
        if (!inChair) Box(Modifier.width(3.dp).fillMaxHeight().background(edge))
        Box(Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { }) {
            val nameColor = if (inChair) WHColors.Bg else WHColors.Ink
            val metaColor = if (inChair) WHColors.InkSoft else WHColors.Neutral700
            if (density == GridGeometry.Density.Single) {
                Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(row.displayName, style = WHType.GridMeta.copy(fontWeight = FontWeight.SemiBold), color = nameColor, maxLines = 1)
                    OneLine(meta, WHType.GridHour, metaColor, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OneLine(row.displayName, WHType.GridName, nameColor, Modifier.weight(1f, fill = false))
                        if (row.repeats && !inChair) RepeatMark()
                        if (inChair) WHTag("In the chair", TagTone.OnInk)
                        if (unpaid) WHTag("No deposit", TagTone.Solid)
                        if (row.replyCount > 0 && !inChair) Box(Modifier.size(7.dp).clip(CircleShape).background(WHColors.Accent))
                    }
                    OneLine(if (unpaid) "$meta in the chair" else meta, WHType.GridMeta, metaColor)
                    if (inChair && row.minutes >= 70) {
                        var detail = "Started ${model.clock.time(row.startsAt)} · ${Durations.short(row.minutes)}"
                        row.depositPaidPence?.takeIf { it.value > 0 }?.let { detail += " · paid ${it.formatted(model.currency)} deposit" }
                        if (row.depositMissing) detail += " · no deposit taken"
                        OneLine(detail, WHType.GridMeta, WHColors.InkWarm, Modifier.padding(top = 6.dp))
                    }
                }
            }
            for (free in row.freeInside) {
                val inside = free.minutes.toFloat().dp
                if (inside >= 12.dp) {
                    Box(
                        Modifier.offset(y = minutes(row.startsAt, free.start)).fillMaxWidth().height(inside).background(WHColors.Surface).hatch(),
                        contentAlignment = Alignment.CenterEnd,
                    ) { Text("free", Modifier.padding(horizontal = 8.dp), style = WHType.GridHour, color = WHColors.Accent) }
                }
            }
        }
    }
}
