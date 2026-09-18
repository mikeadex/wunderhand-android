package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wunderhand.core.DiaryAppointment
import com.wunderhand.core.DragSnap
import com.wunderhand.core.Durations
import com.wunderhand.design.WHColors

/** How far a block on a grid has been moved or stretched, in minutes on the shop's booking grid. */
data class DragAdjust(
    val shiftMinutes: Int = 0,
    val stretchMinutes: Int = 0,
    val isLifted: Boolean = false,
    /** Let go of, and waiting for chairtime to agree. */
    val isSaving: Boolean = false,
)

/** What a draggable block needs: where it is now, the modifier that picks it up, and its handle. */
class BlockDrag(val adjust: DragAdjust, val pickUp: Modifier, val handle: @Composable BoxScope.() -> Unit)

/**
 * An appointment on a grid that can be picked up and moved, or stretched by
 * its handle (chairtime `DayGrid.tsx` and `TeamGrid.tsx`).
 *
 * Press and hold to pick up — not drag-on-touch, because the day scrolls and
 * a block that followed the finger at once could not be scrolled past. The
 * hold leaves a plain tap free to open the appointment. The handle stretches
 * straight away: it is a small, deliberate target nothing else competes with.
 * Both snap to the shop's slot interval, so a moved appointment lands where
 * the booking engine would also have offered. Nothing checks the new time is
 * free: chairtime's exclusion constraint decides.
 *
 * Somebody who cannot drag — TalkBack, a switch, a keyboard — gets the same
 * four things as named actions on the block: earlier, later, longer, shorter,
 * one step of the shop's grid at a time.
 *
 * @param dpPerMinute 1.6 on the phone's grid (96dp an hour), 1 on the team's.
 */
@Composable
fun rememberBlockDrag(row: DiaryAppointment, model: DiaryViewModel, state: DiaryState, dpPerMinute: Float, slotMinutes: Int): BlockDrag {
    var moving by remember(row.id) { mutableStateOf<Float?>(null) }
    var stretching by remember(row.id) { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(row)
    val idle = state.pending == null

    fun snapped(px: Float): Int = DragSnap.minutes((px / density.density).toDouble(), dpPerMinute.toDouble(), slotMinutes)

    // A change let go of and not yet answered stays where it was dropped.
    val pending = state.pending?.takeIf { it.appointmentId == row.id }
    val adjust = when {
        pending?.edge == PendingChange.Edge.Start -> DragAdjust(shiftMinutes = java.time.Duration.between(row.startsAt, pending.at).toMinutes().toInt(), isSaving = true)
        pending?.edge == PendingChange.Edge.End -> DragAdjust(stretchMinutes = java.time.Duration.between(row.endsAt, pending.at).toMinutes().toInt(), isSaving = true)
        else -> DragAdjust(moving?.let(::snapped) ?: 0, stretching?.let(::snapped) ?: 0, isLifted = moving != null || stretching != null)
    }

    fun moveBy(minutes: Int) { if (minutes != 0) model.move(current, current.startsAt.plusSeconds(minutes * 60L)) }
    fun stretchBy(minutes: Int) {
        // Never shorter than one step of the grid.
        if (minutes != 0 && current.minutes + minutes >= slotMinutes) model.resize(current, current.endsAt.plusSeconds(minutes * 60L))
    }

    val pickUp = Modifier
        .pointerInput(row.id, idle) {
            if (!idle) return@pointerInput
            detectDragGesturesAfterLongPress(
                onDragStart = { moving = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDrag = { change, amount -> change.consume(); moving = (moving ?: 0f) + amount.y },
                onDragEnd = { val by = moving?.let(::snapped) ?: 0; moving = null; moveBy(by) },
                onDragCancel = { moving = null },
            )
        }
        .semantics {
            if (idle) customActions = listOf(
                CustomAccessibilityAction("Move ${Durations.label(slotMinutes)} earlier") { moveBy(-slotMinutes); true },
                CustomAccessibilityAction("Move ${Durations.label(slotMinutes)} later") { moveBy(slotMinutes); true },
                CustomAccessibilityAction("Make ${Durations.label(slotMinutes)} longer") { stretchBy(slotMinutes); true },
                CustomAccessibilityAction("Make ${Durations.label(slotMinutes)} shorter") { stretchBy(-slotMinutes); true },
            )
        }

    val handle: @Composable BoxScope.() -> Unit = {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)
                .draggable(
                    rememberDraggableState { delta -> stretching = (stretching ?: 0f) + delta },
                    Orientation.Vertical, enabled = idle,
                    onDragStarted = { stretching = 0f },
                    onDragStopped = { val by = stretching?.let(::snapped) ?: 0; stretching = null; stretchBy(by) },
                )
                // The block's own named actions do this for a screen reader.
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(if (adjust.stretchMinutes != 0) WHColors.Accent else WHColors.Neutral300))
        }
    }

    return BlockDrag(adjust, pickUp, handle)
}

/**
 * The hold that picks a booking up is 0.35 s, as on the web and the iPhone —
 * Android's own long press is nearer half a second, which on a grid feels
 * like the app has not noticed.
 */
@Composable
fun QuickerHold(content: @Composable () -> Unit) {
    val system = LocalViewConfiguration.current
    val quicker = remember(system) {
        object : ViewConfiguration by system {
            override val longPressTimeoutMillis: Long get() = 350
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides quicker, content = content)
}

/** "10:15–11:00": where a lifted block would land. */
fun DiaryAppointment.liftedTimes(adjust: DragAdjust, model: DiaryViewModel): String =
    model.clock.time(startsAt.plusSeconds(adjust.shiftMinutes * 60L)) + "–" + model.clock.time(endsAt.plusSeconds((adjust.shiftMinutes + adjust.stretchMinutes) * 60L))

/** Minutes as a height on a grid. */
fun Int.minutesAt(dpPerMinute: Float): Dp = (this * dpPerMinute).dp
