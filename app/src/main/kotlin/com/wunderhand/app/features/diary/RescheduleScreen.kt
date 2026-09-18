package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wunderhand.core.AppointmentDetail
import com.wunderhand.core.IsoDay
import com.wunderhand.core.SlotsResponse
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall
import kotlinx.coroutines.launch

/**
 * "Move this appointment" (chairtime `app/(pro)/diary/[id]/reschedule`): the
 * next open days with the same person, three times across. Tapping a time
 * moves it there.
 *
 * @param onDone back to the appointment — after a move, or without one.
 */
@Composable
fun RescheduleScreen(appointment: AppointmentDetail, model: AppointmentModel, state: AppointmentState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val clock = model.clock
    var from by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(from) { model.loadSlots(from) }
    // The system's back is back to the appointment, not out of the sheet.
    BackHandler(onBack = onDone)

    Column(modifier.fillMaxSize().background(WHColors.Bg).testTag("moveScreen")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Box(
                Modifier.padding(start = 14.dp, top = 12.dp).size(48.dp).clickable(role = Role.Button, onClick = onDone)
                    .semantics { contentDescription = "Back to the appointment" }.testTag("backToAppointment"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp).liftSmall(CircleShape).clip(CircleShape).background(WHColors.Surface).border(1.dp, WHColors.Divider, CircleShape), contentAlignment = Alignment.Center) {
                    WHIcon(WHIcons.ChevronLeft, size = 18.dp, tint = WHColors.Ink)
                }
            }

            Column(Modifier.padding(horizontal = 22.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Move this appointment", Modifier.semantics { heading() }, style = WHType.SheetName, color = WHColors.Ink)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = WHColors.Ink)) { append(appointment.displayName) }
                        append(" · ${appointment.serviceName} · currently ${clock.time(appointment.startsAt)} on ${clock.weekdayDayMonth(appointment.startsAt)}")
                    },
                    style = WHType.Body, color = WHColors.Neutral800,
                )
            }

            state.slotsProblem?.let { NoteCard(it, Modifier.padding(horizontal = 18.dp).padding(top = 16.dp).testTag("slotsProblem")) }

            val slots = state.slots
            if (slots == null) {
                Column(Modifier.padding(horizontal = 18.dp).padding(top = 12.dp).semantics { contentDescription = "Loading the times" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) {
                        SkeletonBlock(10.dp, Modifier.padding(top = 14.dp), width = 140.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { SkeletonBlock(48.dp, Modifier.weight(1f), radius = 10.dp) } }
                    }
                }
            } else {
                if (slots.days.isEmpty()) {
                    Text("Nothing free in the next few working days. Free some time up first, or block less out.", Modifier.padding(horizontal = 22.dp).padding(top = 24.dp), style = WHType.Body, color = WHColors.Neutral800)
                }
                for (day in slots.days) DaySection(day, model, state) { slot -> scope.launch { if (model.moveTo(slot.start)) onDone() } }
                slots.days.lastOrNull()?.let { last ->
                    WordsButton("Later days ›", { from = IsoDay.shift(last.isoDate, 1) }, Modifier.padding(horizontal = 14.dp).padding(top = 12.dp).testTag("laterDays"))
                }
            }
        }

        FooterBar(Modifier.navigationBarsPadding()) { Text("Tap a time to move it there.", style = WHType.CardMeta, color = WHColors.Neutral700) }
    }
}

@Composable
private fun DaySection(day: SlotsResponse.SlotDay, model: AppointmentModel, state: AppointmentState, onPick: (SlotsResponse.Slot) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(day.label, Modifier.padding(horizontal = 4.dp))
        for (row in day.slots.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in row) {
                    val shape = RoundedCornerShape(10.dp)
                    val time = model.clock.time(slot.start)
                    val isMoving = state.moving == slot.start
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).liftSmall(shape).clip(shape)
                            .background(if (slot.closesGapExactly) WHColors.Accent100 else WHColors.Surface).border(1.dp, WHColors.Divider, shape)
                            .clickable(enabled = state.moving == null, role = Role.Button) { onPick(slot) }
                            .semantics { contentDescription = time + if (slot.closesGapExactly) ", closes a gap exactly" else "" }
                            .testTag("slot"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isMoving) CircularProgressIndicator(Modifier.size(18.dp), color = WHColors.Ink, strokeWidth = 2.dp)
                        else Text(time, style = WHType.Button, color = if (slot.closesGapExactly) WHColors.Accent else WHColors.Ink)
                    }
                }
                // A short last row keeps its thirds.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
