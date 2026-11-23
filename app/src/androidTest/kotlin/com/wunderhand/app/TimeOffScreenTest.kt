package com.wunderhand.app

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wunderhand.app.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Time off, for real: a day taken in Shop, seen in the diary, and taken back —
 * against chairtime's dev server on this Mac (`next dev -p 3100`, reached from
 * the emulator as 10.0.2.2) and its seeded shop.
 *
 * ```
 * ./gradlew :app:connectedMinifiedAndroidTest -Pminified \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.wunderhand.app.TimeOffScreenTest
 * ```
 *
 * One test, for the reason [DiaryTest] has one: each would sign in again, and
 * chairtime limits how often anybody may. It takes tomorrow off for whoever
 * signs in and gives it back, so the shop ends as it began; if it stops half
 * way, the day it left is the one whose note is [note], and the run after
 * that is skipped rather than made to guess whose it is.
 *
 * `-Pandroid.testInstrumentationRunnerArguments.shots=1` keeps pictures of the
 * list, the warning and the diary in the emulator's Download folder
 * (`adb pull /sdcard/Download/wh-time-off-list.png`).
 */
@RunWith(AndroidJUnit4::class)
class TimeOffScreenTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val email = args.getString("email") ?: "kit@fold.example"
    private val password = args.getString("password") ?: "chairtime-demo-1"
    private val shots = args.getString("shots") != null

    private val note = "ZZ App time off Android"
    /** The form's own first day: tomorrow, where the seeded shop is. */
    private val tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1).toString()

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun isOnScreen(tag: String) = app.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = app.waitUntil(20_000) { isOnScreen(tag) }
    private fun says(text: String) = app.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    private fun SemanticsNode.described() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.map { it.words() }).joinToString(" ")

    /** The lines of whole days in the list, by their tags. */
    private fun days() = app.onAllNodes(tagStartsWith("timeOff-series:")).fetchSemanticsNodes()
    private fun ours() = days().firstOrNull { note in it.words() }

    /** Blocked time in the day on screen: a hatched line in the list, a hatched block on either grid. */
    private fun blocked() = (app.onAllNodes(tagStartsWith("block-")).fetchSemanticsNodes() + app.onAllNodes(tagStartsWith("break-")).fetchSemanticsNodes())
        .firstOrNull { node -> node.described().let { note in it && "All day" in it } }

    /** Pressed, a day off is asked about as a day, not as an hour — and here it is left as it is. */
    private fun asksBeforeTakingItBack(press: () -> Unit, picture: String) {
        press()
        app.waitUntil(10_000) { says("Take this day back?") }
        app.onNodeWithTag("confirm").assertTextContains("Take it back")
        shot(picture)
        app.onNodeWithTag("keep").performClick()
        app.waitUntil(10_000) { !says("Take this day back?") }
        assertTrue("the day is still off", blocked() != null)
    }

    /** A picture of the screen, kept on the emulator. Only when asked for, and never a reason to fail. */
    private fun shot(name: String) {
        if (!shots) return
        app.waitForIdle()
        runCatching {
            val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/wh-time-off-$name.png")
            // Read to its end: the command is only done once its output closes.
            ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        }
    }

    private fun openTimeOff() {
        app.onNodeWithTag("tab-Shop").performClick()
        // Where the tab was left: the index, or — on a phone, coming back — the page itself.
        app.waitUntil(20_000) { isOnScreen("shopTimeOff") || isOnScreen("timeOff-heading") }
        if (!isOnScreen("timeOff-heading")) app.onNodeWithTag("shopTimeOff").performScrollTo().performClick()
        waitFor("timeOff-heading")
        waitFor("timeOffAdd")
    }

    /** Where it stopped, when it stops: a timeout says which line gave up and nothing about what was on screen. */
    @Test fun takesADayOffSeesItInTheDiaryAndTakesItBack() {
        try { takesItSeesItAndTakesItBack() } catch (stopped: Throwable) { shot("stopped"); throw stopped }
    }

    private fun takesItSeesItAndTakesItBack() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.activity.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        app.waitUntil(20_000) { isOnScreen("signIn") || isOnScreen("diaryHeading") }
        if (isOnScreen("signIn")) {
            app.onNodeWithTag("email").performTextInput(email)
            app.onNodeWithTag("password").performTextInput(password)
            app.onNodeWithTag("signIn").performClick()
        }
        waitFor("diaryHeading")

        // Shop → Time off: a row for everybody, after Working hours.
        openTimeOff()
        assumeTrue("a run that stopped half way left its day behind: remove the time off called \"$note\" first", ours() == null)
        val before = days().map { it.tag() }.toSet()
        val noneBefore = isOnScreen("timeOffNone")
        everyControlSaysWhatItIs("time off")

        // The form as it opens: tomorrow, one day, whoever is signed in. Only what it is for is typed.
        app.onNodeWithTag("timeOffAdd").performScrollTo().performClick()
        waitFor("timeOffForm")
        everyControlSaysWhatItIs("the time off form")
        app.onNodeWithTag("field-timeOffNote").performScrollTo().performTextInput(note)
        // Done on the keyboard puts it away, so the button is pressed where it has come to rest.
        app.onNodeWithTag("field-timeOffNote").performImeAction()
        app.waitForIdle()
        app.onNodeWithTag("timeOffSave").performScrollTo().assertTextContains("Save time off")
        app.onNodeWithTag("timeOffSave").performClick()

        // Tomorrow is usually the seeded day, so somebody is booked in it: said first, with nothing saved.
        // A day nobody is booked in is saved at once.
        app.waitUntil(30_000) { isOnScreen("timeOffInTheWay") || isOnScreen("timeOffNotice") || isOnScreen("timeOffProblem") }
        assertTrue("chairtime refused the day", !isOnScreen("timeOffProblem"))
        val bookedOver = isOnScreen("timeOffInTheWay")
        if (bookedOver) {
            // It arrives above the button that was pressed, and brings itself into view: nothing here scrolls to it.
            app.waitForIdle()
            app.onNodeWithText("in these days already", substring = true).assertIsDisplayed()
            shot("warning")
            assertTrue("the warning says nothing is saved", says("Nothing has been saved yet."))
            assertTrue("each booking in the way is named", isOnScreen("timeOffBooking"))
            everyControlSaysWhatItIs("the warning")
            app.onNodeWithTag("timeOffSave").performScrollTo().assertTextContains("Take the time off anyway")
            app.onNodeWithTag("timeOffSave").performClick()
        }
        app.waitUntil(30_000) { isOnScreen("timeOffNotice") && ours() != null }
        app.onNodeWithTag("timeOffNotice").assertTextContains("Saved.")
        assertTrue("the form closes once it is saved", !isOnScreen("timeOffForm"))

        // The day is in the list, with what it is for — and what is still booked in it, because nothing else will say.
        val line = checkNotNull(ours()).tag()
        assertEquals("one line more than there was", before + line, days().map { it.tag() }.toSet())
        if (bookedOver) {
            app.onNode(hasTestTag("timeOffInside") and hasAnyAncestor(hasTestTag(line))).performScrollTo().assertTextContains("still in these days", substring = true)
        }
        app.onNodeWithTag(line).performScrollTo()
        shot("list")

        // The diary, tomorrow: the day is there, hatched, and says it is the whole of it.
        app.onNodeWithTag("tab-Diary").performClick()
        waitFor("diaryHeading")
        app.onNodeWithText("Week").performClick()
        waitFor("weekList")
        if (!isOnScreen("week-$tomorrow")) {
            // A Sunday: tomorrow is the first day of the week after this one.
            if (isOnScreen("nextWeek")) app.onNodeWithTag("nextWeek").performScrollTo().performClick()
            else app.onNodeWithContentDescription("Next week").performClick()
            waitFor("week-$tomorrow")
        }
        app.onNodeWithTag("week-$tomorrow").performScrollTo().performClick()
        waitFor("showing-$tomorrow")
        app.waitUntil(20_000) { blocked() != null }
        val block = checkNotNull(blocked()).tag()
        assertTrue("a list on a phone, the team's grid on anything wider", isOnScreen("agenda") || isOnScreen("teamGrid") || isOnScreen("dayGrid"))
        runCatching { app.onNodeWithTag(block).performScrollTo() }
        shot("diary")

        if (block.startsWith("block-")) {
            // A phone: the day is a list, and the line is pressed where a thumb would press it.
            asksBeforeTakingItBack({ app.onNodeWithTag(block).performClick() }, "diary-question")
        } else {
            // By its action, not a touch at its middle: on a grid the bookings still in the day are drawn over it.
            asksBeforeTakingItBack({ app.onNodeWithTag(block).performSemanticsAction(SemanticsActions.OnClick) }, "diary-question")
            if (isOnScreen("teamGrid")) {
                // Anything wider has the list as well, behind "List": the same day, as a line at the top of it.
                // The header scrolls with the day on a screen this size, and finding the block may have scrolled it away.
                app.onNodeWithText("List").performScrollTo().performClick()
                app.waitUntil(20_000) { isOnScreen("agenda") && blocked()?.tag()?.startsWith("block-") == true }
                val listed = checkNotNull(blocked()).tag()
                runCatching { app.onNodeWithTag(listed).performScrollTo() }
                shot("diary-list")
                asksBeforeTakingItBack({ app.onNodeWithTag(listed).performClick() }, "diary-list-question")
                // As it was found.
                app.onNodeWithText("Day").performScrollTo().performClick()
                waitFor("teamGrid")
            }
        }

        // Taken back where it was taken: the line goes, and the list is as it was found.
        openTimeOff()
        app.waitUntil(20_000) { ours() != null }
        app.onNode(hasTestTag("timeOffRemove") and hasAnyAncestor(hasTestTag(line))).performScrollTo().performClick()
        app.onNodeWithTag("confirm").assertTextContains("Remove this day")
        app.onNodeWithTag("confirm").performClick()
        app.waitUntil(30_000) { ours() == null && isOnScreen("timeOffNotice") }
        app.onNodeWithTag("timeOffNotice").assertTextContains("Removed.")
        assertEquals("the days off there were before", before, days().map { it.tag() }.toSet())
        if (noneBefore) app.onNodeWithTag("timeOffNone").assertTextContains("No days off coming up.")
        shot("list-after")

        signOut()
    }

    private fun signOut() {
        app.onNodeWithTag("tab-Shop").performClick()
        if (!isOnScreen("signOut") && isOnScreen("timeOff-close")) app.onNodeWithTag("timeOff-close").performScrollTo().performClick()
        waitFor("signOut")
        app.onNodeWithTag("signOut").performScrollTo().performClick()
        app.onNodeWithTag("confirm").performClick()
        waitFor("signIn")
    }

    /** As [DiaryTest] asks of every screen: anything that can be pressed has words a screen reader can say. */
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
