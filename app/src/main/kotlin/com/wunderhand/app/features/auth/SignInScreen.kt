package com.wunderhand.app.features.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.BuildConfig
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.PageColumn
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.FooterBar
import com.wunderhand.design.Lockup
import com.wunderhand.design.NoteCard
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHType
import com.wunderhand.network.ApiError
import kotlinx.coroutines.launch

/**
 * What is typed, held where a rotation cannot lose it and the disk never
 * sees it: a password is not something to put in saved instance state.
 */
class SignInForm : ViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var isSigningIn by mutableStateOf(false)
        private set

    fun signIn(model: AppModel) {
        if (isSigningIn) return
        if (email.isBlank() || password.isEmpty()) {
            error = "Both an email and a password, please."
            return
        }
        isSigningIn = true
        viewModelScope.launch {
            try {
                model.signIn(email, password)
                /* This form outlives the session it started — it belongs to the
                 * activity, not to the screen. So it forgets what was typed the
                 * moment it has worked: a password is not left in memory, and the
                 * next person to see this screen does not find the last one's. */
                email = ""
                password = ""
                error = null
            } catch (refusal: ApiError) {
                error = refusal.message
                password = ""
            } finally {
                isSigningIn = false
            }
        }
    }
}

/**
 * Sign in, as the web's `/sign-in` page: the lockup, "Sign in", email and
 * password, and the action pinned where a thumb rests.
 *
 * No "Set your shop up" link. Shops sign up and choose a plan on the web;
 * the app is where an existing shop runs its day.
 */
@Composable
fun SignInScreen(model: AppModel, form: SignInForm = viewModel()) {
    val focus = LocalFocusManager.current
    val links = LocalUriHandler.current
    val server by model.server.collectAsStateWithLifecycle()
    val passwordField = remember { FocusRequester() }

    fun submit() {
        focus.clearFocus()
        form.signIn(model)
    }

    Column(Modifier.fillMaxSize()) {
        PageColumn(Modifier.weight(1f)) {
            Column {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Lockup()
                    ScreenHeader("Sign in", style = WHType.PageTitle)
                }

                AnimatedVisibility(form.error != null) {
                    NoteCard(form.error.orEmpty(), Modifier.padding(top = 20.dp).testTag("signInError"))
                }

                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WHField(
                        "Email", form.email, { form.email = it },
                        inputModifier = Modifier.testTag("email").semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { passwordField.requestFocus() }),
                    )
                    WHField(
                        "Password", form.password, { form.password = it },
                        inputModifier = Modifier.testTag("password").focusRequester(passwordField).semantics { contentType = ContentType.Password },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                Box(
                    Modifier.padding(top = 12.dp).heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { links.openUri("${server.trimEnd('/')}/forgot") }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Forgotten your password?", style = WHType.Link, color = WHColors.Ink, textDecoration = TextDecoration.Underline)
                }

                if (BuildConfig.DEBUG) DevServerRow(model, server, Modifier.padding(top = 20.dp).padding(horizontal = 4.dp))
            }
        }

        FooterBar {
            // The 430dp column less the footer's gutters, so on a tablet the
            // button lines up with the fields above it.
            PrimaryButton("Sign in", ::submit, Modifier.widthIn(max = 394.dp).testTag("signIn"), loading = form.isSigningIn)
        }
    }
}

/** Debug builds only: which dev server this build is talking to, and a way to
 *  change it for a phone that cannot reach the emulator's `10.0.2.2`. */
@Composable
private fun DevServerRow(model: AppModel, server: String, modifier: Modifier = Modifier) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Development server")
        if (isEditing) {
            WHField("Server", draft, { draft = it }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("Use this server") { scope.launch { model.useServer(draft); isEditing = false } }
                TextAction("Cancel") { isEditing = false }
            }
        } else {
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { draft = server; isEditing = true },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(server, style = WHType.Meta, color = WHColors.Muted, textDecoration = TextDecoration.Underline)
            }
        }
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = WHType.BarAction, color = WHColors.Ink)
    }
}
