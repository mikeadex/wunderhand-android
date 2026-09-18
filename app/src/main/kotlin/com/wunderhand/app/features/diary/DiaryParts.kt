package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wunderhand.core.DaySummary
import com.wunderhand.core.IsoDay
import com.wunderhand.core.UncollectedDeposits
import com.wunderhand.core.WeekDay
import com.wunderhand.app.features.booking.NewBookingStart
import com.wunderhand.design.AlertLine
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.LoadBar
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHChip
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHSegmented
import com.wunderhand.design.WHType
import com.wunderhand.design.hatch
import com.wunderhand.design.liftSmall
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

/** The time, told again every half minute: enough for a now-line and for
 *  "in the chair" to move on without anybody pulling to refresh. */
@Composable
fun rememberNow(everyMillis: Long = 30_000): State<Instant> {
    val now = remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(everyMillis) {
        while (true) {
            delay(everyMillis)
            now.value = Instant.now()
        }
    }
    return now
}

/** The type has been made big enough that side by side no longer fits. */
@Composable
fun isLargeType(): Boolean = LocalDensity.current.fontScale >= 1.3f

private val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = WHColors.Ink)

// region Alerts

/** The lines above the day: the shop's account state, and deposits that went
 *  uncollected. Said once, at the top, in the web's words. */
@Composable
fun DiaryAlertLines(state: DiaryState, currency: String) {
    val alerts = state.response?.alerts ?: return
    Column {
        alerts.status?.let { AlertLine(buildAnnotatedString { withStyle(bold) { append("${it.headline}.") } }) }
        alerts.uncollectedDeposits?.let { AlertLine(uncollected(it, currency)) }
    }
}

private fun uncollected(missed: UncollectedDeposits, currency: String): AnnotatedString = buildAnnotatedString {
    withStyle(bold) { append(missed.pence?.let { "${it.formatted(currency)} of deposits uncollected." } ?: "Deposits are not being collected.") }
    append(" Finish Stripe setup on the web.")
}

// endregion
// region Header

/** "13 booked · £412 · 2 gaps left", bold where the web is bold. */
fun summaryText(summary: DaySummary, currency: String, utilisation: Double? = null): AnnotatedString = buildAnnotatedString {
    withStyle(bold) { append("${summary.booked} booked") }
    summary.takingsPence?.let { append(" · "); withStyle(bold) { append(it.formatted(currency)) } }
    utilisation?.let { append(" · ${(it * 100).roundToInt()}% of rostered time sold") }
    summary.gapsLabel?.let { append(" · "); withStyle(bold.copy(color = WHColors.Accent)) { append(it) } }
}

/** "3 waiting" — the list itself arrives with the waiting list, in A6. */
private fun AnnotatedString.Builder.waiting(count: Int) {
    if (count > 0) append(" · $count waiting")
}

/** Eyebrow, "Today", the view switch and the sentence that sums the day up — on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiaryHeaderPhone(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        /* The day and the way of looking at it sit side by side, until the type
         * is big enough that the picker would leave the heading a letter wide.
         * A FlowRow does that by itself: what does not fit goes underneath. */
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Eyebrow(model.eyebrow)
                Text(state.heading, Modifier.semantics { heading() }.testTag("diaryHeading"), style = WHType.DiaryTitle, color = WHColors.Ink)
            }
            WHSegmented(
                listOf(DiaryMode.Day to "Day", DiaryMode.Grid to "Grid", DiaryMode.Week to "Week"),
                state.mode, model::setMode, Modifier.testTag("diaryMode"),
            )
        }

        val summary = DaySummary(state.shownMembers)
        if (state.mode != DiaryMode.Week && !summary.isClosed && state.members.isNotEmpty()) {
            Text(
                buildAnnotatedString { append(summaryText(summary, model.currency)); waiting(state.response?.waitingCount ?: 0) },
                Modifier.testTag("diarySummary"), style = WHType.Summary, color = WHColors.Neutral700,
            )
        }
    }
}

/** The laptop's header: the title with its summary beside it, and the view switch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiaryHeaderWide(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Eyebrow(model.eyebrow)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.Bottom) {
                Text(state.heading, Modifier.semantics { heading() }.testTag("diaryHeading"), style = WHType.DiaryTitleWide, color = WHColors.Ink)
                state.response?.let { response ->
                    val team = response.team
                    if (team.isClosed) {
                        Text("Nobody is working", Modifier.padding(bottom = 4.dp), style = WHType.SummaryWide, color = WHColors.Neutral700)
                    } else {
                        val sold = response.week.firstOrNull { it.isoDate == response.date }?.takeIf { it.openMinutes > 0 }?.utilisation
                        Text(
                            buildAnnotatedString { append(summaryText(DaySummary(team.members), model.currency, sold)); waiting(response.waitingCount) },
                            Modifier.padding(bottom = 4.dp).testTag("diarySummary"), style = WHType.SummaryWide, color = WHColors.Neutral700,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.weekContainsToday) TextButton("Back to today", WHColors.Neutral700, model::showToday)
            if (state.members.isNotEmpty()) {
                SecondaryButton("Block time", { model.blockingTime(true) }, Modifier.testTag("blockTime"), minHeight = 48.dp)
                PrimaryButton("New booking", { model.startBooking(NewBookingStart()) }, Modifier.testTag("newBooking"), fill = false)
            }
            WHSegmented(
                listOf(DiaryMode.Day to "Day", DiaryMode.Week to "Week", DiaryMode.List to "List"),
                state.mode, model::setMode, Modifier.testTag("diaryMode"), compact = false,
            )
        }
    }
}

/** Small words, a hand-sized target: 48dp to the touch however small the type. */
@Composable
fun TextButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = WHType.GutterTime, color = color, maxLines = 1)
    }
}

// endregion
// region Week strip

private fun WeekDay.spoken(today: String?, withSold: Boolean): String {
    val day = "${IsoDay.weekdayLong(isoDate)} ${IsoDay.dayOfMonth(isoDate)}"
    val isToday = if (isoDate == today) ", today" else ""
    val load = if (!isOpen) ", closed" else if (withSold) ", $booked booked, ${(utilisation * 100).roundToInt()}% sold" else ", $booked booked"
    return day + isToday + load
}

/**
 * Seven days across, each with a bar saying how full it is. All seven share
 * the width, so today is never scrolled off — until "MON" is wider than a
 * seventh of a phone. At large type each day takes the room its own words
 * need and the week scrolls sideways instead.
 */
@Composable
fun WeekStrip(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    val week = state.response?.week.orEmpty()
    val large = isLargeType()
    Row(
        (if (large) modifier.horizontalScroll(rememberScrollState()) else modifier.fillMaxWidth()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 4.dp),
    ) {
        for (day in week) {
            val selected = day.isoDate == state.response?.date
            val shape = RoundedCornerShape(10.dp)
            Column(
                (if (large) Modifier else Modifier.weight(1f))
                    .liftSmall(shape).clip(shape)
                    .background(if (selected) WHColors.Ink else if (day.isOpen) WHColors.Surface else WHColors.Well)
                    .clickable(role = Role.Tab) { model.show(day.isoDate) }
                    .semantics(mergeDescendants = true) { contentDescription = day.spoken(state.response?.today, false); this.selected = selected }
                    .heightIn(min = 62.dp)
                    .padding(horizontal = if (large) 12.dp else 0.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    IsoDay.weekdayShort(day.isoDate), Modifier.clearAndSetSemantics { }, style = WHType.StripDay, maxLines = 1,
                    color = if (selected) WHColors.Bg.copy(alpha = 0.6f) else if (day.isOpen) WHColors.Eyebrow else WHColors.Muted,
                )
                Text(
                    "${IsoDay.dayOfMonth(day.isoDate)}", Modifier.clearAndSetSemantics { }, style = WHType.StripDate, maxLines = 1,
                    color = if (selected) WHColors.Bg else if (day.isOpen) WHColors.Ink else WHColors.Muted,
                )
                LoadBar(day.utilisation, selected, 3.dp, Modifier.padding(top = 3.dp).width(24.dp).alpha(if (day.isOpen) 1f else 0f))
            }
        }
    }
}

/** "‹ Last week · Today · Next week ›" */
@Composable
fun WeekNav(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton("‹ Last week", WHColors.Neutral700, { model.shiftWeek(-1) }, Modifier.testTag("lastWeek"))
        if (!state.weekContainsToday) TextButton("Today", WHColors.Accent, model::showToday, Modifier.testTag("backToToday"))
        TextButton("Next week ›", WHColors.Neutral700, { model.shiftWeek(1) }, Modifier.testTag("nextWeek"))
    }
}

/**
 * The laptop's week: count and takings on each day with a load bar, and
 * arrows either side. Where seven of those will not fit whole — an unfolded
 * phone, or a tablet with an appointment open beside the grid — each day is
 * its name, its date and its bar, as on a phone: a figure cut off at "27 ·"
 * says less than no figure.
 */
@Composable
fun WideWeekStrip(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    val response = state.response ?: return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 1000.dp
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            WeekArrow("‹", "Previous week") { model.shiftWeek(-1) }
            for (day in response.week) {
                val selected = day.isoDate == response.date
                val shape = RoundedCornerShape(10.dp)
                val faint = if (selected) WHColors.Bg.copy(alpha = 0.6f) else if (day.isOpen) WHColors.Eyebrow else WHColors.Muted
                val strong = if (selected) WHColors.Bg else if (day.isOpen) WHColors.Ink else WHColors.Muted
                val weekday = IsoDay.weekdayShort(day.isoDate).lowercase().replaceFirstChar { it.uppercase() }
                Column(
                    Modifier.weight(1f).liftSmall(shape).clip(shape)
                        .background(if (selected) WHColors.Ink else if (day.isOpen) WHColors.Surface else WHColors.Well)
                        .clickable(role = Role.Tab) { model.show(day.isoDate) }
                        .semantics(mergeDescendants = true) { contentDescription = day.spoken(response.today, true); this.selected = selected }
                        .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 10.dp)
                        .testTag("day-${day.isoDate}"),
                    horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    if (compact) {
                        Text(weekday, Modifier.clearAndSetSemantics { }, style = WHType.GridHour, color = faint, maxLines = 1)
                        Text("${IsoDay.dayOfMonth(day.isoDate)}", Modifier.padding(top = 3.dp).clearAndSetSemantics { }, style = WHType.Button, color = strong, maxLines = 1)
                    } else {
                        val figures = if (day.isOpen) "${day.booked}" + (day.takingsPence?.let { " · ${it.formatted(model.currency)}" } ?: "") else "Closed"
                        Text("$weekday ${IsoDay.dayOfMonth(day.isoDate)}", Modifier.clearAndSetSemantics { }, style = WHType.GridHour, color = faint, maxLines = 1)
                        Text(figures, Modifier.padding(top = 5.dp).clearAndSetSemantics { }, style = WHType.CardMeta.copy(fontWeight = FontWeight.SemiBold), color = strong, maxLines = 1)
                    }
                    LoadBar(if (day.isOpen) day.utilisation else 0.0, selected, 4.dp, Modifier.padding(top = if (compact) 8.dp else 9.dp).fillMaxWidth())
                }
            }
            WeekArrow("›", "Next week") { model.shiftWeek(1) }
        }
    }
}

@Composable
private fun WeekArrow(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.width(40.dp).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(WHColors.Well)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Neutral700) }
}

/** "‹  7 – 13 Sept  ›" over the week view on a tablet. */
@Composable
fun WeekRangeNav(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    val week = state.response?.week.orEmpty()
    val first = week.firstOrNull()?.isoDate ?: return
    val last = week.last().isoDate
    fun dayMonth(iso: String) = IsoDay.weekdayDayMonth(iso).substringAfter(' ')
    val from = if (first.take(7) == last.take(7)) "${IsoDay.dayOfMonth(first)}" else dayMonth(first)
    Row(modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WeekArrow("‹", "Previous week") { model.shiftWeek(-1) }
        Text("$from – ${dayMonth(last)}", Modifier.widthIn(min = 160.dp).padding(horizontal = 8.dp), style = WHType.Semi14, color = WHColors.Ink)
        WeekArrow("›", "Next week") { model.shiftWeek(1) }
    }
}

// endregion
// region Staff chips

/** Narrow the day to one person. The grid always shows one person, so it has no "Everyone". */
@Composable
fun StaffChips(model: DiaryViewModel, state: DiaryState, modifier: Modifier = Modifier) {
    val gridId = state.gridMember(model.me.staff.id)?.id
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (state.mode != DiaryMode.Grid) WHChip("Everyone", state.focusStaffId == null, { model.focus(null) })
        for (member in state.members) {
            val isOn = if (state.mode == DiaryMode.Grid) gridId == member.id else state.focusStaffId == member.id
            WHChip(member.name, isOn, { model.focus(member.id) })
        }
    }
}

// endregion
// region States

@Composable
fun EmptyDayCard(modifier: Modifier = Modifier) {
    WHCard(modifier.testTag("emptyDay")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A clear day", style = WHType.EmptyTitle, color = WHColors.Ink)
            Text("Nothing booked yet.", style = WHType.Body, color = WHColors.Neutral700)
        }
    }
}

@Composable
fun ClosedDayCard(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WHColors.Break).hatch().padding(18.dp).testTag("closedDay"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Nobody is working this day", style = WHType.EmptyTitle.copy(fontSize = WHType.SheetTotal.fontSize), color = WHColors.Ink)
        Text("Hours are set per person, per weekday.", style = WHType.Medium14, color = WHColors.Neutral700)
    }
}

@Composable
fun NoTeamCard(modifier: Modifier = Modifier) {
    WHCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nobody takes bookings yet", style = WHType.EmptyTitle.copy(fontSize = WHType.SheetTotal.fontSize), color = WHColors.Ink)
            Text("The diary has a column for each person who can be booked.", style = WHType.Medium14, color = WHColors.Neutral700)
        }
    }
}

/** The shape of the diary while it loads, so nothing jumps when it arrives. */
@Composable
fun DiarySkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().semantics { contentDescription = "Loading the diary" }, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 18.dp).padding(top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(10.dp, width = 150.dp)
                    SkeletonBlock(26.dp, width = 110.dp)
                }
                Spacer(Modifier.weight(1f))
                SkeletonBlock(34.dp, width = 150.dp, radius = 8.dp)
            }
            SkeletonBlock(12.dp, Modifier.padding(top = 14.dp), width = 220.dp)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { SkeletonBlock(62.dp, Modifier.weight(1f), radius = 10.dp) }
            }
            Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                repeat(6) { i ->
                    Row(Modifier.alpha(1f - i * 0.13f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(12.dp, Modifier.padding(top = 12.dp, start = 10.dp), width = 36.dp)
                        SkeletonBlock(62.dp, Modifier.weight(1f), radius = 12.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun DiaryFailed(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 22.dp).padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader("Could not load the diary", style = WHType.PageTitle)
            Text(message, style = WHType.Body, color = WHColors.Neutral800)
            PrimaryButton("Try again", onRetry, Modifier.padding(top = 8.dp).widthIn(max = 260.dp).testTag("diaryRetry"))
        }
    }
}

/** A size in dp for a stretch of minutes, at so many dp to the hour. */
fun minutesToDp(minutes: Double, dpPerHour: Dp): Dp = dpPerHour * (minutes / 60.0).toFloat()

/** Underlined words that lead somewhere. */
val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline)

// endregion
