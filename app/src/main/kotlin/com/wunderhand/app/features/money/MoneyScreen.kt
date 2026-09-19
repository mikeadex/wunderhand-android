package com.wunderhand.app.features.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.core.Me
import com.wunderhand.core.MoneyResponse
import com.wunderhand.core.MoneyWords
import com.wunderhand.core.Pence
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.NoteCard
import com.wunderhand.design.OneLine
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall

/**
 * What the shop made (chairtime `app/(pro)/money/page.tsx`).
 *
 * Written for an owner at the end of a month who wants one number, and then
 * wants to know whether it is a good one. So the month leads, its comparison
 * sits directly under it, and everything else is evidence for those two
 * lines. Booked ahead is never added to earned; tips are never in a total;
 * chair sales are added and named. An owner sees the shop; anyone else sees
 * their own work — chairtime decides which.
 *
 * Every figure here is the shop's own takings. Nothing on this screen is what
 * Wunderhand costs: the app never says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyScreen(app: AppModel, me: Me) {
    val model: MoneyViewModel = viewModel(key = "money-${me.shop.id}-${me.staff.id}") { MoneyViewModel(me, app.client, app::handle) }
    val state by model.state.collectAsStateWithLifecycle()
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 840.dp
    // Somebody may have been rung through at the other till since this was last looked at.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (model.state.value.response != null) model.load() }

    val response = state.response
    PullToRefreshBox(state.isRefreshing, onRefresh = { model.load(byHand = true) }, Modifier.fillMaxSize().background(WHColors.Bg).testTag("money")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoColumns = maxWidth >= 760.dp
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 1080.dp).fillMaxWidth().padding(horizontal = if (wide) 26.dp else 18.dp).padding(top = if (wide) 24.dp else 18.dp, bottom = 28.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Eyebrow(response?.let { if (it.isShop) it.shopName else "Your work" } ?: me.shop.name, Modifier.testTag("moneyScope"))
                        Text("Money", Modifier.semantics { heading() }.testTag("moneyHeading"), style = if (wide) WHType.DiaryTitleWide else WHType.DiaryTitle, color = WHColors.Ink)
                    }
                    when {
                        response != null -> {
                            state.failure?.let { NoteCard(it, Modifier.padding(top = 14.dp)) }
                            Content(response, model, twoColumns)
                        }
                        state.failure != null -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NoteCard(state.failure.orEmpty())
                            WordsButton("Try again", { model.load() })
                        }
                        else -> Column(Modifier.padding(top = 16.dp).semantics { contentDescription = "Loading the money" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SkeletonBlock(180.dp, radius = 12.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { SkeletonBlock(90.dp, Modifier.weight(1f), radius = 12.dp); SkeletonBlock(90.dp, Modifier.weight(1f), radius = 12.dp) }
                            SkeletonBlock(200.dp, radius = 12.dp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(t: MoneyResponse, model: MoneyViewModel, twoColumns: Boolean) {
    val currency = t.currency
    val monthName = model.monthName
    val comparison = model.comparison(t)

    @Composable
    fun headline(modifier: Modifier = Modifier) = MoneyPanel(modifier, padding = 22.dp) {
        Eyebrow(monthName)
        // One line however big the month was: the figure shrinks before it is cut.
        Text(
            Pence(t.monthTook).formatted(currency), Modifier.padding(top = 8.dp).testTag("moneyMonth"), color = WHColors.Ink, maxLines = 1,
            style = WHType.DiaryTitleWide.copy(fontSize = 52.sp, letterSpacing = (-1.8).sp), autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 52.sp, stepSize = 2.sp),
        )
        // Against the same span of last month, not the whole of it.
        Text(comparison.text, Modifier.padding(top = 8.dp).testTag("moneyComparison"), style = WHType.Body,
            color = when (comparison.tone) { MoneyWords.Tone.Down -> WHColors.Accent; MoneyWords.Tone.Up -> WHColors.Ink; MoneyWords.Tone.Level -> WHColors.Neutral700 })
        comparison.finished?.let { Text(it, Modifier.padding(top = 2.dp), style = WHType.Meta, color = WHColors.Neutral700) }
        RowDivider(Modifier.padding(top = 16.dp))
        // Three across, until the type is big enough that they are not: then what does not fit goes underneath.
        FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HeadlineFact("Done", "${t.month.appointments}")
            HeadlineFact(if (t.month.clients == 0 && t.month.walkIns > 0) "Walk-ins" else "Clients", "${if (t.month.clients > 0) t.month.clients else t.month.walkIns}")
            if (t.month.bookedPence.value > 0) HeadlineFact("Still to come", t.month.bookedPence.formatted(currency), accent = true)
        }
    }

    @Composable
    fun tiles(modifier: Modifier = Modifier, stacked: Boolean) {
        if (t.nothingYet) {
            MoneyPanel(modifier.testTag("moneyNothingYet")) {
                Text("Nothing has been completed yet, so there is nothing to total. Mark an appointment as done and it will start filling in — this page counts finished work, not the diary.", style = WHType.Body, color = WHColors.Neutral700)
            }
            return
        }
        val year: @Composable (Modifier) -> Unit = { m ->
            Tile("Year to date", Pence(t.yearTook).formatted(currency), (if (t.lastYearToDate.earnedPence.value > 0) MoneyWords.change(t.yearTook, t.lastYearToDateTook, "on last year") else null) ?: MoneyWords.appointments(t.yearToDate.appointments), m)
        }
        val average: @Composable (Modifier) -> Unit = { m ->
            Tile("Average", t.averagePence.formatted(currency), if (t.till.yearToDate.extraPence.value > 0) "per appointment, before chair sales" else "per appointment", m)
        }
        if (stacked) Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) { year(Modifier); average(Modifier) }
        // Side by side they are a pair: as tall as each other, whichever has the longer note.
        else Row(modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) { year(Modifier.weight(1f).fillMaxHeight()); average(Modifier.weight(1f).fillMaxHeight()) }
    }

    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            headline(Modifier.weight(1f))
            tiles(Modifier.width(300.dp), stacked = true)
        } else {
            headline()
            // Side by side until the type is big enough that "YEAR TO DATE" is three lines and the figure beside it a sliver.
            tiles(stacked = LocalDensity.current.fontScale >= 1.5f)
        }

        val panels = evidence(t, model.thisMonthKey, monthName)
        if (twoColumns) {
            // Two columns of evidence, dealt alternately so neither runs long.
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                for (side in 0..1) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { panels.filterIndexed { i, _ -> i % 2 == side }.forEach { it() } }
            }
        } else panels.forEach { it() }
    }
}

/** The evidence, as the panels that apply: a shop that has never used the till is not shown a panel of noughts about it. */
private fun evidence(t: MoneyResponse, thisMonthKey: String, monthName: String): List<@Composable () -> Unit> = buildList {
    val currency = t.currency
    val chair = t.till.yearToDate.extraPence.value
    if (!t.nothingYet) {
        if (t.byMonth.size > 1) add {
            MoneyPanel(Modifier.testTag("moneyByMonth"), "Month by month") {
                Bars(t.byMonth.map { m -> BarRow(MoneyWords.monthLabel(m.month), m.tookPence, null, m.month == thisMonthKey, spoken = MoneyWords.monthName(m.month)) }, currency, wideLabel = false)
            }
        }
        // Service figures: with chair sales in the year's total they no longer sum to it.
        if (t.isShop && t.byPerson.size > 1) add {
            MoneyPanel(Modifier.testTag("moneyByPerson"), "Who earned it", if (chair > 0) "Service work, this year" else "This year") {
                Bars(t.byPerson.map { p -> BarRow(p.name, p.earnedPence.value, p.appointments) }, currency, wideLabel = true)
            }
        }
        if (t.byService.isNotEmpty()) add {
            MoneyPanel(Modifier.testTag("moneyByService"), "What sells", if (t.moreServices) "Top 8 this year" else "This year") {
                // Product sold at the till belongs in "what sells": an amount and a note, not a number of things.
                Bars(t.byService.map { s -> BarRow(s.name, s.earnedPence.value, s.appointments) } + listOfNotNull(BarRow("Sold in the chair", chair, null).takeIf { chair > 0 }), currency, wideLabel = true)
            }
        }
    }
    if (t.till.everUsed) add {
        MoneyPanel(Modifier.testTag("moneyTill"), "At the till", monthName) {
            Line("Rung through", "${t.till.month.settlements}")
            if (t.till.month.extraPence.value > 0) Line("Sold in the chair", t.till.month.extraPence.formatted(currency), note = "Counted in the month's total above.")
            if (t.till.month.tipPence.value > 0) Line("Tips", t.till.month.tipPence.formatted(currency), note = "Whoever did the work. Not in any total on this screen.", muted = true)
            if (t.till.byMethod.isNotEmpty()) {
                RowDivider(Modifier.padding(vertical = 12.dp))
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("How it was settled", Modifier.weight(1f))
                    Text("This year", style = WHType.Meta, color = WHColors.Neutral700)
                }
                Bars(t.till.byMethod.map { m -> BarRow(MoneyWords.methodLabel(m.method), m.pence.value, m.settlements) }, currency, wideLabel = true)
                Text("What was handed over at the counter, tips included, so cash here is what should be in the drawer. Most of it is the balance of work already counted above — it is not a figure to add to the month.", Modifier.padding(top = 10.dp), style = WHType.Meta, color = WHColors.Neutral700)
            }
        }
    }
    add {
        MoneyPanel(Modifier.testTag("moneyDeposits"), "Deposits") {
            Line("Taken through the app", t.deposits.collectedPence.formatted(currency))
            if (t.deposits.uncollectedPence.value > 0) {
                val shape = RoundedCornerShape(12.dp)
                val n = t.deposits.uncollectedCount
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(shape).background(WHColors.Accent100).border(1.dp, WHColors.Accent300, shape).padding(12.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text("Asked for, not taken", Modifier.weight(1f), style = WHType.Medium14, color = WHColors.Accent)
                        Text(t.deposits.uncollectedPence.formatted(currency), style = WHType.Medium14, color = WHColors.Accent)
                    }
                    Text("$n ${if (n == 1) "booking" else "bookings"} — your Stripe account could not charge.", style = WHType.Meta, color = WHColors.Accent)
                }
            }
            Text("Only deposits come through us. The rest is paid in the chair and never touches this app, so the totals above are what was earned rather than what we handled.", Modifier.padding(top = 10.dp), style = WHType.Meta, color = WHColors.Neutral700)
        }
    }
    if (t.discounts.givenPence.value > 0) add {
        MoneyPanel(Modifier.testTag("moneyDiscounts"), "What discounting cost", "This year") {
            Line("Given away", t.discounts.givenPence.formatted(currency))
            val n = t.discounts.bookings
            Text("Across $n ${if (n == 1) "booking" else "bookings"} — off-peak rates and offers. Worth it if those hours would otherwise have been empty, and worth knowing either way.", Modifier.padding(top = 8.dp), style = WHType.Meta, color = WHColors.Neutral700)
        }
    }
}

// region Pieces

/** A titled card of evidence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoneyPanel(modifier: Modifier = Modifier, title: String? = null, note: String? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(padding)) {
        // The title, and what it covers beside it — or under it, once the two no longer fit a line: a title is never broken mid-word to make room.
        if (title != null) FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), Modifier.semantics { contentDescription = title; heading() }, style = WHType.Eyebrow, color = WHColors.Eyebrow)
            if (note != null) Text(note, style = WHType.Meta, color = WHColors.Neutral700)
        }
        content()
    }
}

/** "Still to come, £257.20" — a figure is never read on its own. */
@Composable
private fun HeadlineFact(label: String, value: String, accent: Boolean = false) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
        Text(value, Modifier.clearAndSetSemantics { }, style = WHType.RowName.copy(fontWeight = FontWeight.Medium), color = if (accent) WHColors.Accent else WHColors.Ink)
    }
}

/** "Year to date, £44,601, up 12% on last year" — one thing to hear. */
@Composable
private fun Tile(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(16.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $value, $note" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), Modifier.clearAndSetSemantics { }, style = WHType.Eyebrow, color = WHColors.Eyebrow)
        Text(value, Modifier.clearAndSetSemantics { }, style = WHType.SheetName.copy(fontSize = 24.sp), color = WHColors.Ink, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp))
        Text(note, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
    }
}

/** Read as one line, so a figure is never spoken without its name. */
@Composable
private fun Line(label: String, value: String, note: String? = null, muted: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = listOfNotNull("$label: $value", note).joinToString(". ") }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.clearAndSetSemantics { }) {
            Text(label, Modifier.weight(1f), style = WHType.Body, color = if (muted) WHColors.Neutral700 else WHColors.Ink)
            Text(value, style = WHType.RowName.copy(fontWeight = FontWeight.Medium), color = if (muted) WHColors.Neutral700 else WHColors.Ink)
        }
        if (note != null) Text(note, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
    }
}

/** @param count appointments, or settlements: the small number before the money. @param spoken the label said in full — "June", where the bar says "Jun". */
private data class BarRow(val label: String, val pence: Int, val count: Int?, val isNow: Boolean = false, val spoken: String = label)

/**
 * A ranked list with the magnitude drawn in — a table more than a chart, since
 * an owner wants the actual figure for August. One hue, ink, because every bar
 * encodes the same thing and red here means "something needs you".
 */
@Composable
private fun Bars(rows: List<BarRow>, currency: String, wideLabel: Boolean) {
    val peak = maxOf(rows.maxOfOrNull { it.pence } ?: 1, 1)
    /* Beside the bar while the words fit the columns made for them. With the phone's text turned up they do not —
     * "£4,844" was cut to "£4,84", a wrong figure said confidently — so then the words sit above the bar,
     * with the whole row to themselves. */
    val roomy = LocalDensity.current.fontScale >= 1.3f

    @Composable
    fun bar(row: BarRow, modifier: Modifier) = Box(modifier.height(12.dp).clip(CircleShape).background(WHColors.Well)) {
        // A sliver even for a small month: nothing sold is no bar, something sold is never no bar.
        if (row.pence > 0) Box(Modifier.fillMaxWidth((row.pence.toFloat() / peak).coerceIn(0.03f, 1f)).height(12.dp).clip(CircleShape).background(WHColors.Ink))
    }

    Column(verticalArrangement = Arrangement.spacedBy(if (roomy) 14.dp else 9.dp)) {
        for (row in rows) {
            val money = Pence(row.pence).formatted(currency)
            val spoken = listOfNotNull(row.spoken, money, row.count?.let { "$it" }, "so far".takeIf { row.isNow }).joinToString(", ")
            val weight = if (row.isNow) FontWeight.Medium else FontWeight.Normal
            val name = if (roomy) row.spoken else row.label
            if (roomy) Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }.testTag("moneyBar"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(name, Modifier.weight(1f), style = WHType.Meta.copy(fontWeight = weight), color = if (row.isNow) WHColors.Ink else WHColors.Neutral700)
                    if (row.count != null) Text("${row.count}", style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1)
                    Text(money, style = WHType.Semi14.copy(fontWeight = weight), color = WHColors.Ink, maxLines = 1)
                }
                bar(row, Modifier.fillMaxWidth())
            } else Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }.testTag("moneyBar"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OneLine(name, WHType.Meta.copy(fontWeight = weight), if (row.isNow) WHColors.Ink else WHColors.Neutral700, Modifier.width(if (wideLabel) 96.dp else 36.dp))
                bar(row, Modifier.weight(1f))
                if (row.count != null) Text("${row.count}", Modifier.widthIn(min = 26.dp), style = WHType.Meta, color = WHColors.Neutral700, textAlign = TextAlign.End, maxLines = 1)
                Text(money, Modifier.width(78.dp), style = WHType.Semi14.copy(fontWeight = weight), color = WHColors.Ink, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

// endregion
