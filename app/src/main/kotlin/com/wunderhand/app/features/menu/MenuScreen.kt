package com.wunderhand.app.features.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.shell.EditorSheet
import com.wunderhand.core.Me
import com.wunderhand.core.MenuResponse
import com.wunderhand.core.MenuService
import com.wunderhand.design.EmptyNote
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.TagTone
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHChip
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHTag
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import java.util.UUID

/**
 * The Menu tab (chairtime `app/(pro)/menu/page.tsx`): what clients book, with
 * what a pro scans for — the length, a develop gap, a patch test first, an age
 * floor — and the two things that stop a service being booked. A service is
 * added here and changed from its own page.
 *
 * On a phone the list, and a service replaces it. Unfolded, the list keeps its
 * own pane and the service opens beside it.
 */
@Composable
fun MenuScreen(app: AppModel, me: Me) {
    val model: MenuViewModel = viewModel(key = "menu-${me.shop.id}-${me.staff.id}") { MenuViewModel(me, app.client, app::handle, createSavedStateHandle()) }
    val state by model.state.collectAsStateWithLifecycle()
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 840.dp
    val openId = state.openId

    // Somebody else may have changed the menu on the web while this was in a pocket.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (model.state.value.response != null) model.load() }
    BackHandler(enabled = openId != null && !wide) { model.open(null) }

    Box(Modifier.fillMaxSize().background(WHColors.Bg).testTag("menu")) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                MenuList(model, state, wide = true, Modifier.width(360.dp).fillMaxHeight())
                Box(Modifier.fillMaxHeight().width(1.dp).background(WHColors.Divider))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (openId != null) ServiceDetailScreen(openId, app, model, wide = true, onBack = { model.open(null) })
                    else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WHIcon(WHIcons.Scissors, size = 30.dp, tint = WHColors.Neutral500)
                        Text("Pick a service from the menu", style = WHType.RowName, color = WHColors.Ink)
                        Text("Its price, how the time is used and who does it open here.", style = WHType.Body, color = WHColors.Neutral700)
                    }
                }
            }
        } else if (openId != null) {
            ServiceDetailScreen(openId, app, model, wide = false, onBack = { model.open(null) })
        } else {
            MenuList(model, state, wide = false, Modifier.fillMaxSize())
        }
    }

    if (state.isAdding) {
        EditorSheet(onDismiss = { model.adding(false) }) {
            // A new form each time: the last new service's name is not the next one's.
            val form: ServiceFormViewModel = viewModel(key = "service-form-new-${rememberSaveable { UUID.randomUUID().toString() }}") {
                ServiceFormViewModel(null, app.client, app::handle, createSavedStateHandle())
            }
            ServiceFormScreen(form, onSaved = { model.created(it.service.id) }, onArchived = {}, onClose = { model.adding(false) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuList(model: MenuViewModel, state: MenuState, wide: Boolean, modifier: Modifier = Modifier) {
    val menu = state.response
    Column(modifier) {
        PullToRefreshBox(state.isRefreshing, onRefresh = { model.load(byHand = true) }, Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 18.dp).padding(top = if (wide) 20.dp else 18.dp, bottom = 28.dp)) {
                    Header(menu, wide, onAdd = { model.adding(true) }.takeIf { wide && menu != null && model.isOwner })
                    when {
                        menu != null -> {
                            state.failure?.let { NoteCard(it, Modifier.padding(top = 14.dp)) }
                            if (menu.categories.size > 1) Categories(menu, state.category, model::category)
                            val shown = menu.services(state.category)
                            if (shown.isEmpty()) {
                                // A shop with a full menu looking at one empty category should not be told their menu is empty.
                                if (state.category != null) EmptyNote("Nothing in this one", "No service sits in this category yet. Pick All to see the rest of the menu.", Modifier.padding(top = 28.dp).testTag("emptyMenu"))
                                else EmptyNote("Nothing on the menu", "A service is what a client books. Add the one you do most and the rest can follow.", Modifier.padding(top = 28.dp).testTag("emptyMenu"))
                            } else {
                                WHCard(Modifier.padding(top = 16.dp)) {
                                    shown.forEachIndexed { index, service ->
                                        if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                                        ServiceRow(service, model.currency, selected = wide && state.openId == service.id) { model.open(service.id) }
                                    }
                                }
                            }
                        }
                        state.failure != null -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NoteCard(state.failure)
                            WordsButton("Try again", { model.load() })
                        }
                        else -> Column(Modifier.padding(top = 20.dp).semantics { contentDescription = "Loading the menu" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            repeat(6) { SkeletonBlock(62.dp, radius = 10.dp) }
                        }
                    }
                }
            }
        }
        // Adding to the menu is an owner's (chairtime `lib/auth/owner.ts`).
        if (!wide && menu != null && model.isOwner) FooterBar { PrimaryButton("Add a service", { model.adding(true) }, Modifier.testTag("addService")) }
    }
}

@Composable
private fun Header(menu: MenuResponse?, wide: Boolean, onAdd: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow(menu?.countLabel ?: " ", Modifier.testTag("menuCount"))
            Text("Menu", Modifier.semantics { heading() }.testTag("menuHeading"), style = if (wide) WHType.DiaryTitle.copy(fontSize = WHType.DiaryTitle.fontSize * 0.93f) else WHType.DiaryTitle, color = WHColors.Ink)
            if (menu != null) {
                val attention = listOfNotNull(
                    "${menu.unassignedCount} nobody can book".takeIf { menu.unassignedCount > 0 },
                    "${menu.offlineCount} not online".takeIf { menu.offlineCount > 0 },
                ).joinToString(" · ")
                if (attention.isNotEmpty()) Text(attention, Modifier.testTag("menuAttention"), style = WHType.Semi14, color = WHColors.Accent)
            }
        }
        if (onAdd != null) PrimaryButton("Add a service", onAdd, Modifier.testTag("addService"), fill = false)
    }
}

@Composable
private fun Categories(menu: MenuResponse, chosen: String?, onChoose: (String?) -> Unit) {
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()).testTag("menuCategories"), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        WHChip("All", chosen == null, { onChoose(null) })
        for (category in menu.categories) WHChip(category.name, chosen == category.id, { onChoose(category.id) })
    }
}

@Composable
private fun ServiceRow(service: MenuService, currency: String, selected: Boolean, onOpen: () -> Unit) {
    val price = service.priceLabel(currency)
    // Called out, not listed among the meta: a service nobody performs cannot be booked at all.
    val warning = if (service.isUnbookable) "Nobody assigned — cannot be booked" else null
    val spoken = listOfNotNull(service.name, price, service.metaLine, warning, "Not bookable online".takeIf { warning == null && !service.bookableOnline }).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().background(if (selected) WHColors.Well else WHColors.Surface).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = spoken; this.selected = selected }.testTag("menuService"),
    ) {
        Row(Modifier.weight(1f).clearAndSetSemantics { }.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(service.name, style = WHType.RowName, color = WHColors.Ink)
                Text(service.metaLine, style = WHType.Meta, color = WHColors.Neutral700)
                if (warning != null) WHTag(warning, TagTone.Accent, Modifier.padding(top = 2.dp))
                else if (!service.bookableOnline) Text("Not bookable online", style = WHType.Meta, color = WHColors.Neutral700)
            }
            Text(price, style = WHType.Semi14.copy(fontSize = WHType.Semi14.fontSize * 1.07f), color = WHColors.Ink, maxLines = 1)
            WHIcon(WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
