package com.wunderhand.app.features.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.wunderhand.core.ActionWords
import com.wunderhand.core.BlockRequest
import com.wunderhand.core.IsoDay
import com.wunderhand.design.FooterBar
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHSegmented
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.network.ApiError
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Block off time — a break, admin, a holiday (chairtime `app/(pro)/diary/block`).
 *
 * The date and the times are the shop's wall clock, as the pro means them:
 * "13:00" is one o'clock at the shop wherever the phone happens to be. They
 * are sent as words — a date and two times — and chairtime places them in the
 * shop's zone, so nothing here converts anything.
 *
 * @param onBlocked the day it was blocked on, to go and look at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockTimeSheet(model: DiaryViewModel, state: DiaryState, onBlocked: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(BlockRequest.Kind.Break) }
    // The shop is an owner's: they may block anybody's time. Anybody else blocks
    // their own (chairtime `assertOwnerOrSelf`), so they are not asked whose.
    val isOwner = model.me.staff.isOwner
    var staffId by rememberSaveable { mutableStateOf(if (isOwner) state.focusStaffId ?: model.me.staff.id else model.me.staff.id) }
    var date by rememberSaveable { mutableStateOf(state.dayInHand ?: model.clock.isoDate(java.time.Instant.now())) }
    var from by rememberSaveable { mutableStateOf("13:00") }
    var to by rememberSaveable { mutableStateOf("13:30") }
    var note by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) } // "date", "from", "to", "who"

    fun save() {
        if (saving) return
        saving = true
        problem = null
        scope.launch {
            try {
                val created = model.api.blockTime(BlockRequest(staffId, kind, date, from, to, note.trim().ifEmpty { null }))
                onBlocked(created.date)
            } catch (error: ApiError) {
                when (error) {
                    is ApiError.Unauthorized -> { onClose(); model.handleElsewhere(error) }
                    is ApiError.SlotTaken -> problem = ActionWords.BLOCK_OVERLAPS
                    else -> problem = error.message
                }
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(WHColors.Bg).imePadding().testTag("blockTime")) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    WordsButton("Close", onClose, Modifier.testTag("closeBlockTime"), color = WHColors.Neutral700)
                }
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Block off time", Modifier.semantics { heading() }, style = WHType.SheetName, color = WHColors.Ink)
                    Text("Clients see the time as unavailable. Appointments already there stay put — move them yourself if you need to.", style = WHType.Body, color = WHColors.Neutral800)
                }

                problem?.let { NoteCard(it, Modifier.padding(horizontal = 18.dp).padding(top = 16.dp).testTag("blockProblem")) }

                Column(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WHAT FOR", Modifier.padding(horizontal = 4.dp).semantics { contentDescription = "What for" }, style = WHType.FieldLabel, color = WHColors.Neutral700)
                    WHSegmented(BlockRequest.Kind.entries.map { it to it.label }, kind, { kind = it }, compact = false)
                }

                Column(Modifier.padding(horizontal = 18.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isOwner && state.members.size > 1) {
                        Box {
                            Picked("Who", state.members.firstOrNull { it.id == staffId }?.name ?: "Choose", Modifier.testTag("blockWho")) { picking = "who" }
                            DropdownMenu(picking == "who", onDismissRequest = { picking = null }, containerColor = WHColors.Surface) {
                                for (member in state.members) {
                                    DropdownMenuItem(text = { Text(member.name, style = WHType.Medium14, color = WHColors.Ink) }, onClick = { staffId = member.id; picking = null })
                                }
                            }
                        }
                    }
                    Picked("Date", IsoDay.weekdayDayMonth(date), Modifier.testTag("blockDate")) { picking = "date" }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Picked("From", from, Modifier.weight(1f).testTag("blockFrom")) { picking = "from" }
                        Picked("To", to, Modifier.weight(1f).testTag("blockTo")) { picking = "to" }
                    }
                    val keyboard = LocalSoftwareKeyboardController.current
                    WHField(
                        "Note", note, { note = it }, inputModifier = Modifier.testTag("blockNote"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    )
                }
            }
        }

        FooterBar(Modifier.navigationBarsPadding()) {
            PrimaryButton("Block this time", ::save, Modifier.widthIn(max = 484.dp).testTag("blockThisTime"), loading = saving)
        }
    }

    if (picking == "date") {
        // The picker works in UTC midnights; a calendar date is the same words in any zone.
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                WordsButton("Choose", {
                    picker.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    picking = null
                })
            },
            dismissButton = { WordsButton("Cancel", { picking = null }, color = WHColors.Neutral700) },
        ) { DatePicker(picker) }
    }

    if (picking == "from" || picking == "to") {
        val isFrom = picking == "from"
        val (hour, minute) = (if (isFrom) from else to).split(":").map { it.toInt() }
        val picker = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        Dialog(onDismissRequest = { picking = null }) {
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(WHColors.Surface).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isFrom) "From" else "To", Modifier.fillMaxWidth().padding(bottom = 16.dp).semantics { heading() }, style = WHType.EmptyTitle, color = WHColors.Ink)
                TimePicker(picker)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WordsButton("Cancel", { picking = null }, color = WHColors.Neutral700)
                    WordsButton("Choose", {
                        val chosen = "%02d:%02d".format(picker.hour, picker.minute)
                        if (isFrom) from = chosen else to = chosen
                        picking = null
                    }, Modifier.testTag("chooseTime"))
                }
            }
        }
    }
}

/** A field whose value is picked rather than typed: the same card as [WHField], opening a picker. */
@Composable
private fun Picked(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape)
            .clickable(role = Role.DropdownList, onClick = onClick).semantics { contentDescription = "$label: $value" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), style = WHType.FieldLabel, color = WHColors.Neutral700)
            Text(value, style = WHType.FieldValue, color = WHColors.Ink)
        }
        WHIcon(WHIcons.ChevronsUpDown, size = 14.dp, tint = WHColors.Neutral500)
    }
}
