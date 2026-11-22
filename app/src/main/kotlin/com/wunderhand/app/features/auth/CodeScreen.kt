package com.wunderhand.app.features.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.PageColumn
import com.wunderhand.design.FooterBar
import com.wunderhand.design.Lockup
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHType
import com.wunderhand.network.ApiError
import com.wunderhand.network.Pending
import com.wunderhand.network.TwoFactorCode
import com.wunderhand.network.TwoFactorRefusal
import kotlinx.coroutines.launch

/**
 * What is typed on the code screen, held where a rotation cannot lose it.
 *
 * The half-done sign-in is not kept here: it belongs to the session's phase,
 * so there is no way to ask for a code without one, and it goes when the phase
 * does.
 */
class CodeForm : ViewModel() {
    var code by mutableStateOf("")
        private set
    var kind by mutableStateOf(TwoFactorCode.Authenticator)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isSigningIn by mutableStateOf(false)
        private set

    val isBackup: Boolean get() = kind == TwoFactorCode.Backup

    /** Which sign-in this form is answering — as a number, not the thing
     *  itself. Telling one sign-in from the next is all this needs, and the
     *  cookie that finishes one belongs to the session's phase, which lets go
     *  of it; a form that outlives the screen must not hold it longer. */
    private var asking: Int? = null

    /**
     * The screen has opened for a sign-in.
     *
     * This form belongs to the activity, not to the screen — so it outlives
     * both, and a second person signing in would otherwise find the first
     * one's backup-code mode and refusal still on it. A sign-in it has not
     * seen before starts it fresh; the one it is already on — the same screen
     * after a rotation — is left exactly as it was.
     */
    fun opened(pending: Pending) {
        if (asking == pending.hashCode()) return
        asking = pending.hashCode()
        code = ""
        kind = TwoFactorCode.Authenticator
        error = null
    }

    /**
     * Digits only and six of them, so a code read off a screen as "123 456" or
     * filled in from the keyboard arrives the way the server wants it — what
     * `tidyCode` does on the web, done as it is typed.
     *
     * A backup code keeps its hyphens, which belong to it; the web allows it
     * twenty-four characters.
     */
    fun type(typed: String) {
        code = if (isBackup) typed.take(24) else typed.filter { it.isDigit() }.take(6)
    }

    /** The other kind of code: the authenticator's, or one of the ten saved. */
    fun useOtherKind() {
        kind = if (isBackup) TwoFactorCode.Authenticator else TwoFactorCode.Backup
        code = ""
        error = null
    }

    fun signIn(model: AppModel, pending: Pending) {
        if (isSigningIn) return
        if (code.isBlank()) {
            error = "Type the code first."
            return
        }
        isSigningIn = true
        viewModelScope.launch {
            try {
                model.signIn(code, pending, kind)
                // It has worked, or it has sent them back to the password.
                // Either way this screen keeps nothing.
                code = ""
                error = null
            } catch (refusal: ApiError) {
                error = refusal.message
                /* A wrong code is worth another go, and the next one the
                 * authenticator shows will be a different six digits. A pause
                 * is not — leave what was typed where it is. */
                if ((refusal as? ApiError.TwoFactor)?.refusal == TwoFactorRefusal.WrongCode) code = ""
            } finally {
                isSigningIn = false
            }
        }
    }
}

/**
 * The second step of signing in, as the web's `/sign-in/code`: the six digits
 * the authenticator app shows, or one of the ten backup codes.
 *
 * Reached only from the password step, which hands over the half-done sign-in
 * this one finishes. Turning two-step sign-in on and off stays on the web —
 * this screen only ever asks.
 */
@Composable
fun CodeScreen(model: AppModel, pending: Pending, form: CodeForm = viewModel()) {
    val focus = LocalFocusManager.current
    val links = LocalUriHandler.current
    val codeField = remember { FocusRequester() }

    fun submit() {
        focus.clearFocus()
        form.signIn(model, pending)
    }

    LaunchedEffect(pending) {
        form.opened(pending)
        codeField.requestFocus()
    }

    Column(Modifier.fillMaxSize()) {
        PageColumn(Modifier.weight(1f)) {
            Column {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Lockup()
                    ScreenHeader(
                        if (form.isBackup) "Use a backup code" else "Enter your code",
                        style = WHType.PageTitle,
                    )
                    Text(
                        if (form.isBackup) {
                            "One of the ten codes you saved when you turned two-step sign-in on. Each works once."
                        } else {
                            "Open your authenticator app and type the 6-digit code it shows for Wunderhand."
                        },
                        style = WHType.Body,
                        color = WHColors.Muted,
                    )
                }

                AnimatedVisibility(form.error != null) {
                    NoteCard(form.error.orEmpty(), Modifier.padding(top = 20.dp).testTag("codeError"))
                }

                WHField(
                    if (form.isBackup) "Backup code" else "Code",
                    form.code,
                    form::type,
                    Modifier.padding(top = 20.dp),
                    // So the keyboard can offer the code rather than making
                    // somebody leave for the other app and come back.
                    inputModifier = Modifier.testTag("code").focusRequester(codeField)
                        .semantics { contentType = ContentType.SmsOtpCode },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        // NumberPassword, not Number: a decimal keyboard offers a separator a code has no use for.
                        keyboardType = if (form.isBackup) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )

                Column(Modifier.padding(top = 24.dp).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.heightIn(min = 48.dp).testTag("otherKindOfCode")
                            .clickable(role = Role.Button) { form.useOtherKind() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            if (form.isBackup) "Use the code from my authenticator app" else "Lost your phone? Use a backup code",
                            style = WHType.Link, color = WHColors.Ink, textDecoration = TextDecoration.Underline,
                        )
                    }

                    Text(
                        "No phone and no backup codes? Write to hello@wunderhand.com from the email you sign in with.",
                        style = WHType.Body,
                        color = WHColors.Muted,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { links.openUri("mailto:hello@wunderhand.com") },
                    )

                    Box(
                        Modifier.heightIn(min = 48.dp).testTag("startAgain")
                            .clickable(role = Role.Button) { model.startSignInAgain() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "Start again", style = WHType.Link, color = WHColors.Ink,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            }
        }

        FooterBar {
            PrimaryButton("Sign in", ::submit, Modifier.widthIn(max = 394.dp).testTag("sendCode"), loading = form.isSigningIn)
        }
    }
}
