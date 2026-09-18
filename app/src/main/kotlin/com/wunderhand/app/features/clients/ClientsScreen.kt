package com.wunderhand.app.features.clients

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.core.ClientFilter
import com.wunderhand.core.ClientRow
import com.wunderhand.core.ClientWords
import com.wunderhand.core.Me
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.OneLine
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.RowDivider
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.liftSmall
import java.util.UUID

/**
 * The Clients tab (chairtime `app/(pro)/clients`).
 *
 * On a phone, the list, and opening somebody replaces it. On a tablet the list
 * keeps its own pane and the person opens beside it, so reading one client
 * does not cost the list you were working through — as the web's laptop layout
 * does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(app: AppModel, me: Me) {
    val model: ClientsViewModel = viewModel(key = "clients-${me.shop.id}-${me.staff.id}") {
        ClientsViewModel(me, app.client, app::handle, createSavedStateHandle())
    }
    val state by model.state.collectAsStateWithLifecycle()
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 840.dp
    val openId = state.openId

    // The system's back: out of the notes, then out of the person, then it is the app's.
    BackHandler(enabled = openId != null && (!wide || state.showingHealth)) { if (state.showingHealth) model.showHealth(false) else model.open(null) }

    @Composable
    fun detail(id: String) {
        if (state.showingHealth) {
            val name = state.response?.clients?.firstOrNull { it.id == id }?.name
            HealthScreen(id, name, app, model.clock, wide, onBack = { model.showHealth(false) })
        } else {
            ClientProfileScreen(id, app, model, wide, onBack = { model.open(null) })
        }
    }

    Box(Modifier.fillMaxSize().background(WHColors.Bg).testTag("clients")) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                ClientList(model, state, wide = true, Modifier.width(340.dp).fillMaxHeight())
                Box(Modifier.fillMaxHeight().width(1.dp).background(WHColors.Divider))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (openId != null) detail(openId)
                    else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WHIcon(WHIcons.Users, size = 30.dp, tint = WHColors.Neutral500)
                        Text("Pick somebody from the list", style = WHType.RowName, color = WHColors.Ink)
                        Text("Their visits, notes and medical notes open here.", style = WHType.Body, color = WHColors.Neutral700)
                    }
                }
            }
        } else if (openId != null) {
            detail(openId)
        } else {
            ClientList(model, state, wide = false, Modifier.fillMaxSize())
        }
    }

    if (state.isAdding) {
        ModalBottomSheet(onDismissRequest = { model.adding(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WHColors.Bg, dragHandle = null) {
            // A new form each time Add is pressed: the last new client's name is not the next one's.
            val form: ClientFormViewModel = viewModel(key = "client-form-new-${androidx.compose.runtime.saveable.rememberSaveable { UUID.randomUUID().toString() }}") {
                ClientFormViewModel(null, app.client, app::handle, createSavedStateHandle())
            }
            ClientFormScreen(form, onSaved = model::added, onRemoved = {}, onClose = { model.adding(false) })
        }
    }
}

// region The list

@Composable
private fun ClientList(model: ClientsViewModel, state: ClientsState, wide: Boolean, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { ListHeader(model, state, wide) }
            item { FilterChips(model, state, wide) }
            val response = state.response
            when {
                response == null -> if (state.failure == null) item {
                    Column(Modifier.padding(horizontal = 22.dp).padding(top = 24.dp).semantics { contentDescription = "Loading clients" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(6) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SkeletonBlock(40.dp, width = 40.dp, radius = 20.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { SkeletonBlock(13.dp, width = 150.dp); SkeletonBlock(10.dp, width = 100.dp) }
                            }
                        }
                    }
                }
                response.clients.isEmpty() -> item { EmptyList(state, wide) }
                else -> {
                    item { Box(Modifier.height(16.dp)) }
                    items(response.clients, key = { it.id }) { row ->
                        ClientRowView(row, model, wide, selected = wide && state.openId == row.id) { focus.clearFocus(); model.open(row.id) }
                    }
                    item { Box(Modifier.height(20.dp)) }
                }
            }
        }
        if (!wide) FooterBar { PrimaryButton("Add a client", { model.adding(true) }, Modifier.testTag("addClient")) }
    }
}

@Composable
private fun ListHeader(model: ClientsViewModel, state: ClientsState, wide: Boolean) {
    val response = state.response
    Column(Modifier.padding(horizontal = if (wide) 18.dp else 22.dp).padding(top = if (wide) 20.dp else 18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Eyebrow(response?.let { if (it.isCapped) "The first ${it.limit}" else ClientWords.people(it.clients.size) } ?: " ", Modifier.testTag("clientCount"))
                Text("Clients", Modifier.semantics { heading() }.testTag("clientsHeading"), style = if (wide) WHType.DiaryTitle.copy(fontSize = WHType.DiaryTitle.fontSize * 0.93f) else WHType.DiaryTitle, color = WHColors.Ink)
            }
            if (wide) PrimaryButton("Add a client", { model.adding(true) }, Modifier.testTag("addClient"), fill = false)
        }

        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 48.dp).liftSmall(shape).clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WHIcon(WHIcons.Search, size = 18.dp, tint = WHColors.Neutral500)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) Text("Search by name or number", Modifier.clearAndSetSemantics { }, style = WHType.FieldValue, color = WHColors.Neutral500)
                val focus = LocalFocusManager.current
                BasicTextField(
                    state.query, model::search,
                    // Tappable down the whole row, not just the line of text.
                    Modifier.fillMaxWidth().padding(vertical = 13.dp).semantics { contentDescription = "Search by name or number" }.testTag("clientSearch"),
                    textStyle = WHType.FieldValue.copy(color = WHColors.Ink), singleLine = true, cursorBrush = SolidColor(WHColors.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
            }
            if (state.query.isNotEmpty()) {
                Box(Modifier.size(48.dp).clickable(role = Role.Button) { model.search("") }.semantics { contentDescription = "Clear the search" }, contentAlignment = Alignment.Center) {
                    WHIcon(WHIcons.CircleX, size = 18.dp, tint = WHColors.Neutral500)
                }
            }
        }

        state.failure?.let { NoteCard(it, Modifier.padding(top = 14.dp)) }
        if (response?.isCapped == true) Text(ClientWords.capped(response.limit), Modifier.padding(top = 12.dp), style = WHType.CardMeta, color = WHColors.Neutral700)
    }
}

/** Filters that scroll sideways on a phone and wrap in the tablet's narrow pane. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(model: ClientsViewModel, state: ClientsState, wide: Boolean) {
    @Composable
    fun chips() {
        for (filter in ClientFilter.entries) {
            val on = state.filter == filter
            val count = state.response?.count(filter) ?: 0
            val showCount = filter != ClientFilter.All && count > 0
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Tab, indication = null, interactionSource = null) { model.filter(filter) }
                    .semantics(mergeDescendants = true) { selected = on; contentDescription = if (showCount) "${filter.label}, $count" else filter.label }.testTag("filter-${filter.raw}"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.liftSmall(CircleShape).clip(CircleShape).background(if (on) WHColors.Ink else WHColors.Surface).padding(horizontal = 14.dp, vertical = 8.dp).clearAndSetSemantics { },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(filter.label, style = WHType.Meta.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) WHColors.Bg else WHColors.Neutral700, maxLines = 1)
                    if (showCount) Text("$count", style = WHType.Meta, color = if (on) WHColors.Bg.copy(alpha = 0.7f) else WHColors.Eyebrow)
                }
            }
        }
    }
    if (wide) FlowRow(Modifier.padding(horizontal = 18.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    else Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

@Composable
private fun ClientRowView(row: ClientRow, model: ClientsViewModel, wide: Boolean, selected: Boolean, onOpen: () -> Unit) {
    val spoken = listOfNotNull(row.name, row.visitsAndSpend(model.currency), "${row.noShowCount} missed".takeIf { row.noShowCount > 0 }, row.tag).joinToString(", ")
    Column {
        RowDivider()
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (selected) WHColors.Surface else WHColors.Bg)
                .clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = spoken; this.selected = selected }.testTag("clientRow"),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) WHColors.Ink else WHColors.Bg.copy(alpha = 0f)))
            Row(Modifier.weight(1f).clearAndSetSemantics { }.padding(start = if (wide) 12.dp else 16.dp, end = if (wide) 18.dp else 22.dp).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(if (wide) 32.dp else 40.dp).clip(CircleShape).background(WHColors.Neutral200), contentAlignment = Alignment.Center) {
                    Text(row.initials, style = if (wide) WHType.Tag else WHType.CardMeta.copy(fontWeight = FontWeight.SemiBold), color = WHColors.Neutral700, maxLines = 1)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OneLine(row.name, if (wide) WHType.Semi14 else WHType.RowName, WHColors.Ink)
                    Text(
                        buildAnnotatedString {
                            append(row.visitsAndSpend(model.currency))
                            if (row.noShowCount > 0) withStyle(SpanStyle(color = WHColors.Accent)) { append(" · ${row.noShowCount} missed") }
                        },
                        style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1,
                    )
                }
                when (row.tag) {
                    "Due" -> Text("Due", Modifier.clip(RoundedCornerShape(5.dp)).background(WHColors.Accent100).padding(horizontal = 7.dp, vertical = 2.dp), style = WHType.Tag, color = WHColors.Accent)
                    null -> Unit
                    else -> Text(row.tag.orEmpty(), style = WHType.Meta, color = WHColors.Neutral700)
                }
            }
        }
    }
}

/** Names what belongs here, as the web's empty list does. */
@Composable
private fun EmptyList(state: ClientsState, wide: Boolean) {
    val typed = state.query.trim()
    val (title, says) = when {
        typed.isNotEmpty() -> "Nobody by that name" to "Nothing matches “$typed”. Try a phone number, or part of a surname."
        state.filter != ClientFilter.All -> "Nobody here yet" to "Good news, mostly — this list stays empty until someone falls into it."
        else -> "No clients yet" to "Bookings add clients automatically, so this fills itself from here on. To bring an existing list over — a spreadsheet, your contacts, another booking system — use Import on the web."
    }
    Column(Modifier.padding(horizontal = if (wide) 18.dp else 22.dp).padding(top = 28.dp).testTag("noClients"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = WHType.EmptyTitle, color = WHColors.Ink)
        Text(says, style = WHType.Body, color = WHColors.Neutral700)
    }
}

// endregion
