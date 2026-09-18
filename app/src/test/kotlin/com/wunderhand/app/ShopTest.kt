package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.shop.HoursViewModel
import com.wunderhand.app.features.shop.OutletFormViewModel
import com.wunderhand.app.features.shop.OutletsViewModel
import com.wunderhand.app.features.shop.PolicyViewModel
import com.wunderhand.app.features.shop.RemindersViewModel
import com.wunderhand.app.features.shop.RulesViewModel
import com.wunderhand.app.features.shop.ShopRoute
import com.wunderhand.app.features.shop.ShopViewModel
import com.wunderhand.app.features.shop.TeamPersonFormViewModel
import com.wunderhand.app.features.shop.TeamPersonViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.BookingRules
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Employment
import com.wunderhand.core.HoursResponse
import com.wunderhand.core.HoursWrite
import com.wunderhand.core.InviteResponse
import com.wunderhand.core.Me
import com.wunderhand.core.OutletResponse
import com.wunderhand.core.OutletWrite
import com.wunderhand.core.OutletsResponse
import com.wunderhand.core.PolicyResponse
import com.wunderhand.core.PolicyWrite
import com.wunderhand.core.ReminderWords
import com.wunderhand.core.RemindersResponse
import com.wunderhand.core.RemindersWrite
import com.wunderhand.core.ShopResponse
import com.wunderhand.core.TeamPersonResponse
import com.wunderhand.core.TeamPersonWrite
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun text(name: String) = checkNotNull(object {}.javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))
private val me: Me = fixture("me")
private val shop: ShopResponse = fixture("shop")
private val hours: HoursResponse = fixture("hours")
private val rules: BookingRules = fixture("rules")
private val policy: PolicyResponse = fixture("policy")
private val reminders: RemindersResponse = fixture("reminders")
private val person: TeamPersonResponse = fixture("team-person")
private val invited: InviteResponse = fixture("team-invited")
private val outlets: OutletsResponse = fixture("outlets")
private val outlet: OutletResponse = fixture("outlet")

private open class TheShop : StubApi() {
    var lastHours: HoursWrite? = null
    var lastRules: BookingRules? = null
    var lastPolicy: PolicyWrite? = null
    var lastReminders: RemindersWrite? = null
    var lastPerson: TeamPersonWrite? = null
    var lastOutlet: OutletWrite? = null

    override suspend fun shop(): ShopResponse { calls += "shop"; return shop }
    override suspend fun hours(staffId: String?, outletId: String?): HoursResponse { calls += "hours $staffId $outletId"; return hours.copy(staffId = staffId ?: hours.staffId, outletId = outletId ?: hours.outletId) }
    override suspend fun saveHours(write: HoursWrite): HoursResponse { calls += "saveHours"; lastHours = write; return hours.copy(days = write.days) }
    override suspend fun rules(): BookingRules { calls += "rules"; return rules }
    override suspend fun saveRules(rules: BookingRules): BookingRules { calls += "saveRules"; lastRules = rules; return rules }
    override suspend fun policy(): PolicyResponse { calls += "policy"; return policy }
    override suspend fun savePolicy(write: PolicyWrite): PolicyResponse { calls += "savePolicy"; lastPolicy = write; return policy.copy(clientFacingWording = write.clientFacingWording) }
    override suspend fun reminders(): RemindersResponse { calls += "reminders"; return reminders }
    override suspend fun saveReminders(write: RemindersWrite): RemindersResponse { calls += "saveReminders"; lastReminders = write; return reminders }
    override suspend fun teamPerson(id: String): TeamPersonResponse { calls += "person $id"; return person }
    override suspend fun addTeamPerson(write: TeamPersonWrite): TeamPersonResponse { calls += "addPerson"; lastPerson = write; return person }
    override suspend fun updateTeamPerson(id: String, write: TeamPersonWrite): TeamPersonResponse { calls += "updatePerson $id"; lastPerson = write; return person }
    override suspend fun removeTeamPerson(id: String): TeamPersonResponse { calls += "removePerson $id"; return person.copy(person = person.person.copy(accountStatus = "suspended")) }
    override suspend fun invite(staffId: String, email: String?): InviteResponse { calls += "invite $staffId $email"; return invited }
    override suspend fun setOwner(staffId: String, owner: Boolean): TeamPersonResponse { calls += "owner $staffId $owner"; return person.copy(person = person.person.copy(isOwner = owner)) }
    override suspend fun outlets(): OutletsResponse { calls += "outlets"; return outlets }
    override suspend fun outlet(id: String): OutletResponse { calls += "outlet $id"; return outlet }
    override suspend fun addOutlet(write: OutletWrite): OutletResponse { calls += "addOutlet"; lastOutlet = write; return outlet }
    override suspend fun updateOutlet(id: String, write: OutletWrite): OutletResponse { calls += "updateOutlet $id"; lastOutlet = write; return outlet }
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class OnMain {
    @After fun reset() = Dispatchers.resetMain()
    protected fun unconfined(scope: TestScope) = Dispatchers.setMain(UnconfinedTestDispatcher(scope.testScheduler))
}

class ShopIndexTest : OnMain() {
    @Test fun `a person's page sits on the team, and back from the last screen looks at the shop again`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = ShopViewModel(me, api, handle = {})
        model.open(ShopRoute.Team)
        model.push(ShopRoute.Person("p1"))
        assertEquals(listOf(ShopRoute.Team, ShopRoute.Person("p1")), model.state.value.path)
        model.back()
        assertEquals(listOf("shop"), api.calls)
        model.back()
        assertTrue(model.state.value.path.isEmpty())
        assertEquals(listOf("shop", "shop"), api.calls)
        assertEquals(4, model.state.value.visit)
    }

    @Test fun `picking another row replaces what was open, and where somebody was comes back with the app`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        ShopViewModel(me, TheShop(), {}, handle).apply { open(ShopRoute.Team); push(ShopRoute.Person("p1")); open(ShopRoute.Rules); open(ShopRoute.Team); push(ShopRoute.Person("p2")) }
        val back = ShopViewModel(me, TheShop(), {}, handle).state.value
        assertEquals(listOf(ShopRoute.Team, ShopRoute.Person("p2")), back.path)
        assertEquals(5, back.visit)
    }

    @Test fun `only their own hours and their own login are anybody's`() {
        val all = listOf(ShopRoute.Hours, ShopRoute.Rules, ShopRoute.Policy, ShopRoute.Reminders, ShopRoute.Team, ShopRoute.Outlets, ShopRoute.Account, ShopRoute.Close, ShopRoute.Person("p"))
        assertEquals(listOf(ShopRoute.Hours, ShopRoute.Account), all.filter { it.isAnybodys })
        for (route in all) assertEquals(route, ShopRoute.of(route.raw))
        assertNull(ShopRoute.of("billing"))
    }
}

class SettingsTest : OnMain() {
    @Test fun `a screen is loaded when it is opened, and not again for being turned sideways`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = RulesViewModel(api, handle = {})
        model.enter(1)
        model.type("noticeHours", "6")
        model.enter(1)
        assertEquals("6", model.state.value.text["noticeHours"])
        model.enter(2)
        assertEquals("${rules.noticeHours}", model.state.value.text["noticeHours"])
        assertEquals(listOf("rules", "rules"), api.calls)
    }

    @Test fun `a rule that is not a whole number is named before anything is sent`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = RulesViewModel(api, handle = {}).apply { enter(1) }
        model.type("bufferMinutes", "ten")
        assertNull(model.save())
        assertEquals("Buffer after each appointment must be a whole number", model.state.value.problem?.text)
        assertEquals("bufferMinutes", model.state.value.problem?.field)
        model.type("bufferMinutes", "10")
        model.save()
        assertEquals(10, api.lastRules?.bufferMinutes)
        assertTrue(model.state.value.justSaved)
        model.type("bufferMinutes", "5")
        assertFalse(model.state.value.justSaved)
    }

    @Test fun `chairtime's refusal of a rule points at its field`() = runTest {
        unconfined(this)
        val api = object : TheShop() { override suspend fun saveRules(rules: BookingRules) = throw ApiError.Validation("Slots must be 5 to 120 minutes.", "slotIntervalMinutes") }
        val model = RulesViewModel(api, handle = {}).apply { enter(1); save() }
        assertEquals("slotIntervalMinutes", model.state.value.problem?.field)
        assertFalse(model.state.value.isSaving)
    }

    @Test fun `the policy's deposit is money, and wording left blank is the standard line`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = PolicyViewModel(api, handle = {}).apply { enter(1) }
        assertEquals(policy.defaultWording, model.state.value.defaultWording)
        model.type(PolicyViewModel.DEPOSIT, "a tenner")
        assertNull(model.save())
        assertEquals(PolicyViewModel.DEPOSIT, model.state.value.problem?.field)
        model.type(PolicyViewModel.DEPOSIT, "12.5"); model.type(PolicyViewModel.WORDING, "   ")
        model.save()
        assertEquals(1250, api.lastPolicy?.standardDepositPence)
        assertNull(api.lastPolicy?.clientFacingWording)
    }

    @Test fun `a day switched off is a day off, and no times are kept`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = HoursViewModel(api, handle = {}).apply { enter(1) }
        val working = hours.days.map { it.weekday }
        assertEquals(working.toSet(), model.state.value.rows.filterValues { it.isOn }.keys)
        model.edit(working[0]) { it.copy(isOn = false) }
        model.edit(0) { it.copy(isOn = true, opens = "10:00", closes = "16:00") }
        model.save()
        val sent = api.lastHours!!
        assertEquals(hours.staffId, sent.staffId)
        assertFalse(sent.days.any { it.weekday == working[0] })
        assertEquals(HoursResponse.Day(0, "10:00", "16:00"), sent.days.last())
    }

    @Test fun `a day that closes before it opens is said against its row`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = HoursViewModel(api, handle = {}).apply { enter(1) }
        model.edit(3) { DayRowOf("18:00", "09:00") }
        assertNull(model.save())
        assertEquals(3, model.state.value.problemWeekday)
        assertEquals(listOf("hours null null"), api.calls)
    }

    @Test fun `chairtime names the row it was sent, and the screen names the day`() = runTest {
        unconfined(this)
        val api = object : TheShop() { override suspend fun saveHours(write: HoursWrite) = throw ApiError.Validation("Closes too late.", "days.1.closesAt") }
        val model = HoursViewModel(api, handle = {}).apply { enter(1); save() }
        val sentSecond = HoursResponse.weekOrder.filter { w -> hours.days.any { it.weekday == w } }[1]
        assertEquals(sentSecond, model.state.value.problemWeekday)
    }

    @Test fun `somebody else's week is asked for by who and where`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = HoursViewModel(api, handle = {}).apply { enter(1) }
        model.show(hours.staff[0].id, hours.outlets[1].id)
        assertEquals("hours ${hours.staff[0].id} ${hours.outlets[1].id}", api.calls.last())
        assertEquals(hours.staff[0].id, model.state.value.response?.staffId)
    }

    @Test fun `an empty reminder is a slot not used, unless it is switched on`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = RemindersViewModel(api, handle = {}).apply { enter(1) }
        // A week, a day, two hours: each shown as the number somebody would have typed.
        assertEquals(listOf("7" to ReminderWords.Unit.Days, "1" to ReminderWords.Unit.Days, "2" to ReminderWords.Unit.Hours), model.state.value.rows.map { it.value to it.unit })
        model.edit(0) { it.copy(value = "", isOn = false) }
        model.edit(1) { it.copy(isOn = true) }
        model.edit(2) { it.copy(value = "", isOn = true) }
        assertNull(model.save())
        assertEquals("slots.2.minutesBefore", model.state.value.problem?.field)
        model.edit(2) { it.copy(value = "90", unit = ReminderWords.Unit.Minutes, isOn = false) }
        model.save()
        assertEquals(listOf(RemindersWrite.Slot(1440, true), RemindersWrite.Slot(90, false)), api.lastReminders?.slots)
    }
}

private fun DayRowOf(opens: String, closes: String) = com.wunderhand.app.features.shop.DayRow(true, opens, closes)

class TeamAndOutletsTest : OnMain() {
    @Test fun `an invitation needs an address, and says what chairtime said`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = TeamPersonViewModel(api, handle = {}).apply { enter("p1", 1) }
        assertNull(model.invite())
        assertEquals("An email is needed", model.state.value.problem)
        model.typeEmail(" zz.person@example.com ")
        model.invite()
        assertEquals("invite p1 zz.person@example.com", api.calls.last())
        assertEquals(invited.message, model.state.value.notice)
        assertEquals("invited", model.state.value.response?.person?.accountStatus)
        assertEquals("", model.state.value.email)
    }

    @Test fun `somebody coming back is given their login with no address`() = runTest {
        unconfined(this)
        val back = person.copy(person = person.person.copy(hasLogin = true, accountStatus = "suspended"))
        val api = object : TheShop() { override suspend fun teamPerson(id: String) = back }
        val model = TeamPersonViewModel(api, handle = {}).apply { enter("p1", 1) }
        model.invite()
        assertEquals("invite p1 null", api.calls.last())
    }

    @Test fun `a refused act says why, and looks at the person again`() = runTest {
        unconfined(this)
        val api = object : TheShop() { override suspend fun setOwner(staffId: String, owner: Boolean) = throw ApiError.Validation("A shop needs at least one owner.", null) }
        val model = TeamPersonViewModel(api, handle = {}).apply { enter("p1", 1) }
        model.setOwner(false)
        assertEquals("A shop needs at least one owner.", model.state.value.problem)
        assertEquals(listOf("person p1", "person p1"), api.calls)
        assertFalse(model.state.value.isBusy)
    }

    @Test fun `who sees the money, and removing, say what happened`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = TeamPersonViewModel(api, handle = {}).apply { enter("p1", 1) }
        model.setOwner(true)
        assertEquals("Saved. ZZ can now see the shop’s takings.", model.state.value.notice)
        model.remove()
        assertTrue(model.state.value.notice!!.startsWith("ZZ is off the team"))
        assertTrue(model.state.value.response!!.person.isSuspended)
    }

    @Test fun `somebody new works at the first outlet unless told otherwise`() = runTest {
        unconfined(this)
        val api = TheShop()
        val form = TeamPersonFormViewModel(null, api, handle = {})
        assertEquals(setOf(outlets.outlets[0].id), form.state.value.outletIds)
        assertNull(form.save { error("no name") })
        assertEquals("name", form.state.value.problem?.field)
        form.edit { it.copy(name = " Wren Halloway ", role = " ", employment = Employment.ChairRenter, isBookable = false) }
        form.save {}
        assertEquals(TeamPersonWrite("Wren Halloway", null, "chair_renter", false, listOf(outlets.outlets[0].id)), api.lastPerson)
    }

    @Test fun `somebody being changed keeps where they work, and half a form survives`() = runTest {
        unconfined(this)
        val api = TheShop()
        val handle = SavedStateHandle()
        TeamPersonFormViewModel(person, api, {}, handle).edit { it.copy(role = "Barber", outletIds = setOf(person.outletIds[0])) }
        val back = TeamPersonFormViewModel(person, api, {}, handle)
        assertEquals("Barber", back.state.value.role)
        assertEquals(setOf(person.outletIds[0]), back.state.value.outletIds)
        back.save {}
        assertEquals("updatePerson ${person.person.id}", api.calls.last())
        assertFalse("outlets" in api.calls)
    }

    @Test fun `the list has no travel fees, so opening an outlet fetches it`() = runTest {
        unconfined(this)
        val api = TheShop()
        val model = OutletsViewModel(api, handle = {}).apply { enter(1) }
        model.open("o1")
        assertEquals(outlet, model.state.value.editing)
        assertTrue(model.state.value.isEditing)
        model.closeForm(savedOne = true)
        assertEquals(listOf("outlets", "outlet o1", "outlets"), api.calls)
        model.add()
        assertTrue(model.state.value.isEditing)
        assertNull(model.state.value.editing)
    }

    @Test fun `an outlet's ladder is typed in any order and sent in miles`() = runTest {
        unconfined(this)
        val api = TheShop()
        val form = OutletFormViewModel(outlet, api, handle = {})
        assertEquals(3, form.state.value.draft.bands.size)
        form.removeBand(1)
        form.addBand()
        form.editBand(2) { it.copy(miles = "6", fee = "4") }
        form.save {}
        assertEquals(listOf(OutletWrite.Band(3, 0), OutletWrite.Band(6, 400), OutletWrite.Band(15, 1000)), api.lastOutlet?.travelBands)
        assertEquals("updateOutlet ${outlet.outlet.id}", api.calls.last())
    }

    @Test fun `a band with no sense in it is said against its row, and chairtime's own band names are not ours`() = runTest {
        unconfined(this)
        val api = object : TheShop() { override suspend fun addOutlet(write: OutletWrite) = throw ApiError.Validation("Fees must rise with distance.", "travelBands.1.feePence") }
        val form = OutletFormViewModel(null, api, handle = {})
        form.edit { it.copy(name = "The road") }
        form.addBand(); form.editBand(0) { it.copy(miles = "far") }
        assertNull(form.save { error("not sent") })
        assertEquals("band.0.miles", form.state.value.problem?.field)
        form.editBand(0) { it.copy(miles = "5", fee = "3") }
        form.save { error("refused") }
        assertEquals("Fees must rise with distance.", form.state.value.problem?.text)
        assertNull(form.state.value.problem?.field)
    }

    @Test fun `half an outlet survives the system taking the app away`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        OutletFormViewModel(null, TheShop(), {}, handle).apply { edit { it.copy(name = "Dalston", addressPrivate = true) }; addBand(); editBand(0) { it.copy(miles = "3") } }
        val back = OutletFormViewModel(null, TheShop(), {}, handle).state.value.draft
        assertEquals("Dalston", back.name)
        assertTrue(back.addressPrivate)
        assertEquals("3", back.bands.single().miles)
    }
}
