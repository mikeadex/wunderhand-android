package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.menu.ExtrasViewModel
import com.wunderhand.app.features.menu.MenuViewModel
import com.wunderhand.app.features.menu.PerformersViewModel
import com.wunderhand.app.features.menu.ServiceFormViewModel
import com.wunderhand.app.features.menu.StepsViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.ExtraLinksWrite
import com.wunderhand.core.ExtraWrite
import com.wunderhand.core.ExtrasResponse
import com.wunderhand.core.Me
import com.wunderhand.core.MenuOptions
import com.wunderhand.core.MenuResponse
import com.wunderhand.core.MenuServiceResponse
import com.wunderhand.core.PerformersWrite
import com.wunderhand.core.SavedId
import com.wunderhand.core.ServiceWrite
import com.wunderhand.core.StepsWrite
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
private val me = ChairtimeJson.decodeFromString(Me.serializer(), text("me"))
private val menu = ChairtimeJson.decodeFromString(MenuResponse.serializer(), text("menu"))
private val fade = ChairtimeJson.decodeFromString(MenuServiceResponse.serializer(), text("menu-service"))
private val options = ChairtimeJson.decodeFromString(MenuOptions.serializer(), text("menu-options"))
private val extras = ChairtimeJson.decodeFromString(ExtrasResponse.serializer(), text("menu-extras"))

/** A chairtime with a menu, that writes down what it was asked to change. */
private open class Shop : StubApi() {
    var lastService: ServiceWrite? = null
    var lastSteps: StepsWrite? = null
    var lastPerformers: PerformersWrite? = null
    var lastLinks: ExtraLinksWrite? = null
    var lastExtra: ExtraWrite? = null

    override suspend fun menu(): MenuResponse { calls += "menu"; return menu }
    override suspend fun menuOptions(): MenuOptions { calls += "options"; return options }
    override suspend fun createService(write: ServiceWrite): MenuServiceResponse { calls += "create"; lastService = write; return fade }
    override suspend fun updateService(id: String, write: ServiceWrite): MenuServiceResponse { calls += "update $id"; lastService = write; return fade }
    override suspend fun archiveService(id: String) { calls += "archive $id" }
    override suspend fun saveSteps(serviceId: String, write: StepsWrite): MenuServiceResponse { calls += "steps"; lastSteps = write; return fade }
    override suspend fun savePerformers(serviceId: String, write: PerformersWrite): MenuServiceResponse { calls += "performers"; lastPerformers = write; return fade }
    override suspend fun extras(serviceId: String): ExtrasResponse { calls += "extras"; return extras }
    override suspend fun saveExtraLinks(serviceId: String, write: ExtraLinksWrite): ExtrasResponse { calls += "links"; lastLinks = write; return extras }
    override suspend fun createExtra(write: ExtraWrite): SavedId { calls += "createExtra"; lastExtra = write; return SavedId("x-new") }
    override suspend fun updateExtra(id: String, write: ExtraWrite): SavedId { calls += "updateExtra $id"; lastExtra = write; return SavedId(id) }
    override suspend fun retireExtra(id: String) { calls += "retire $id" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MenuTest {
    @After fun reset() = Dispatchers.resetMain()
    private fun unconfined(scope: kotlinx.coroutines.test.TestScope) = Dispatchers.setMain(UnconfinedTestDispatcher(scope.testScheduler))

    @Test fun `a new service opens on its own page, and the menu is asked for again`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = MenuViewModel(me, api, handle = {})
        model.adding(true)
        model.created("s-new")
        assertFalse(model.state.value.isAdding)
        assertEquals("s-new", model.state.value.openId)
        assertEquals(listOf("menu", "menu"), api.calls)
        model.archived()
        assertNull(model.state.value.openId)
    }

    @Test fun `a category that has gone is not one to stay narrowed to`() = runTest {
        unconfined(this)
        val model = MenuViewModel(me, Shop(), handle = {}, saved = SavedStateHandle(mapOf("category" to "gone", "open" to "s1")))
        assertNull(model.state.value.category)
        assertEquals("s1", model.state.value.openId)
        model.category(menu.categories[0].id)
        assertEquals(menu.categories[0].id, model.state.value.category)
    }

    @Test fun `a refusal to load is said, and what was on screen stays`() = runTest {
        unconfined(this)
        var fail = false
        val api = object : Shop() { override suspend fun menu() = if (fail) throw ApiError.Server("Try again in a moment.") else super.menu() }
        val model = MenuViewModel(me, api, handle = {})
        fail = true
        model.load(byHand = true)
        assertEquals("Try again in a moment.", model.state.value.failure)
        assertEquals(menu, model.state.value.response)
        assertFalse(model.state.value.isRefreshing)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceFormTest {
    @After fun reset() = Dispatchers.resetMain()
    private fun unconfined(scope: kotlinx.coroutines.test.TestScope) = Dispatchers.setMain(UnconfinedTestDispatcher(scope.testScheduler))

    @Test fun `a form with no name asks chairtime nothing, and says which field`() = runTest {
        unconfined(this)
        val api = Shop()
        val form = ServiceFormViewModel(null, api, handle = {})
        assertNull(form.save { error("nothing to save") })
        assertEquals("name", form.state.value.problem?.field)
        assertEquals(listOf("options"), api.calls)
    }

    @Test fun `a new service is created, and one that exists is updated`() = runTest {
        unconfined(this)
        val api = Shop()
        var made: MenuServiceResponse? = null
        ServiceFormViewModel(null, api, handle = {}).apply { edit { it.copy(name = " Beard sculpt ", price = "18.5") }; save { made = it } }
        assertEquals(fade, made)
        assertEquals("Beard sculpt", api.lastService?.name)
        assertEquals(1850, api.lastService?.pricePence)

        ServiceFormViewModel(fade, api, handle = {}).apply { edit { it.copy(deposit = "") }; save {} }
        assertEquals("update ${fade.service.id}", api.calls.last())
        assertEquals("Skin fade", api.lastService?.name)
        assertNull(api.lastService?.depositPence)
    }

    @Test fun `chairtime's refusal points at its field`() = runTest {
        unconfined(this)
        val api = object : Shop() { override suspend fun createService(write: ServiceWrite) = throw ApiError.Validation("There is already a service called that.", "name") }
        val form = ServiceFormViewModel(null, api, handle = {})
        form.edit { it.copy(name = "Skin fade") }
        form.save { error("refused") }
        assertEquals("name", form.state.value.problem?.field)
        assertFalse(form.state.value.isSaving)
    }

    @Test fun `half a form survives the system taking the app away`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        ServiceFormViewModel(null, Shop(), {}, handle).edit { it.copy(name = "Root tint", requiresConsent = true, minAgeYears = "16", prerequisiteServiceId = "patch") }
        val back = ServiceFormViewModel(null, Shop(), {}, handle).state.value.draft
        assertEquals("Root tint", back.name)
        assertTrue(back.requiresConsent)
        assertEquals("16", back.minAgeYears)
        assertEquals("patch", back.prerequisiteServiceId)
    }

    @Test fun `archiving says so, and only for a service that exists`() = runTest {
        unconfined(this)
        val api = Shop()
        assertNull(ServiceFormViewModel(null, api, handle = {}).archive { error("nothing to archive") })
        var gone = false
        ServiceFormViewModel(fade, api, handle = {}).archive { gone = true }
        assertTrue(gone)
        assertEquals("archive ${fade.service.id}", api.calls.last())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServicePartsTest {
    @After fun reset() = Dispatchers.resetMain()
    private fun unconfined(scope: kotlinx.coroutines.test.TestScope) = Dispatchers.setMain(UnconfinedTestDispatcher(scope.testScheduler))
    private val ade = fade.performers[0]

    @Test fun `steps are added, moved and removed, and saved whole`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = StepsViewModel(fade, api, handle = {})
        model.add(); model.add()
        model.edit(1) { it.copy(label = "Develop", minutes = "35", staffBusy = false) }
        model.edit(2) { it.copy(label = "Finish", minutes = "10") }
        model.move(2, -1)
        assertEquals(listOf("Cut", "Finish", "Develop"), model.state.value.rows.map { it.label })
        model.move(0, -1)
        assertEquals("Cut", model.state.value.rows[0].label)
        model.remove(1)
        model.save {}
        assertEquals(listOf(StepsWrite.Step("Cut", 45, true), StepsWrite.Step("Develop", 35, false)), api.lastSteps?.steps)
    }

    @Test fun `the last step stays, and minutes that are not a number are said before anything is sent`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = StepsViewModel(fade, api, handle = {})
        model.remove(0)
        assertEquals(1, model.state.value.rows.size)
        model.edit(0) { it.copy(minutes = "a while") }
        assertNull(model.save { error("not sent") })
        assertEquals("steps.0.minutes", model.state.value.problem?.field)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `an owner chooses among everybody who takes bookings`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = PerformersViewModel(fade, isOwner = true, myStaffId = me.staff.id, api, handle = {})
        val rows = model.state.value.rows!!
        assertEquals(options.staff.map { it.id }, rows.map { it.id })
        assertEquals(fade.performers.map { it.id }.toSet(), rows.filter { it.isOn }.map { it.id }.toSet())

        model.edit(ade.id) { it.copy(price = "32") }
        model.save {}
        assertEquals(3200, api.lastPerformers?.performers?.first { it.staffId == ade.id }?.pricePence)
        assertTrue(api.lastPerformers!!.performers.filter { it.staffId != ade.id }.all { it.pricePence == null })
    }

    @Test fun `anybody else sees their own row alone, and the owner's choices are never asked for`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = PerformersViewModel(fade, isOwner = false, myStaffId = ade.id, api, handle = {})
        assertEquals(listOf(ade.id), model.state.value.rows?.map { it.id })
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `a price that is not an amount is said, not read as the standard one`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = PerformersViewModel(fade, isOwner = false, myStaffId = ade.id, api, handle = {})
        model.edit(ade.id) { it.copy(price = "thirty") }
        assertNull(model.save { error("not sent") })
        assertEquals("price_${ade.id}", model.state.value.problem?.field)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `which extras are offered is saved as a set`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = ExtrasViewModel(fade, api, handle = {})
        val (beard, mobile) = extras.extras[0] to extras.extras[4]
        assertEquals(setOf(mobile.id), model.state.value.offered)
        model.offer(beard.id, true); model.offer(mobile.id, false)
        model.saveLinks()
        assertEquals(listOf(beard.id), api.lastLinks?.extraIds)
        assertTrue(model.state.value.justSaved)
        assertTrue(model.state.value.changedAnything)
        model.offer(beard.id, false)
        assertFalse(model.state.value.justSaved)
    }

    @Test fun `a new extra is offered at once on the service it was made from, and a changed one stays where it was`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = ExtrasViewModel(fade, api, handle = {})
        model.editing(null)
        model.type { it.copy(name = "Scalp massage", price = "8", minutes = "") }
        model.saveExtra()
        assertEquals(ExtraWrite("Scalp massage", 800, 0, fade.service.id), api.lastExtra)
        assertNull(model.state.value.form)
        assertEquals("extras", api.calls.last())

        model.editing(extras.extras[0])
        assertEquals("10.00", model.state.value.form?.price)
        model.type { it.copy(price = "12") }
        model.saveExtra()
        assertNull(api.lastExtra?.serviceId)
        assertEquals(1200, api.lastExtra?.pricePence)
    }

    @Test fun `an extra with no name or a price in words is refused here`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = ExtrasViewModel(fade, api, handle = {})
        model.editing(null)
        assertNull(model.saveExtra())
        assertEquals("name", model.state.value.form?.problem?.field)
        model.type { it.copy(name = "Tonic", price = "a fiver") }
        assertNull(model.saveExtra())
        assertEquals("pricePence", model.state.value.form?.problem?.field)
        model.type { it.copy(price = "5", minutes = "-5") }
        assertNull(model.saveExtra())
        assertEquals("minutes", model.state.value.form?.problem?.field)
        assertEquals(listOf("extras"), api.calls)
    }

    @Test fun `retiring is only for one that exists`() = runTest {
        unconfined(this)
        val api = Shop()
        val model = ExtrasViewModel(fade, api, handle = {})
        model.editing(null)
        assertNull(model.retire())
        model.editing(extras.extras[1])
        model.retire()
        assertTrue("retire ${extras.extras[1].id}" in api.calls)
        assertNull(model.state.value.form)
    }
}
