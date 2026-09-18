package com.wunderhand.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wunderhand.app.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app, for real: the debug build against chairtime's dev server on this
 * Mac (`next dev` on port 3100, reached from the emulator as 10.0.2.2) and its
 * seeded shop.
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * One test, not several, because each one would sign in again and chairtime
 * limits how often anybody may — the same reason the iOS tests share a
 * session. It asserts shapes, never the seed's numbers: the seeded shop grows.
 * Another login: `-Pandroid.testInstrumentationRunnerArguments.email=…` and `.password=…`.
 */
@RunWith(AndroidJUnit4::class)
class DiaryTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val email = args.getString("email") ?: "kit@fold.example"
    private val password = args.getString("password") ?: "chairtime-demo-1"

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun isOnScreen(tag: String) = app.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun anyOnScreen(prefix: String) = app.onAllNodes(tagStartsWith(prefix)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = app.waitUntil(20_000) { isOnScreen(tag) }

    @Test fun signsInReadsTheDiaryAndSignsOut() {
        // Whatever the last run left behind, start from the sign-in screen.
        app.waitUntil(20_000) { isOnScreen("signIn") || isOnScreen("diaryHeading") }
        if (isOnScreen("diaryHeading")) signOut()

        // Nothing typed: said at once, and nothing is sent.
        app.onNodeWithTag("signIn").performClick()
        app.onNodeWithTag("signInError").assertTextContains("Both an email and a password, please.")

        app.onNodeWithTag("email").performTextInput(email)
        app.onNodeWithTag("password").performTextInput(password)
        app.onNodeWithTag("signIn").performClick()

        // The diary, on today, with the five tabs the web has.
        waitFor("diaryHeading")
        app.onNodeWithTag("diaryHeading").assertTextContains("Today")
        for (tab in listOf("Diary", "Clients", "Menu", "Money", "Shop")) app.onNodeWithTag("tab-$tab").assertIsDisplayed()
        everyControlSaysWhatItIs("the diary")

        // The week: seven days, and the totals over them.
        app.onNodeWithText("Week").performClick()
        waitFor("weekList")
        // A tile is read out whole — "Booked, 32" — rather than as two fragments.
        app.onNodeWithContentDescription("Booked, ", substring = true).assertExists()
        everyControlSaysWhatItIs("the week")

        // Find a day with somebody in it, wherever the seed has put them this week.
        val days = app.onAllNodes(tagStartsWith("week-")).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertTrue("an open day in the week", days.isNotEmpty())
        val busy = days.firstOrNull { day ->
            app.onNodeWithTag(day).performScrollTo().performClick()
            app.waitUntil(20_000) { isOnScreen("agenda") || isOnScreen("teamGrid") || isOnScreen("emptyDay") || isOnScreen("closedDay") }
            runCatching { app.waitUntil(3_000) { anyOnScreen("appointment-") } }.isSuccess.also { found ->
                if (!found) { app.onNodeWithText("Week").performClick(); waitFor("weekList") }
            }
        }
        assertTrue("a booking somewhere in the seeded week", busy != null)
        everyControlSaysWhatItIs("a busy day")

        // Open it: who, when, and — for an owner — what to take.
        app.onAllNodes(tagStartsWith("appointment-")).onFirst().performScrollTo().performClick()
        waitFor("appointmentName")
        app.onNodeWithTag("appointmentStatus").assertExists()
        app.onNodeWithTag("toTake").assertExists()
        everyControlSaysWhatItIs("an open appointment")

        // A rotation, a fold: the activity is rebuilt; the session, the day and the open appointment are not.
        app.activityRule.scenario.recreate()
        waitFor("appointmentName")
        app.onNodeWithContentDescription("Close appointment").performClick()
        app.waitUntil(10_000) { !isOnScreen("appointmentName") }

        signOut()
    }

    private fun signOut() {
        app.onNodeWithTag("tab-Shop").performClick()
        waitFor("signOut")
        app.onNodeWithTag("signOut").performScrollTo().performClick()
        waitFor("signIn")
    }

    /**
     * Anything that can be pressed has words a screen reader can say — its
     * own, or a description. A control TalkBack announces as "button" and
     * nothing else is a control a blind barber cannot use.
     */
    private fun everyControlSaysWhatItIs(where: String) {
        val silent = app.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            val described = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()
            val typed = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            text.isBlank() && described.isBlank() && typed.isBlank()
        }
        assertTrue("on $where, ${silent.size} control(s) say nothing: " + silent.joinToString { it.config.getOrNull(SemanticsProperties.TestTag) ?: "untagged at ${it.boundsInRoot}" }, silent.isEmpty())
    }
}
