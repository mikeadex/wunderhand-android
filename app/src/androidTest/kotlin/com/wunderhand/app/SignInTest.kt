package com.wunderhand.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wunderhand.app.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing in, for real: the debug build against chairtime's dev server on
 * this Mac (`next dev` on port 3100, reached from the emulator as 10.0.2.2)
 * and its seeded shop.
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * One test, not several, because each one would sign in again and chairtime
 * limits how often anybody may — the same reason the iOS tests share a session.
 * Another login: `-Pandroid.testInstrumentationRunnerArguments.email=…` and `.password=…`.
 */
@RunWith(AndroidJUnit4::class)
class SignInTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val email = args.getString("email") ?: "kit@fold.example"
    private val password = args.getString("password") ?: "chairtime-demo-1"

    private fun isOnScreen(tag: String) = app.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = app.waitUntil(15_000) { isOnScreen(tag) }

    @Test fun signsInStaysSignedInAndSignsOut() {
        // Whatever the last run left behind, start from the sign-in screen.
        app.waitUntil(15_000) { isOnScreen("signIn") || isOnScreen("signOut") }
        if (isOnScreen("signOut")) {
            app.onNodeWithTag("signOut").performScrollTo().performClick()
            waitFor("signIn")
        }

        // Nothing typed: said at once, and nothing is sent.
        app.onNodeWithTag("signIn").performClick()
        app.onNodeWithTag("signInError").assertTextContains("Both an email and a password, please.")

        app.onNodeWithTag("email").performTextInput(email)
        app.onNodeWithTag("password").performTextInput(password)
        app.onNodeWithTag("signIn").performClick()

        waitFor("todayAt")
        app.onNodeWithText("Today at Fold Barbers").assertIsDisplayed()
        app.onNodeWithText(email).assertIsDisplayed()

        // A rotation, a fold, a language change: the activity is rebuilt, the session is not.
        app.activityRule.scenario.recreate()
        waitFor("todayAt")

        app.onNodeWithTag("signOut").performScrollTo().performClick()
        waitFor("signIn")
    }
}
