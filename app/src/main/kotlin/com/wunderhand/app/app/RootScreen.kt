package com.wunderhand.app.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wunderhand.app.BuildConfig
import com.wunderhand.app.features.auth.ShopPickerScreen
import com.wunderhand.app.features.auth.SignInScreen
import com.wunderhand.app.features.shell.MainScaffold
import com.wunderhand.design.SignalBar
import com.wunderhand.design.WHColors
import kotlinx.coroutines.launch

/**
 * Picks the screen from the session's phase. Nothing below this decides
 * whether anybody is signed in.
 *
 * Trouble that may pass — no signal, chairtime mid-deploy — is a bar across
 * the top rather than a screen of its own: whatever was loaded is still the
 * best guide a shop has until the next answer arrives.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RootScreen(model: AppModel, reachability: Reachability) {
    val phase by model.phase.collectAsStateWithLifecycle()
    val trouble by model.trouble.collectAsStateWithLifecycle()
    val cameBack by reachability.cameBack.collectAsStateWithLifecycle()
    val aScreenIsSayingIt by model.aScreenIsSayingIt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var isTrying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (model.phase.value == Phase.Launching) model.start(allowServerOverride = BuildConfig.DEBUG)
    }
    // Back on the network: ask again without anybody pressing anything.
    LaunchedEffect(cameBack) {
        if (cameBack > 0 && model.trouble.value != null) model.tryAgain()
    }

    CompositionLocalProvider(LocalReachability provides reachability) {
    // The top and the sides are the root's. The bottom is each screen's: the
    // tab bar draws under the gesture bar, and a form pads for the keyboard.
    // Test tags double as resource ids, so UI Automator and accessibility tools can find a control by name.
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(WHColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        AnimatedVisibility(trouble != null && !aScreenIsSayingIt, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SignalBar(
                message = trouble.orEmpty(),
                modifier = Modifier.testTag("signalBar"),
                isTrying = isTrying,
                onRetry = {
                    isTrying = true
                    scope.launch { model.tryAgain(); isTrying = false }
                },
            )
        }

        // Keyed on the kind of phase, not its contents: a refreshed `Me` is the
        // same screen with newer words, not a new screen to fade to.
        AnimatedContent(phase, contentKey = { it::class }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { now ->
            val bottom = if (now is Phase.SignedIn) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            Box(Modifier.fillMaxSize().then(bottom)) {
                when (now) {
                    Phase.Launching -> LaunchScreen()
                    Phase.SignedOut -> SignInScreen(model)
                    is Phase.ChoosingShop -> ShopPickerScreen(model, now.me)
                    is Phase.SignedIn -> MainScaffold(model, now.me)
                    is Phase.Unreachable -> MessageScreen("Could not reach Wunderhand", now.message, "Try again") {
                        scope.launch { model.tryAgain() }
                    }
                    is Phase.UpgradeRequired -> MessageScreen("Time to update", now.message, null) {}
                }
            }
        }
    }
    }
}
