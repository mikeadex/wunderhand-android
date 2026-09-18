package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wunderhand.core.Agenda
import com.wunderhand.core.AgendaItem
import com.wunderhand.core.DiaryAppointment
import com.wunderhand.core.DiaryGap
import com.wunderhand.core.DiaryMember
import com.wunderhand.core.Durations
import com.wunderhand.core.Pence
import com.wunderhand.core.ShopClock
import com.wunderhand.design.OneLine
import com.wunderhand.design.TagTone
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHTag
import com.wunderhand.design.WHType
import com.wunderhand.design.liftSmall
import java.time.Instant

/** What a screen reader says for an appointment, wherever it is drawn. */
fun DiaryAppointment.spoken(clock: ShopClock, currency: String, now: Instant, who: String? = null): String = buildList {
    if (isInTheChair(now)) add("In the chair")
    add("${clock.time(startsAt)} to ${clock.time(endsAt)}")
    add(displayName)
    add(serviceName)
    who?.let { add("with $it") }
    if (isAtClient) add("at ${clientPostcode ?: "the client’s"}")
    pricePence?.let { add(it.formatted(currency)) }
    if (depositMissing) add("deposit not taken")
    if (replyCount > 0) add(if (replyCount == 1) "client replied" else "$replyCount replies")
    if (repeats) add("repeats")
}.joinToString(", ")

/**
 * The day as one list for the whole shop — not a shrunk grid
 * (chairtime `components/diary/Agenda.tsx`).
 *
 * Times run down a gutter, gaps are dividers between cards, and the
 * appointment under way is the dark card where it falls in the day.
 */
@Composable
fun AgendaView(members: List<DiaryMember>, model: DiaryViewModel, now: Instant, modifier: Modifier = Modifier) {
    val items = Agenda.items(members)
    // "with Sam" on every card of Sam's own day is noise.
    val showWho = members.size > 1

    Column(modifier.testTag("agenda"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (item in items) {
            when (item) {
                is AgendaItem.Booked -> AgendaCard(item.appointment, item.who.takeIf { showWho }, model, now)
                is AgendaItem.Free -> GapDivider(item.gap, if (showWho) item.who else emptyList(), model.clock)
            }
        }
    }
}

@Composable
private fun AgendaCard(row: DiaryAppointment, who: String?, model: DiaryViewModel, now: Instant) {
    val inChair = row.isInTheChair(now)
    val past = row.isPast(now)
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open the appointment") { model.open(row.id) }
            .semantics(mergeDescendants = true) { contentDescription = row.spoken(model.clock, model.currency, now, who) }
            .testTag("appointment-${row.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            model.clock.time(row.startsAt),
            Modifier.width(46.dp).padding(top = if (inChair) 14.dp else 12.dp).clearAndSetSemantics { },
            style = WHType.GutterTime.copy(fontWeight = if (inChair) FontWeight.Bold else FontWeight.SemiBold),
            color = if (inChair) WHColors.Accent else if (past) WHColors.Neutral500 else WHColors.Muted,
            textAlign = TextAlign.End, maxLines = 1,
        )
        Box(Modifier.weight(1f).clearAndSetSemantics { }) {
            if (inChair) InChairCard(row, who, model) else OrdinaryCard(row, who, model, Modifier.alpha(if (past) 0.7f else 1f))
        }
    }
}

/** "Skin fade · 45m · with Sam · at E8 1AB" */
private fun metaLine(row: DiaryAppointment, duration: String, who: String?): String = buildList {
    add(row.serviceName)
    add(duration)
    who?.let { add("with $it") }
    if (row.isAtClient) add("at ${row.clientPostcode ?: "the client’s"}")
}.joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrdinaryCard(row: DiaryAppointment, who: String?, model: DiaryViewModel, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).liftSmall(shape).clip(shape).background(WHColors.Surface)) {
        // The edge says whether the money is in hand.
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (row.depositMissing) WHColors.Accent else WHColors.Ink))
        Column(Modifier.weight(1f).padding(start = 11.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OneLine(row.displayName, WHType.CardName, WHColors.Ink, Modifier.weight(1f, fill = false))
                    if (row.repeats) RepeatMark()
                }
                row.pricePence?.let { Text(it.formatted(model.currency), style = WHType.CardPrice, color = WHColors.Ink, maxLines = 1) }
            }
            OneLine(metaLine(row, Durations.short(row.minutes), who), WHType.CardMeta, WHColors.Neutral700, Modifier.padding(top = 4.dp))

            if (row.depositMissing || row.replyCount > 0) {
                FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.depositMissing) {
                        WHTag(row.pricePence?.let { "No deposit — take ${it.formatted(model.currency)} in the chair" } ?: "No deposit — take it in the chair", TagTone.Unpaid)
                    }
                    if (row.replyCount > 0) WHTag(if (row.replyCount == 1) "Client replied" else "${row.replyCount} replies", TagTone.Accent)
                }
            }
        }
    }
}

@Composable
private fun InChairCard(row: DiaryAppointment, who: String?, model: DiaryViewModel) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().shadow(5.dp, shape, ambientColor = WHColors.Shadow, spotColor = WHColors.Shadow).clip(shape)
            .background(WHColors.Ink).padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OneLine(row.displayName, WHType.CardName, WHColors.Bg, Modifier.weight(1f, fill = false))
            WHTag(if (row.status == "completed") "Done" else "In the chair", TagTone.OnInk)
        }
        OneLine(metaLine(row, "${model.clock.time(row.startsAt)}–${model.clock.time(row.endsAt)}", who), WHType.CardMeta, WHColors.InkSoft, Modifier.padding(top = 5.dp))

        val price = row.pricePence
        if (price != null) {
            Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(moneyLine(row, price, model.currency), Modifier.weight(1f), style = WHType.Meta, color = WHColors.InkWarm)
                Text(price.formatted(model.currency), style = WHType.CardPrice.copy(fontSize = WHType.Button.fontSize), color = WHColors.Bg, maxLines = 1)
            }
        } else if (row.depositMissing) {
            Text("No deposit taken", Modifier.padding(top = 11.dp), style = WHType.Meta, color = WHColors.InkWarm)
        }
    }
}

/** "£10 deposit paid · £58 to take" — the sums are chairtime's. Once the till
 *  has rung it through there is nothing to take, however long the hour has left to run. */
private fun moneyLine(row: DiaryAppointment, price: Pence, currency: String): String {
    if (row.status == "completed") return "Checked out"
    val toTake = (row.toTakePence ?: price).formatted(currency)
    val paid = row.depositPaidPence
    return when {
        paid != null && paid.value > 0 -> "${paid.formatted(currency)} deposit paid · $toTake to take"
        row.depositMissing -> "No deposit — take ${price.formatted(currency)}"
        else -> "$toTake to take"
    }
}

/** "13:00 · 1h 15m free · Ade, Kit", between two cards. Offering it to the
 *  waiting list ("Fill it") arrives with that list, in A6 — a button that does
 *  nothing is worse than no button. */
@Composable
private fun GapDivider(gap: DiaryGap, who: List<String>, clock: ShopClock) {
    val label = "${clock.time(gap.startsAt)} · ${Durations.short(gap.minutes)} free" + if (who.isEmpty()) "" else " · ${who.joinToString(", ")}"
    val spoken = "${Durations.label(gap.minutes)} free from ${clock.time(gap.startsAt)}" + if (who.isEmpty()) "" else " for ${who.joinToString(", ")}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 58.dp).clearAndSetSemantics { contentDescription = spoken }.testTag("gap"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(WHColors.Divider))
        Text(label, Modifier.weight(4f, fill = false), style = WHType.Meta.copy(fontSize = WHType.StripDay.fontSize * 1.14f), color = WHColors.Muted, maxLines = 2, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).height(1.dp).background(WHColors.Divider))
    }
}

/** The small mark on an appointment that is somebody's regular slot. Neutral,
 *  never red: on the diary red means something needs you. */
@Composable
fun RepeatMark(modifier: Modifier = Modifier) {
    WHIcon(WHIcons.Repeat, modifier, size = 13.dp, tint = WHColors.Neutral500, label = "Repeats")
}
