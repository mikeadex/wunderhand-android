package com.wunderhand.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
            waitFor("showing-" + day.removePrefix("week-"))
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

        // Underneath it, what can be done now — or a plain line about why nothing can.
        assertTrue("the sheet's footer", isOnScreen("markDone") || isOnScreen("opensLater") || isOnScreen("closedLine"))
        if (isOnScreen("reschedule")) {
            // Where it could move to. Looked at, not used: the seed stays as it was found.
            app.onNodeWithTag("reschedule").performClick()
            waitFor("moveScreen")
            app.waitUntil(20_000) { isOnScreen("slot") || isOnScreen("laterDays") || isOnScreen("slotsProblem") }
            everyControlSaysWhatItIs("the move screen")
            app.onNodeWithTag("backToAppointment").performClick()
            waitFor("appointmentName")
        }

        // A rotation, a fold: the activity is rebuilt; the session, the day and the open appointment are not.
        app.activityRule.scenario.recreate()
        waitFor("appointmentName")
        app.onNodeWithContentDescription("Close appointment").performClick()
        app.waitUntil(10_000) { !isOnScreen("appointmentName") }

        blocksTimeAndTakesItBack()
        booksAWalkInAndCancelsIt()
        looksSomebodyUpAndAddsAndRemovesAClient()
        putsAServiceOnTheMenuAndTakesItOff()
        waitsFillsAndCounts()
        walksTheShop()
        signOut()
    }

    /**
     * The Clients tab: the list, a search that finds nobody, a profile, its
     * medical notes behind the phone's lock — an emulator has none, and says
     * so — and a client added and removed again, so the list ends as it began.
     */
    private fun looksSomebodyUpAndAddsAndRemovesAClient() {
        app.onNodeWithTag("tab-Clients").performClick()
        app.waitUntil(20_000) { anyOnScreen("clientRow") }
        app.onNodeWithTag("clientsHeading").assertTextContains("Clients")
        everyControlSaysWhatItIs("the client list")

        // Nobody is called this. The empty list says so in words, not with a blank.
        app.onNodeWithTag("clientSearch").performTextInput("zzqx-nobody")
        waitFor("noClients")
        app.onNodeWithText("Nobody by that name").assertExists()
        app.onNodeWithContentDescription("Clear the search").performClick()
        app.waitUntil(20_000) { anyOnScreen("clientRow") }

        app.onAllNodesWithTag("clientRow").onFirst().performClick()
        waitFor("clientName")
        app.onNodeWithTag("clientStats").assertTextContains("visit", substring = true)
        everyControlSaysWhatItIs("a client's profile")

        // Medical notes: asked for, never part of the profile.
        app.onNodeWithTag("medicalNotes").performScrollTo().performClick()
        waitFor("healthHeading")
        app.waitUntil(20_000) { isOnScreen("notesLocked") || isOnScreen("healthAccessLog") }
        if (isOnScreen("healthAccessLog")) assertTrue("a phone with no lock says it cannot lock them", isOnScreen("notesUnprotected"))
        everyControlSaysWhatItIs("medical notes")
        app.onNodeWithTag("backFromHealth").performClick()
        waitFor("clientName")
        if (isOnScreen("backToClients")) app.onNodeWithTag("backToClients").performClick()

        // Somebody new, then gone again.
        val name = "Zz Android Test ${System.currentTimeMillis() % 100_000}"
        app.onNodeWithTag("addClient").performClick()
        waitFor("clientNameField")
        everyControlSaysWhatItIs("the client form")
        app.onNodeWithTag("clientNameField").performTextInput(name)
        app.onNodeWithTag("saveClient").performClick()
        app.waitUntil(20_000) { app.onAllNodesWithTag("clientName").fetchSemanticsNodes().any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name } } }
        app.onNodeWithTag("clientStatus").assertExists()

        app.onAllNodesWithTag("editClient").onFirst().performClick()
        waitFor("removeClient")
        app.onNodeWithTag("removeClient").performScrollTo().performClick()
        app.onNodeWithTag("confirm").performClick()
        app.waitUntil(20_000) { !isOnScreen("clientForm") && app.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * The Menu tab: the list, a service's page, and a service made from
     * nothing — named and priced, its time broken into steps with a gap in the
     * middle, somebody to do it, an extra made for it and retired — then
     * archived, so the menu ends as it began.
     */
    private fun putsAServiceOnTheMenuAndTakesItOff() {
        app.onNodeWithTag("tab-Menu").performClick()
        app.waitUntil(20_000) { anyOnScreen("menuService") }
        app.onNodeWithTag("menuCount").assertTextContains("SERVICE", substring = true)
        everyControlSaysWhatItIs("the menu")

        app.onAllNodesWithTag("menuService").onFirst().performClick()
        waitFor("serviceSummary")
        app.onNodeWithTag("serviceSummary").assertTextContains("booked", substring = true)
        everyControlSaysWhatItIs("a service's page")
        if (isOnScreen("serviceBack")) app.onNodeWithTag("serviceBack").performClick()

        // A form with no name is told so before chairtime hears of it.
        val name = "Zz Android Test ${System.currentTimeMillis() % 100_000}"
        app.onNodeWithTag("addService").performClick()
        waitFor("serviceNameField")
        everyControlSaysWhatItIs("the service form")
        app.onNodeWithTag("saveService").performClick()
        app.onNodeWithTag("serviceFormProblem").assertTextContains("A name is needed")
        app.onNodeWithTag("serviceNameField").performTextInput(name)
        app.onNodeWithTag("servicePriceField").performScrollTo().performTextInput("12.50")
        app.onNodeWithTag("servicePriceField").performImeAction()
        app.waitForIdle()
        app.onNodeWithTag("saveService").performClick()

        // Straight to its own page, which says the one thing wrong with it.
        app.waitUntil(20_000) { app.onAllNodesWithTag("serviceHeading").fetchSemanticsNodes().any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name } } }
        app.onNodeWithTag("serviceSummary").assertTextContains("£12.50", substring = true)
        app.onNodeWithTag("nobodyAssigned").assertExists()

        // Twenty on, thirty five off: the middle is somebody else's to book.
        app.onNodeWithTag("changeSteps").performScrollTo().performClick()
        waitFor("saveSteps")
        everyControlSaysWhatItIs("the steps")
        app.onNodeWithTag("stepMinutes-0").performTextReplacement("20")
        // The keyboard away first, so the button is pressed where it has come to rest.
        app.onNodeWithTag("stepMinutes-0").performImeAction()
        app.waitForIdle()
        app.onNodeWithTag("addStep").performScrollTo().performClick()
        waitFor("stepLabel-1")
        app.onNodeWithTag("stepLabel-1").performScrollTo().performTextInput("Develop")
        app.onNodeWithTag("stepMinutes-1").performTextReplacement("35")
        app.onNodeWithTag("stepMinutes-1").performImeAction()
        app.waitForIdle()
        app.onNodeWithTag("stepBusy-1").performScrollTo().performClick()
        app.onNodeWithTag("stepsSummary").performScrollTo().assertExists()
        app.onNodeWithText("35m is sellable.").assertExists()
        app.onNodeWithTag("saveSteps").performClick()
        app.waitUntil(20_000) { !isOnScreen("saveSteps") }
        app.onNodeWithTag("serviceSummary").assertTextContains("55m", substring = true)

        // Somebody to do it, at the standard price.
        app.onNodeWithTag("choosePerformers").performScrollTo().performClick()
        waitFor("performer-0")
        everyControlSaysWhatItIs("who does it")
        app.onNodeWithTag("performer-0").performClick()
        app.onNodeWithTag("savePerformers").performClick()
        app.waitUntil(20_000) { !isOnScreen("savePerformers") }
        app.waitUntil(10_000) { !isOnScreen("nobodyAssigned") && isOnScreen("performer") }

        // An extra made for it, then retired: it is the shop's, so it would otherwise outlive the service.
        app.onNodeWithTag("changeExtras").performScrollTo().performClick()
        waitFor("addExtra")
        app.onNodeWithTag("addExtra").performScrollTo().performClick()
        waitFor("extraName")
        everyControlSaysWhatItIs("the extra form")
        val extra = "$name extra"
        app.onNodeWithTag("extraName").performTextInput(extra)
        app.onNodeWithTag("extraPrice").performTextInput("3")
        app.onNodeWithTag("extraPrice").performImeAction()
        app.waitForIdle()
        app.onNodeWithTag("saveExtra").performClick()
        app.waitUntil(20_000) { isOnScreen("addExtra") && app.onAllNodesWithContentDescription("Change $extra").fetchSemanticsNodes().isNotEmpty() }
        everyControlSaysWhatItIs("the extras")
        app.onNodeWithContentDescription("Change $extra").performScrollTo().performClick()
        waitFor("retireExtra")
        app.onNodeWithTag("retireExtra").performScrollTo().performClick()
        app.onNodeWithTag("confirm").performClick()
        app.waitUntil(20_000) { isOnScreen("addExtra") && app.onAllNodesWithContentDescription("Change $extra").fetchSemanticsNodes().isEmpty() }
        app.onNodeWithTag("extras-close").performScrollTo().performClick()
        app.waitUntil(10_000) { !isOnScreen("addExtra") }

        // And off the menu again.
        app.onNodeWithTag("editService").performClick()
        waitFor("archiveService")
        app.onNodeWithTag("archiveService").performScrollTo().performClick()
        app.onNodeWithTag("confirm").performClick()
        app.waitUntil(20_000) { !isOnScreen("serviceForm") && anyOnScreen("menuService") && app.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * The whole of a new booking — service, person, extras if there are any,
     * a time, "Book" — then the appointment it opens on is cancelled, so the
     * seed ends as it began. A walk-in, so there is no deposit to refund and
     * nobody to email.
     */
    private fun booksAWalkInAndCancelsIt() {
        runCatching { app.onNodeWithTag("newBooking").performScrollTo() }
        app.onNodeWithTag("newBooking").performClick()
        app.waitUntil(20_000) { anyOnScreen("bookingService") }
        app.onNodeWithTag("bookingTitle").assertTextContains("Pick a service")
        everyControlSaysWhatItIs("the menu step")
        app.onAllNodesWithTag("bookingService").onFirst().performClick()

        app.waitUntil(20_000) { anyOnScreen("bookingPerson") }
        everyControlSaysWhatItIs("the person step")
        app.onAllNodesWithTag("bookingPerson").onFirst().performClick()

        // Extras are a step only for a service that has some.
        app.waitUntil(20_000) { isOnScreen("bookingContinue") || anyOnScreen("bookingSlot") || isOnScreen("bookingLaterDays") }
        if (isOnScreen("bookingContinue")) {
            everyControlSaysWhatItIs("the extras step")
            app.onNodeWithTag("bookingContinue").performClick()
        }

        app.waitUntil(20_000) { anyOnScreen("bookingSlot") }
        everyControlSaysWhatItIs("the time step")
        app.onAllNodesWithTag("bookingSlot").onFirst().performScrollTo().performClick()
        waitFor("bookButton")
        app.onNodeWithTag("bookButton").assertTextContains("Book ", substring = true)
        app.onNodeWithTag("bookButton").performClick()

        // The flow goes; the diary moves to its day; the new appointment opens.
        waitFor("appointmentName")
        app.onNodeWithTag("appointmentName").assertTextContains("Walk-in")
        waitFor("cancelAppointment")

        // The soonest time is often within the half hour, and then the till is open. Looked at, typed into, and
        // left unpaid: a bill rung through cannot be un-rung, and this appointment is about to be cancelled.
        if (isOnScreen("checkOut")) {
            app.onNodeWithTag("checkOut").performClick()
            waitFor("tillDue")
            val before = app.onNodeWithTag("tillDue").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            everyControlSaysWhatItIs("the till")
            app.onNodeWithTag("till-tip").performScrollTo().performTextInput("2.50")
            app.onNodeWithTag("till-tip").performImeAction()
            app.waitForIdle()
            val after = app.onNodeWithTag("tillDue").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            assertTrue("what to ask for moves as a tip is typed: $before, then $after", before != after && after.startsWith("To pay, tip included"))
            app.onNodeWithTag("markPaid").assertExists()
            app.onNodeWithTag("till-close").performScrollTo().performClick()
            app.waitUntil(10_000) { !isOnScreen("tillDue") }
        }
        app.onNodeWithTag("cancelAppointment").performClick()
        app.onNodeWithTag("confirm").performClick()
        waitFor("closedLine")
        app.onNodeWithTag("sheetNotice").assertTextContains("Cancelled", substring = true)
        app.onNodeWithContentDescription("Close appointment").performClick()
        app.waitUntil(10_000) { !isOnScreen("appointmentName") }
    }

    /**
     * Block half an hour, see it in the day, remove it: the day ends as it began.
     *
     * On a day six weeks out with nobody booked, because chairtime refuses a
     * block over something already there — and the seed's busy days are busy
     * at lunchtime.
     */
    private fun blocksTimeAndTakesItBack() {
        app.onNodeWithText("Week").performClick()
        waitFor("weekList")
        repeat(6) {
            if (isOnScreen("nextWeek")) app.onNodeWithTag("nextWeek").performScrollTo().performClick()
            else app.onNodeWithContentDescription("Next week").performClick()
            app.waitForIdle()
        }
        fun quietDays() = app.onAllNodes(tagStartsWith("week-")).fetchSemanticsNodes()
            .filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { ", 0 booked" in it } }
            .map { it.config[SemanticsProperties.TestTag] }
        app.waitUntil(20_000) { quietDays().isNotEmpty() }

        // Breaks are drawn on a grid: the team's on a tablet, your own on a phone —
        // so on a phone it has to be a day the person signed in works.
        val day = quietDays().firstOrNull { tag ->
            app.onNodeWithTag(tag).performScrollTo().performClick()
            // Until the day asked for is the day on screen: the last one stays up, dimmed, while it loads.
            waitFor("showing-" + tag.removePrefix("week-"))
            // A day with nobody booked is still an agenda on a phone: everybody's free time is on it.
            app.waitUntil(20_000) { isOnScreen("teamGrid") || isOnScreen("agenda") || isOnScreen("emptyDay") || isOnScreen("closedDay") }
            if (isOnScreen("teamGrid")) return@firstOrNull true
            app.onNodeWithText("Grid").performClick()
            runCatching { app.waitUntil(3_000) { isOnScreen("dayGrid") } }.isSuccess.also { works ->
                if (!works) { app.onNodeWithText("Week").performClick(); waitFor("weekList") }
            }
        }
        assertTrue("a quiet working day six weeks out", day != null)

        fun breaks() = app.onAllNodes(tagStartsWith("break-")).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }.toSet()
        val before = breaks()

        // In the header on a tablet, where it scrolls with the day; pinned under it on a phone, where it does not.
        runCatching { app.onNodeWithTag("blockTime").performScrollTo() }
        app.onNodeWithTag("blockTime").performClick()
        waitFor("blockThisTime")
        everyControlSaysWhatItIs("the block-time sheet")
        app.onNodeWithTag("blockNote").performTextInput("Android test — safe to remove")
        // Done on the keyboard puts it away, so the button is pressed where it has come to rest.
        app.onNodeWithTag("blockNote").performImeAction()
        app.waitForIdle()
        app.onNodeWithTag("blockThisTime").performClick()

        app.waitUntil(30_000) { (breaks() - before).isNotEmpty() || isOnScreen("blockProblem") }
        if (isOnScreen("blockProblem")) {
            val said = app.onNodeWithTag("blockProblem").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            throw AssertionError("chairtime refused the block: $said")
        }
        val added = (breaks() - before).single()
        app.onNodeWithTag(added).performScrollTo().performClick()
        app.onNodeWithText("Remove it").performClick()
        app.waitUntil(30_000) { added !in breaks() }
        assertTrue("no refusal was shown", !isOnScreen("diaryActionProblem"))
    }

    /**
     * The waiting list, a gap, the till's way in, and the Money tab.
     *
     * Somebody is put on the list from their profile and taken off again, so
     * the list ends as it began. A gap is opened and who fits it is read — and
     * **not offered**: an offer on this server emails whoever is on the seeded
     * list. Money is read for its shape, never its figures: the shop grows.
     */
    private fun waitsFillsAndCounts() {
        // From a client's profile the form opens on them, with nothing to look up.
        app.onNodeWithTag("tab-Clients").performClick()
        app.waitUntil(20_000) { anyOnScreen("clientRow") }
        app.onAllNodesWithTag("clientRow").onFirst().performClick()
        waitFor("clientName")
        val who = app.onNodeWithTag("clientName").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        app.onNodeWithTag("addToWaitlist").performScrollTo().performClick()
        waitFor("waitlistClient")
        app.onNodeWithTag("waitlistClient").assertContentDescriptionEquals("Client: $who")
        app.waitUntil(20_000) { isOnScreen("waitlistPart-e") }
        everyControlSaysWhatItIs("the waitlist form")
        app.onNodeWithTag("waitlistPart-e").performScrollTo().performClick()
        // The button waits for what the form chooses between: there is nothing to add them for until a service is known.
        app.waitUntil(20_000) { app.onAllNodesWithTag("waitlistJoinSave").fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config } }
        app.onNodeWithTag("waitlistJoinSave").performClick()

        // Back on the list, which says who was added, and has them on it.
        app.waitUntil(20_000) { isOnScreen("waitlistNotice") || isOnScreen("waitlistJoinProblem") }
        if (isOnScreen("waitlistJoinProblem")) {
            val said = app.onNodeWithTag("waitlistJoinProblem").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            throw AssertionError("chairtime would not put $who on the list: $said")
        }
        app.onNodeWithTag("waitlistNotice").assertTextContains("$who is on the list.")
        app.waitUntil(20_000) { app.onAllNodesWithContentDescription("Take $who off the list").fetchSemanticsNodes().isNotEmpty() }
        everyControlSaysWhatItIs("the waiting list")
        app.onAllNodesWithContentDescription("Take $who off the list").onFirst().performScrollTo().performClick()
        app.onNodeWithTag("confirm").performClick()
        app.waitUntil(20_000) { app.onAllNodesWithContentDescription("Take $who off the list").fetchSemanticsNodes().isEmpty() }
        app.onNodeWithTag("waitlistNotice").assertTextContains("$who is off the list.")
        app.onNodeWithTag("waitlist-close").performScrollTo().performClick()
        app.waitUntil(10_000) { !isOnScreen("waitlistAdd") }
        if (isOnScreen("backToClients")) app.onNodeWithTag("backToClients").performClick()

        // A gap, if today has one with room for a finger: who fits it, best first. Read, and closed.
        app.onNodeWithTag("tab-Diary").performClick()
        waitFor("diaryHeading")
        if (isOnScreen("fillGap")) {
            app.onAllNodesWithTag("fillGap").onFirst().performScrollTo().performClick()
            waitFor("gap-heading")
            app.waitUntil(20_000) { anyOnScreen("gapCandidate") || isOnScreen("nobodyFits") || isOnScreen("gapProblem") }
            assertTrue("a gap says who fits it, or that nobody does", !isOnScreen("gapProblem"))
            everyControlSaysWhatItIs("a gap")
            app.onNodeWithTag("gap-close").performScrollTo().performClick()
            app.waitUntil(10_000) { !isOnScreen("gap-heading") }
        }

        // Money: whose it is, the month, and how it compares — or that there is nothing to compare yet.
        app.onNodeWithTag("tab-Money").performClick()
        waitFor("moneyMonth")
        app.onNodeWithTag("moneyMonth").assertTextContains("£", substring = true)
        app.onNodeWithTag("moneyComparison").assertExists()
        app.onNodeWithTag("moneyDeposits").performScrollTo().assertExists()
        everyControlSaysWhatItIs("the money")
    }

    /**
     * The Shop tab: the index and what each row says, a rule refused before it
     * is sent and then saved as it was, a week of hours, somebody on the team,
     * an outlet with its travel fees — and the two ways out, looked at and not
     * taken: this login and this shop are the ones a store's reviewer signs in to.
     */
    private fun walksTheShop() {
        app.onNodeWithTag("tab-Shop").performClick()
        waitFor("shopTeam")
        everyControlSaysWhatItIs("the shop")
        // What the shop is on, and nothing about what that costs: the app never names a price for Wunderhand itself.
        val plan = app.onNodeWithTag("shopPlan").performScrollTo().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        assertTrue("the plan row counts seats and outlets: $plan", "seat" in plan && "outlet" in plan)
        assertTrue("the plan row names no price: $plan", "£" !in plan && "month" !in plan.lowercase())

        /** Open a row; look; come back. Beside the index, on a tablet, there is nothing to come back from. */
        fun visit(row: String, page: String, look: () -> Unit) {
            app.onNodeWithTag(row).performScrollTo().performClick()
            waitFor("$page-heading")
            look()
            everyControlSaysWhatItIs(page)
            if (isOnScreen("$page-close")) { app.onNodeWithTag("$page-close").performScrollTo().performClick(); waitFor("shopTeam") }
        }

        visit("shopRules", "rules") {
            waitFor("field-noticeHours")
            val was = app.onNodeWithTag("field-noticeHours").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            app.onNodeWithTag("field-noticeHours").performScrollTo().performTextReplacement("soon")
            app.onNodeWithTag("field-noticeHours").performImeAction()
            app.waitForIdle()
            app.onNodeWithTag("rules-save").performClick()
            app.onNodeWithTag("rulesProblem").assertTextContains("Least notice must be a whole number")
            // Put back as it was found, and saved: the same six numbers, so the shop is as it was.
            app.onNodeWithTag("field-noticeHours").performScrollTo().performTextReplacement(was)
            app.onNodeWithTag("field-noticeHours").performImeAction()
            app.waitForIdle()
            app.onNodeWithTag("rules-save").performClick()
            app.waitUntil(20_000) { isOnScreen("savedLine") }
        }

        visit("shopHours", "hours") {
            app.waitUntil(20_000) { anyOnScreen("day-") }
            assertTrue("seven days", app.onAllNodes(tagStartsWith("day-")).fetchSemanticsNodes().size == 7)
        }

        visit("shopReminders", "reminders") { waitFor("reminder-3") }

        visit("shopTeam", "team") {
            app.waitUntil(20_000) { anyOnScreen("teamPerson") }
            // Somebody with no name is not somebody: said before chairtime hears of it.
            app.onNodeWithTag("team-save").performClick()
            waitFor("savePerson")
            everyControlSaysWhatItIs("the person form")
            app.onNodeWithTag("savePerson").performClick()
            app.onNodeWithTag("personFormProblem").assertTextContains("A name is needed")
            app.onNodeWithTag("personForm-close").performScrollTo().performClick()
            app.waitUntil(10_000) { !isOnScreen("savePerson") }

            app.onAllNodesWithTag("teamPerson").onFirst().performClick()
            waitFor("personStatus")
            everyControlSaysWhatItIs("somebody's page")
            app.onNodeWithTag("person-close").performScrollTo().performClick()
            app.waitUntil(20_000) { anyOnScreen("teamPerson") }
        }

        visit("shopOutlets", "outlets") {
            app.waitUntil(20_000) { anyOnScreen("outletCard") }
            // The list has no travel fees; the form is fetched with them.
            app.onAllNodesWithTag("outletCard").onFirst().performClick()
            waitFor("saveOutlet")
            everyControlSaysWhatItIs("the outlet form")
            app.onNodeWithTag("outletForm-close").performScrollTo().performClick()
            app.waitUntil(10_000) { !isOnScreen("saveOutlet") }
        }

        // The ways out. Read, and left alone: nothing is typed, so neither button can be pressed.
        visit("shopAccount", "account") {
            app.onNodeWithTag("accountPassword").performScrollTo().assertExists()
            app.onNodeWithTag("deleteLogin").assertIsNotEnabled()
        }
        visit("shopClose", "closeShop") {
            app.waitUntil(20_000) { isOnScreen("closeUpcoming") || isOnScreen("closureWaiting") || isOnScreen("shopClosed") }
            if (isOnScreen("closeShopButton")) app.onNodeWithTag("closeShopButton").assertIsNotEnabled()
        }
    }

    private fun signOut() {
        app.onNodeWithTag("tab-Shop").performClick()
        waitFor("signOut")
        app.onNodeWithTag("signOut").performScrollTo().performClick()
        // Asked about first: it is one slip of a thumb from the row above it.
        app.onNodeWithTag("confirm").performClick()
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
