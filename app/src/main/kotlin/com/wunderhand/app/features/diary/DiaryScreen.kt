package com.wunderhand.app.features.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.LocalReachability
import com.wunderhand.core.DaySummary
import com.wunderhand.core.Me
import com.wunderhand.core.SignalWords
import com.wunderhand.design.FooterBar
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.SignalBar
import com.wunderhand.design.WHColors
import java.time.Instant

/** From here up the day is the team's columns with the appointment beside
 *  them; below it, the agenda with the appointment as a sheet over it. */
private val WIDE_FROM = 840.dp

/** From here up there is room for the appointment beside the grid. Below it —
 *  an unfolded phone, a small tablet held upright — a panel would leave the
 *  columns a letter wide, so the appointment is a sheet over them instead. */
private val PANEL_FROM = 1000.dp

/**
 * The diary — the screen a shop opens every morning and leaves open all day.
 *
 * Two layouts from one set of data, as on the web: on a phone the day is an
 * agenda for the whole shop with a chip to narrow it; on a tablet at full
 * width it is a time grid with a column per person. The appointment opens as
 * a sheet on a phone, a panel beside the grid on a tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(app: AppModel, me: Me) {
    val model: DiaryViewModel = viewModel(key = "diary-${me.shop.id}-${me.staff.id}") {
        DiaryViewModel(me, app.client, app.cache, handle = app::handle, saved = createSavedStateHandle())
    }
    val state by model.state.collectAsStateWithLifecycle()
    val signal = LocalReachability.current
    val isOnline by signal.isOnline.collectAsStateWithLifecycle()
    val cameBack by signal.cameBack.collectAsStateWithLifecycle()
    val now by rememberNow()
    // The ring is for a pull only: a reload nobody asked for should not wave at them.
    var isRefreshing by remember { mutableStateOf(false) }

    // Back in front after being away: the day may have moved on without us.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (model.state.value.response != null) model.load() }
    // Out of the stockroom and back on the network: catch the day up without anybody asking.
    LaunchedEffect(cameBack) { if (cameBack > 0 && model.state.value.failure != null) model.load() }
    // While this screen's bar is up it speaks for the whole app.
    LaunchedEffect(state.isShowingAKeptDay) { app.aScreenIsSayingIt.value = state.isShowingAKeptDay }
    DisposableEffect(Unit) { onDispose { app.aScreenIsSayingIt.value = false } }

    // By the window, not by what is left of it: the rail and an open
    // appointment both take width, and neither should flip the layout.
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(LocalDensity.current) { window.width.toDp() }
    val wide = windowWidth >= WIDE_FROM
    val beside = windowWidth >= PANEL_FROM

    Box(Modifier.fillMaxSize().background(WHColors.Bg)) {
        LaunchedEffect(wide) { model.fitTo(wide) }

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // A day kept through a failed reload, with how old it is. The
                // screen stays usable; it just stops pretending to be current.
                AnimatedVisibility(state.isShowingAKeptDay, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SignalBar(
                        SignalWords.stale(state.loadedAt ?: now, now, model.clock, offline = !isOnline),
                        Modifier.testTag("staleDay"), isTrying = state.isLoading, onRetry = { model.load() },
                    )
                }
                // The day takes what the bar above and the footer below leave it.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.response == null && state.failure != null -> DiaryFailed(state.failure.orEmpty()) { model.load() }
                        state.response == null -> DiarySkeleton()
                        else -> PullToRefreshBox(
                            isRefreshing,
                            onRefresh = {
                                isRefreshing = true
                                model.load().invokeOnCompletion { isRefreshing = false }
                            },
                            Modifier.fillMaxSize(),
                        ) {
                            if (wide) DiaryWide(model, state, now) else DiaryPhone(model, state, now)
                        }
                    }
                }
                // The web's phone footer. "New booking" joins it in A3; until then
                // the one thing it does is block time off.
                if (!wide && state.response != null && state.members.isNotEmpty() && state.mode != DiaryMode.Week) {
                    FooterBar { SecondaryButton("Block off time", { model.blockingTime(true) }, Modifier.fillMaxWidth().widthIn(max = 724.dp).testTag("blockTime")) }
                }
            }

            // Beside the grid on a tablet.
            val open = state.openAppointmentId
            if (beside && open != null) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(WHColors.Divider))
                AppointmentSheet(open, app.client, model.clock, changed = { model.load().join() }, app::handle, onClose = { model.open(null) }, Modifier.width(420.dp))
            }
        }

        if (state.isBlockingTime) {
            ModalBottomSheet(
                onDismissRequest = { model.blockingTime(false) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = WHColors.Bg, dragHandle = null,
            ) { BlockTimeSheet(model, state, onBlocked = model::blocked, onClose = { model.blockingTime(false) }) }
        }

        // Over the day everywhere else.
        val open = state.openAppointmentId
        if (!beside && open != null) {
            ModalBottomSheet(
                onDismissRequest = { model.open(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = WHColors.Bg,
                dragHandle = null,
            ) {
                AppointmentSheet(open, app.client, model.clock, changed = { model.load().join() }, app::handle, onClose = { model.open(null) })
            }
        }
    }
}

@Composable
private fun DiaryPhone(model: DiaryViewModel, state: DiaryState, now: Instant) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        // A phone's layout on a small tablet is a column, not a stretched phone.
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 20.dp)) {
            DiaryAlertLines(state, model.currency)
            ActionProblem(model, state)
            DiaryHeaderPhone(model, state, Modifier.padding(horizontal = 18.dp).padding(top = 18.dp))
            WeekStrip(model, state, Modifier.padding(horizontal = 18.dp).padding(top = 16.dp))
            WeekNav(model, state, Modifier.padding(horizontal = 10.dp).padding(top = 4.dp))
            if (state.mode != DiaryMode.Week && state.members.size > 1) StaffChips(model, state, Modifier.padding(top = 4.dp))

            // The day asked for is on its way: what is on screen is the last one, dimmed.
            Box(Modifier.alpha(if (state.isShowingRequestedDay) 1f else 0.55f).testTag("showing-${state.response?.date}")) {
                val shown = state.shownMembers
                val summary = DaySummary(shown)
                val gridMember = state.gridMember(model.me.staff.id)
                when {
                    state.members.isEmpty() -> NoTeamCard(Modifier.padding(horizontal = 18.dp).padding(top = 24.dp))
                    state.mode == DiaryMode.Week -> WeekListView(model, state)
                    summary.isClosed -> ClosedDayCard(Modifier.padding(horizontal = 18.dp).padding(top = 24.dp))
                    state.mode == DiaryMode.Grid && gridMember != null && !gridMember.day.isClosed -> DayGridView(gridMember, model, state, now, Modifier.padding(top = 20.dp))
                    state.mode == DiaryMode.Grid -> ClosedDayCard(Modifier.padding(horizontal = 18.dp).padding(top = 24.dp))
                    summary.booked > 0 || summary.gaps > 0 -> AgendaView(shown, model, now, Modifier.padding(horizontal = 18.dp).padding(top = 20.dp))
                    else -> EmptyDayCard(Modifier.padding(horizontal = 18.dp).padding(top = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun DiaryWide(model: DiaryViewModel, state: DiaryState, now: Instant) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        DiaryAlertLines(state, model.currency)
        ActionProblem(model, state)
        DiaryHeaderWide(model, state, Modifier.padding(horizontal = 26.dp).padding(top = 24.dp, bottom = 20.dp))
        if (state.mode == DiaryMode.Week) WeekRangeNav(model, state, Modifier.padding(horizontal = 26.dp).padding(bottom = 14.dp))
        else WideWeekStrip(model, state, Modifier.padding(horizontal = 26.dp).padding(bottom = 18.dp))

        Box(Modifier.alpha(if (state.isShowingRequestedDay) 1f else 0.55f).testTag("showing-${state.response?.date}")) {
            val team = state.response?.team
            when {
                state.members.isEmpty() -> NoTeamCard(Modifier.padding(horizontal = 26.dp))
                state.mode == DiaryMode.Week -> WeekListView(model, state, Modifier.widthIn(max = 720.dp))
                state.mode == DiaryMode.List -> Box(Modifier.padding(horizontal = 26.dp).widthIn(max = 760.dp)) {
                    when {
                        team?.isClosed == true -> ClosedDayCard()
                        (team?.bookedCount ?: 0) > 0 || (team?.gapCount ?: 0) > 0 -> AgendaView(state.members, model, now)
                        else -> EmptyDayCard()
                    }
                }
                else -> TeamGridView(model, state, now)
            }
        }
    }
}

/** Why a drag or an unblock was refused, above the day, until the next change. */
@Composable
private fun ActionProblem(model: DiaryViewModel, state: DiaryState) {
    val problem = state.actionProblem ?: return
    Row(
        Modifier.fillMaxWidth().background(WHColors.Accent100).padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive }.testTag("diaryActionProblem"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(WHColors.Accent))
        Text(problem, Modifier.weight(1f).padding(vertical = 8.dp), style = WHType.CardMeta, color = WHColors.Ink)
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = model::dismissActionProblem).semantics { contentDescription = "Dismiss" },
            contentAlignment = Alignment.Center,
        ) { WHIcon(WHIcons.X, size = 14.dp, tint = WHColors.Neutral700) }
    }
}
