package com.wunderhand.app

import com.wunderhand.app.features.shop.CalendarViewModel
import com.wunderhand.app.features.shop.ConsentFormViewModel
import com.wunderhand.app.features.shop.ConsentViewModel
import com.wunderhand.app.features.shop.OffersViewModel
import com.wunderhand.app.features.shop.QuietTimesViewModel
import com.wunderhand.app.features.shop.RulesViewModel
import com.wunderhand.app.features.shop.ShopRoute
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.BookingRules
import com.wunderhand.core.CalendarLinkResponse
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.ConsentDocument
import com.wunderhand.core.ConsentResponse
import com.wunderhand.core.ConsentWords
import com.wunderhand.core.OfferWords
import com.wunderhand.core.OfferWrite
import com.wunderhand.core.OffersResponse
import com.wunderhand.core.QuietTimesResponse
import com.wunderhand.core.QuietWords
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Shop screens that opened the web until 1.3: consent, quiet times, offers, the calendar link — and the cancel switch on the rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShopMoreTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))
    private val consent: ConsentResponse = fixture("consent")
    private val savedDocument: ConsentDocument = fixture("consent-document")
    private val quiet: QuietTimesResponse = fixture("quiet-times-busy")
    private val offers: OffersResponse = fixture("offers")
    private val started: OffersResponse = fixture("offers-started")
    private val madeLink: CalendarLinkResponse = fixture("calendar")
    private val rules: BookingRules = fixture("rules")
    private val handled = mutableListOf<ApiError>()

    private open inner class Shop : StubApi() {
        var lastOffer: OfferWrite? = null
        var lastRules: BookingRules? = null
        override suspend fun consent(): ConsentResponse { calls += "consent"; return consent }
        override suspend fun saveConsent(kind: String, body: String): ConsentDocument { calls += "saveConsent $kind"; return savedDocument.copy(kind = kind, body = body) }
        override suspend fun quietTimes(): QuietTimesResponse { calls += "quietTimes"; return quiet }
        override suspend fun acceptQuietTime(weekday: Int, part: String, percent: Int): QuietTimesResponse { calls += "accept $weekday $part $percent"; return quiet.copy(suggestions = quiet.suggestions.drop(1)) }
        override suspend fun showSaving(ruleId: String, show: Boolean): QuietTimesResponse { calls += "showSaving $show"; return quiet.copy(rules = quiet.rules.map { it.copy(showSaving = show) }) }
        override suspend fun stopQuietTime(ruleId: String): QuietTimesResponse { calls += "stop"; return quiet.copy(rules = emptyList()) }
        override suspend fun offers(): OffersResponse { calls += "offers"; return offers }
        override suspend fun startOffer(write: OfferWrite): OffersResponse { calls += "startOffer"; lastOffer = write; return started }
        override suspend fun stopOffer(id: String): OffersResponse { calls += "stopOffer $id"; return OffersResponse(offers.offers.map { it.copy(stopped = true) }) }
        override suspend fun calendarLink(): CalendarLinkResponse { calls += "calendar"; return madeLink.copy(feed = null, url = null, webcalUrl = null) }
        override suspend fun makeCalendarLink(): CalendarLinkResponse { calls += "makeCalendar"; return madeLink }
        override suspend fun stopCalendarLink(): CalendarLinkResponse { calls += "stopCalendar"; return madeLink.copy(feed = null, url = null, webcalUrl = null) }
        override suspend fun rules(): BookingRules { calls += "rules"; return rules }
        override suspend fun saveRules(rules: BookingRules): BookingRules { calls += "saveRules"; lastRules = rules; return rules }
    }

    // Routes

    @Test fun `the new screens are the owner's, but for a person's own calendar`() {
        val added = listOf(ShopRoute.QuietTimes, ShopRoute.Offers, ShopRoute.Consent, ShopRoute.ConsentForm("aftercare"), ShopRoute.Calendar)
        assertEquals(listOf(ShopRoute.Calendar), added.filter { it.isAnybodys })
        for (route in added) assertEquals(route, ShopRoute.of(route.raw))
        assertEquals("Consent forms", ShopRoute.ConsentForm("aftercare").title)
        assertEquals("Your calendar", ShopRoute.Calendar.title)
    }

    // Consent

    @Test fun `the list is read each time it is come back to, and keeps what it shows while it asks`() {
        val api = Shop()
        val model = ConsentViewModel(api, handle = {})
        model.enter(1); model.enter(1); model.enter(2)
        assertEquals(listOf("consent", "consent"), api.calls)
        assertEquals(5, model.state.value.response?.documents?.size)
    }

    @Test fun `a kind opens on its live words, and saving starts the next version`() {
        val api = Shop()
        val model = ConsentFormViewModel("patch_test", api, handle = {}).apply { enter(1) }
        val live = consent.documents.first()
        assertEquals(live.body, model.state.value.text)
        assertFalse(model.state.value.isChanged)

        model.type(live.body + " Ask if anything has changed since.")
        assertTrue(model.state.value.isChanged)
        model.save()
        assertEquals("saveConsent patch_test", api.calls.last())
        assertEquals(6, model.state.value.document?.version)
        assertEquals("Saved as version 6. Anyone who signed the previous wording is still recorded against that text, not this one.", model.state.value.note)
        assertFalse(model.state.value.isChanged)
        // Typing again takes the sentence away: it was about the words before.
        model.type("Something else entirely, long enough to be wording.")
        assertNull(model.state.value.note)
    }

    @Test fun `wording too short to mean anything is refused before it is sent`() {
        val api = Shop()
        val model = ConsentFormViewModel("aftercare", api, handle = {}).apply { enter(1) }
        model.type("  Keep it dry.  ")
        assertNull(model.save())
        assertEquals(ConsentWords.TOO_SHORT, model.state.value.problem?.text)
        assertEquals("body", model.state.value.problem?.field)
        assertTrue(api.calls.none { it.startsWith("saveConsent") })
    }

    @Test fun `chairtime's refusal of the words is shown against the box, and the same words say so`() {
        val refusing = object : Shop() { override suspend fun saveConsent(kind: String, body: String) = throw ApiError.Validation("Not saved.", "body") }
        val model = ConsentFormViewModel("aftercare", refusing, handle = {}).apply { enter(1); type("Keep it dry for a day and ring us."); save() }
        assertEquals("body", model.state.value.problem?.field)
        assertFalse(model.state.value.isSaving)

        val same = object : Shop() { override suspend fun saveConsent(kind: String, body: String) = savedDocument.copy(body = body, unchanged = true) }
        val again = ConsentFormViewModel("aftercare", same, handle = {}).apply { enter(1); type("Keep it dry for a day and ring us."); save() }
        assertEquals(ConsentWords.SAME, again.state.value.note)
    }

    @Test fun `a kind the shop does not have says so`() {
        val model = ConsentFormViewModel("piercing", Shop(), handle = {}).apply { enter(1) }
        assertNull(model.state.value.document)
        assertTrue(model.state.value.failure!!.isNotEmpty())
        assertNull(model.save())
    }

    // Quiet times

    @Test fun `taking a suggestion answers with the screen, and says what it did`() {
        val api = Shop()
        val model = QuietTimesViewModel(api, handle = {}).apply { enter(1) }
        val first = model.state.value.response!!.suggestions.first()
        model.accept(first)
        assertEquals("accept 2 m 25", api.calls.last())
        assertEquals(3, model.state.value.response?.suggestions?.size)
        assertEquals(QuietWords.ADDED, model.state.value.notice)
        assertNull(model.state.value.busy)
    }

    @Test fun `the saving switch and stopping a rule each answer with the screen`() {
        val api = Shop()
        val model = QuietTimesViewModel(api, handle = {}).apply { enter(1) }
        val rule = model.state.value.response!!.rules.single()
        model.showSaving(rule, false)
        assertFalse(model.state.value.response!!.rules.single().showSaving)
        assertNull(model.state.value.notice)
        model.stop(rule)
        assertTrue(model.state.value.response!!.rules.isEmpty())
        assertEquals(QuietWords.STOPPED, model.state.value.notice)
        assertEquals(listOf("quietTimes", "showSaving false", "stop"), api.calls)
    }

    @Test fun `hours that already have a lower price say which rule is in the way`() {
        val api = object : Shop() {
            override suspend fun acceptQuietTime(weekday: Int, part: String, percent: Int) = throw ApiError.Validation("Those hours already have a lower price — Wednesday mornings. Stop that one first.", "part")
        }
        val model = QuietTimesViewModel(api, handle = {}).apply { enter(1) }
        model.accept(model.state.value.response!!.suggestions.first())
        assertTrue(model.state.value.problem!!.contains("Wednesday mornings"))
        assertNull(model.state.value.notice)
        assertEquals(4, model.state.value.response?.suggestions?.size)
    }

    @Test fun `being signed out is the app's to say, not the screen's`() {
        val api = object : Shop() { override suspend fun quietTimes() = throw ApiError.Unauthorized("Signed out.") }
        val model = QuietTimesViewModel(api, handle = { handled += it }).apply { enter(1) }
        assertTrue(handled.single() is ApiError.Unauthorized)
        assertNull(model.state.value.failure)
    }

    // Offers

    @Test fun `an offer is started from the form, and the list comes back with it`() {
        val api = Shop()
        val model = OffersViewModel(api, handle = {}).apply { enter(1) }
        model.starting(true)
        model.edit { it.copy(name = "ZZ Test offer", amount = "20", code = "zztestoffer", quietOnly = true) }
        model.start()
        assertEquals(OfferWrite("ZZ Test offer", "ZZTESTOFFER", "first_visit", null, "percent", 20, true), api.lastOffer)
        val state = model.state.value
        assertEquals(2, state.response?.offers?.size)
        assertEquals(OfferWords.ADDED, state.notice)
        assertFalse(state.isStarting)
        assertEquals("", state.draft.name)
    }

    @Test fun `a form that cannot be read says which field, and nothing is sent`() {
        val api = Shop()
        val model = OffersViewModel(api, handle = {}).apply { enter(1); starting(true) }
        model.edit { it.copy(name = "Half off everything", amount = "95") }
        assertNull(model.start())
        assertEquals("value", model.state.value.problem?.field)
        assertNull(api.lastOffer)
        // Typing clears the complaint; closing the form throws the draft away.
        model.edit { it.copy(amount = "50") }
        assertNull(model.state.value.problem)
        model.starting(false)
        assertEquals("", model.state.value.draft.name)
    }

    @Test fun `the days ticked and where it stops are sent, Monday first`() {
        val api = Shop()
        val model = OffersViewModel(api, handle = {}).apply { enter(1); starting(true) }
        model.edit { it.copy(name = "ZZ Midweek", amount = "15", maxRedemptions = "25").toggled(0).toggled(3).toggled(2) }
        model.start()
        assertEquals(OfferWrite("ZZ Midweek", null, "first_visit", null, "percent", 15, weekdays = listOf(2, 3, 0), maxRedemptions = 25), api.lastOffer)
        // A ceiling that is not a number stops at its field, before anything is sent.
        val typo = OffersViewModel(Shop(), handle = {}).apply { enter(1); starting(true) }
        typo.edit { it.copy(name = "ZZ Midweek", amount = "15", maxRedemptions = "0") }
        assertNull(typo.start())
        assertEquals("maxRedemptions", typo.state.value.problem?.field)
    }

    @Test fun `chairtime's refusal of the days stays at the days`() {
        val api = object : Shop() { override suspend fun startOffer(write: OfferWrite) = throw ApiError.Validation("Pick days of the week", "weekdays") }
        val model = OffersViewModel(api, handle = {}).apply { enter(1); starting(true) }
        model.edit { it.copy(name = "ZZ Midweek", amount = "15").toggled(2) }
        model.start()
        assertEquals("weekdays", model.state.value.problem?.field)
        assertTrue(model.state.value.isStarting)
        assertEquals(setOf(2), model.state.value.draft.weekdays)
    }

    @Test fun `chairtime's refusal keeps the form open with what was typed`() {
        val api = object : Shop() { override suspend fun startOffer(write: OfferWrite) = throw ApiError.Validation("That code is already in use.", "code") }
        val model = OffersViewModel(api, handle = {}).apply { enter(1); starting(true) }
        model.edit { it.copy(name = "New year", amount = "10", code = "NEWYEAR") }
        model.start()
        assertEquals("code", model.state.value.problem?.field)
        assertTrue(model.state.value.isStarting)
        assertEquals("New year", model.state.value.draft.name)
        assertFalse(model.state.value.isSaving)
    }

    @Test fun `stopping one says bookings keep their price`() {
        val api = Shop()
        val model = OffersViewModel(api, handle = {}).apply { enter(1) }
        val offer = model.state.value.response!!.offers.single()
        model.stop(offer)
        assertEquals("stopOffer ${offer.id}", api.calls.last())
        assertTrue(model.state.value.response!!.running.isEmpty())
        assertEquals(OfferWords.STOPPED, model.state.value.notice)
        assertNull(model.state.value.stopping)
    }

    // The calendar link

    @Test fun `a link is made once, shown once, and gone when the screen is come back to`() {
        val api = Shop()
        val model = CalendarViewModel(api, handle = {}).apply { enter(1) }
        assertFalse(model.state.value.response!!.hasLink)
        model.make()
        assertTrue(model.state.value.response!!.url!!.endsWith(".ics"))
        // Come back to: chairtime keeps no copy to show, so there is only that one exists.
        model.enter(2)
        assertNull(model.state.value.response!!.url)
        assertEquals(listOf("calendar", "makeCalendar", "calendar"), api.calls)
    }

    @Test fun `it is switched off, and a refusal leaves what was there`() {
        val api = Shop()
        val model = CalendarViewModel(api, handle = {}).apply { enter(1); make() }
        model.stop()
        assertFalse(model.state.value.response!!.hasLink)

        val failing = object : Shop() { override suspend fun makeCalendarLink() = throw ApiError.Server("Something went wrong.") }
        val other = CalendarViewModel(failing, handle = {}).apply { enter(1); make() }
        assertEquals("Something went wrong.", other.state.value.problem)
        assertFalse(other.state.value.isBusy)
        assertFalse(other.state.value.response!!.hasLink)
    }

    // The cancel switch

    @Test fun `whether the team may cancel is saved with the numbers`() {
        val api = Shop()
        val model = RulesViewModel(api, handle = {}).apply { enter(1) }
        assertTrue(model.state.value.staffMayCancel)
        model.teamMayCancel(false)
        assertFalse(model.state.value.justSaved)
        model.save()
        assertEquals(false, api.lastRules?.staffMayCancel)
        assertEquals(rules.noticeHours, api.lastRules?.noticeHours)
        assertFalse(model.state.value.staffMayCancel)
        assertTrue(model.state.value.justSaved)
    }
}
