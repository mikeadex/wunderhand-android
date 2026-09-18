package com.wunderhand.app.features.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.features.shell.EditorPage
import com.wunderhand.core.LeavingWords
import com.wunderhand.core.Me
import com.wunderhand.core.ShopClosing
import com.wunderhand.core.ShopClosure
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.design.liftSmall

/** The password, typed again. Masked, never suggested back by the keyboard, and offered to the phone's password manager to fill. */
@Composable
private fun PasswordAgain(value: String, onChange: (String) -> Unit, tag: String, isProblem: Boolean = false) {
    WHField(
        "Your password", value, onChange, inputModifier = Modifier.testTag(tag).semantics { contentType = ContentType.Password },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        visualTransformation = PasswordVisualTransformation(), isProblem = isProblem,
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().liftSmall(shape).clip(shape).background(WHColors.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

// region Your login

/**
 * Your login (chairtime `app/(pro)/shop/account`): the email you sign in with,
 * and how to delete it.
 *
 * The screen says what stays before it offers the button, because the person
 * most likely to reach for this is somebody leaving a shop, who should know
 * their appointments and takings stay in its books under their name. The
 * password is typed again, and the press is asked about once more, because
 * nothing here can be undone.
 */
@Composable
internal fun AccountScreen(app: AppModel, me: Me, onBack: (() -> Unit)?) {
    val model: AccountViewModel = viewModel(key = "shop-account-${me.user.email}") { AccountViewModel(me, app.client) { app.signOut() } }
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var confirming by remember { mutableStateOf(false) }
    // An unlocked phone put down on a counter should not come back with a password waiting in the box.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.forgetPassword() }

    EditorPage(
        title = "Your login", onClose = onBack, tag = "account", intro = model.whereTheySignIn, back = true, eyebrow = me.staff.name, closeLabel = "Back to the shop",
        footer = { PrimaryButton("Delete my login", { focus.clearFocus(); confirming = true }, Modifier.testTag("deleteLogin"), enabled = state.password.isNotEmpty(), loading = state.isDeleting) },
    ) {
        state.problem?.let { NoteCard(it, Modifier.padding(top = 18.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("accountProblem")) }
        Eyebrow("Delete my login", Modifier.padding(top = 28.dp))
        Text(model.whatGoes, Modifier.padding(top = 8.dp), style = WHType.Body, color = WHColors.Neutral800)
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (point in model.whatStays) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("·", Modifier.clearAndSetSemantics { }, style = WHType.Body, color = WHColors.Neutral500)
                Text(point, style = WHType.Body, color = WHColors.Neutral800)
            }
        }
        Box(Modifier.padding(top = 24.dp)) { PasswordAgain(state.password, model::type, "accountPassword", isProblem = state.problem != null) }
    }

    if (confirming) ConfirmDialog(
        title = "Delete your login?", message = "You will be signed out, here and everywhere. This cannot be undone.", confirm = "Delete my login",
        onConfirm = { model.delete() }, onDismiss = { confirming = false },
    )
}

// endregion
// region Close this shop

/**
 * Close this shop (chairtime `app/(pro)/shop/close`).
 *
 * It does not delete on the spot. The plan is cancelled, the booking page goes
 * dark, everything stays readable so it can be exported, and the records go
 * thirty days later — long enough that a shop closed in a bad week can be
 * reopened by writing in. So the screen asks for three things: the password
 * again, the shop's own address typed out, and one more press. And it says
 * first what closing leaves behind — the appointments already booked, whose
 * clients hear nothing from this.
 */
@Composable
internal fun CloseShopScreen(app: AppModel, me: Me, visit: Int, onBack: (() -> Unit)?) {
    val model: CloseShopViewModel = viewModel(key = "shop-close-${me.shop.id}") { CloseShopViewModel(me, app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.forgetPassword() }
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var confirming by remember { mutableStateOf(false) }
    val closing = state.closing
    val request = state.request

    EditorPage(
        title = if (state.isClosed) "This shop is closed" else "Close this shop", onClose = onBack, tag = "closeShop", back = true, eyebrow = me.shop.name, closeLabel = "Back to the shop",
        footer = closeFooter(me, state, model) { focus.clearFocus(); confirming = true },
    ) {
        Failure(state.failure, closing == null) { model.load() }
        state.problem?.let { NoteCard(it, Modifier.padding(top = 18.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("closeProblem")) }
        when {
            state.isClosed -> Card(Modifier.padding(top = 18.dp).testTag("shopClosed")) {
                Text("Closed. The plan is cancelled and nothing new can be booked.", style = WHType.RowName, color = WHColors.Ink)
                state.deleteAfter?.let { Text(LeavingWords.recordsGo(it, model.clock), Modifier.testTag("recordsGo"), style = WHType.Body, color = WHColors.Neutral800) }
                Text("Closed by mistake, or changed your mind? Write to us before then and the shop is reopened as it was.", style = WHType.Body, color = WHColors.Neutral800)
            }
            request != null -> Waiting(request, me, state, model)
            closing != null -> AskToClose(closing, state, model)
            state.failure == null -> Loading("what closing would mean", 3, 90)
        }
    }

    if (confirming) ConfirmDialog(
        title = "Close ${me.shop.name}?", message = "The plan is cancelled now, and the records are deleted ${closing?.graceDays ?: 30} days from today.", confirm = "Close the shop", keep = "Keep it open",
        onConfirm = { model.close() }, onDismiss = { confirming = false },
    )
}

/** What is pinned under the screen: nothing once it is closed, the answer to a request while one is open, and otherwise the button that asks. */
private fun closeFooter(me: Me, state: CloseShopState, model: CloseShopViewModel, ask: () -> Unit): (@Composable () -> Unit)? {
    val closing = state.closing
    val request = state.request
    return when {
        state.isClosed -> null
        request != null -> if (request.mine == ShopClosure.Standing.None) null else ({ RequestFooter(request, state, model) })
        closing != null -> ({
            PrimaryButton(
                "Close ${me.shop.name}", ask,
                Modifier.testTag("closeShopButton").semantics { contentDescription = "Close ${me.shop.name}. Asks once more before anything happens. The address to type is ${closing.slug}." },
                enabled = state.canAsk, loading = state.busy == ClosingAct.Asking,
            )
        })
        else -> null
    }
}

@Composable
private fun AskToClose(closing: ShopClosing, state: CloseShopState, model: CloseShopViewModel) {
    Card(Modifier.padding(top = 18.dp)) {
        Text("What closing does", style = WHType.RowName, color = WHColors.Ink)
        Text("Your plan is cancelled and the booking page goes dark. Nothing new can be booked.", style = WHType.Body, color = WHColors.Neutral800)
        Text(LeavingWords.recordsGo(closing.graceDays), style = WHType.Body, color = WHColors.Neutral800)
        Text(LeavingWords.upcoming(closing.upcoming), Modifier.testTag("closeUpcoming"), style = WHType.Body, color = if (closing.upcoming > 0) WHColors.Accent else WHColors.Neutral800)
    }
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PasswordAgain(state.password, model::typePassword, "closePassword")
        WHField(
            "Type ${closing.slug} to confirm", state.typed, model::typeConfirm, inputModifier = Modifier.testTag("closeConfirm"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done), placeholder = closing.slug,
        )
    }
}

/** A request the other owners have not all answered yet. */
@Composable
private fun Waiting(request: ShopClosure, me: Me, state: CloseShopState, model: CloseShopViewModel) {
    Card(Modifier.padding(top = 18.dp).testTag("closureWaiting")) {
        Text(
            if (request.mine == ShopClosure.Standing.Requester) "You asked to close ${me.shop.name}." else "${request.requestedBy.name} has asked to close ${me.shop.name}.",
            Modifier.testTag("closureAsked"), style = WHType.RowName, color = WHColors.Ink,
        )
        Text("A shop with more than one owner is not one owner's to close. It closes when every owner has agreed, and any one of them can end the request.", style = WHType.Body, color = WHColors.Neutral800)
        Column(Modifier.testTag("closurePartners"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (partner in request.partners) Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${partner.name}: ${if (partner.agreed) "agreed" else "not yet"}" },
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                WHIcon(if (partner.agreed) WHIcons.Check else WHIcons.ChevronRight, size = 16.dp, tint = if (partner.agreed) WHColors.Ink else WHColors.Neutral500)
                Text(partner.name, Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.Body, color = WHColors.Ink)
                Text(if (partner.agreed) "Agreed" else "Not yet", Modifier.clearAndSetSemantics { }, style = WHType.Semi14, color = if (partner.agreed) WHColors.Ink else WHColors.Neutral700)
            }
        }
        Text(LeavingWords.waitingOn(request.waitingOn), style = WHType.Body, color = WHColors.Neutral800)
        Text(LeavingWords.lapses(request.expiresAt, model.clock), style = WHType.Body, color = WHColors.Neutral700)
    }
    // Agreeing is an act like asking was: their own password, typed again.
    if (request.mine == ShopClosure.Standing.Partner) Box(Modifier.padding(top = 16.dp)) { PasswordAgain(state.password, model::typePassword, "agreePassword") }
}

/** Whoever asked takes it back; anybody else agrees, or ends it. */
@Composable
private fun RequestFooter(request: ShopClosure, state: CloseShopState, model: CloseShopViewModel) {
    val focus = LocalFocusManager.current
    when (request.mine) {
        ShopClosure.Standing.Requester -> PrimaryButton("Take the request back", { model.withdraw() }, Modifier.testTag("withdrawClosure"), enabled = state.busy == null, loading = state.busy == ClosingAct.Withdrawing)
        ShopClosure.Standing.Partner, ShopClosure.Standing.Agreed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (request.mine == ShopClosure.Standing.Partner) PrimaryButton(
                "Agree to close", { focus.clearFocus(); model.agree() }, Modifier.testTag("agreeToClose"),
                enabled = state.password.isNotEmpty() && state.busy == null, loading = state.busy == ClosingAct.Agreeing,
            )
            WordsButton(
                if (request.mine == ShopClosure.Standing.Agreed) "Change your mind and stop it" else "No — keep the shop open", { model.refuse() },
                Modifier.testTag("refuseClosure"), color = WHColors.Accent, enabled = state.busy == null, loading = state.busy == ClosingAct.Refusing,
            )
        }
        ShopClosure.Standing.None -> Unit
    }
}

// endregion
