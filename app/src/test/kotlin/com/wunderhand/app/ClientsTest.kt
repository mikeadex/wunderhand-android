package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.clients.ClientFormViewModel
import com.wunderhand.app.features.clients.ClientProfileModel
import com.wunderhand.app.features.clients.ClientsViewModel
import com.wunderhand.app.features.clients.HealthViewModel
import com.wunderhand.app.features.clients.NotesGate
import com.wunderhand.app.features.clients.TimedGate
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.ClientFilter
import com.wunderhand.core.ClientInput
import com.wunderhand.core.ClientProfileResponse
import com.wunderhand.core.ClientSaved
import com.wunderhand.core.ClientWords
import com.wunderhand.core.ClientsResponse
import com.wunderhand.core.HealthResponse
import com.wunderhand.core.HealthSaved
import com.wunderhand.core.Me
import com.wunderhand.core.PickedContact
import com.wunderhand.core.ShopClock
import com.wunderhand.core.UnlockWords
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun text(name: String) = checkNotNull(object {}.javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
private val me = ChairtimeJson.decodeFromString(Me.serializer(), text("me"))
private val list = ChairtimeJson.decodeFromString(ClientsResponse.serializer(), text("clients"))
private val profile = ChairtimeJson.decodeFromString(ClientProfileResponse.serializer(), text("client"))
private val notes = ChairtimeJson.decodeFromString(HealthResponse.serializer(), text("health"))

@OptIn(ExperimentalCoroutinesApi::class)
class ClientsListTest {
    @After fun reset() = Dispatchers.resetMain()

    private class Shop : StubApi() {
        override suspend fun clients(filter: ClientFilter, query: String): ClientsResponse { calls += "clients ${filter.raw} '$query'"; return list }
    }

    @Test fun `typing searches once the typing stops, not on every letter`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = Shop()
        val model = ClientsViewModel(me, api, handle = {})
        runCurrent()
        assertEquals(listOf("clients all ''"), api.calls)

        model.search("w"); model.search("wr"); model.search("wre")
        advanceTimeBy(299); runCurrent()
        assertEquals(1, api.calls.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("clients all ''", "clients all 'wre'"), api.calls)
    }

    @Test fun `a filter asks at once, and keeps what is typed`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val api = Shop()
        val model = ClientsViewModel(me, api, handle = {}, pauseMillis = 0)
        model.search("wren")
        model.filter(ClientFilter.Due)
        assertEquals("clients due 'wren'", api.calls.last())
    }

    @Test fun `somebody new opens, and the list is asked for again`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val api = Shop()
        val model = ClientsViewModel(me, api, handle = {})
        model.adding(true)
        model.added("c-new")
        assertFalse(model.state.value.isAdding)
        assertEquals("c-new", model.state.value.openId)
        assertEquals(2, api.calls.size)
        model.removed()
        assertNull(model.state.value.openId)
    }

    @Test fun `it comes back to who was open, but never straight into their medical notes`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val saved = SavedStateHandle()
        val first = ClientsViewModel(me, Shop(), handle = {}, saved = saved)
        first.filter(ClientFilter.Regulars); first.open("c1"); first.showHealth(true)
        val back = ClientsViewModel(me, Shop(), handle = {}, saved = saved).state.value
        assertEquals(ClientFilter.Regulars, back.filter)
        assertEquals("c1", back.openId)
        assertFalse(back.showingHealth)
    }

    @Test fun `only an owner is drawn a column for money`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        assertTrue(ClientsViewModel(me, Shop(), handle = {}).seesMoney)
        val ade = me.copy(staff = me.staff.copy(isOwner = false))
        assertFalse(ClientsViewModel(ade, Shop(), handle = {}).seesMoney)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClientFormTest {
    @After fun reset() = Dispatchers.resetMain()
    private fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @Test fun `a new client is posted, and one on file is changed starting from what is there`() {
        main()
        val api = object : StubApi() {
            override suspend fun createClient(input: ClientInput): ClientSaved { calls += "create ${input.name}"; return ClientSaved("c-new") }
            override suspend fun updateClient(id: String, input: ClientInput): ClientSaved { calls += "update $id ${input.standingFormula}"; return ClientSaved(id) }
        }
        var savedAs: String? = null
        ClientFormViewModel(null, api, handle = {}).apply { edit { it.copy(name = "Wren Halloway") }; save { savedAs = it } }
        assertEquals("c-new", savedAs)

        val editing = ClientFormViewModel(profile.client, api, handle = {})
        assertEquals(profile.client.name, editing.state.value.input.name)
        editing.save { savedAs = it }
        assertEquals("update ${profile.client.id} 6/0 + 20vol, 35 min", api.calls.last())
    }

    @Test fun `a refusal is said, on the field it is about, and nothing closes`() {
        main()
        val api = object : StubApi() {
            override suspend fun createClient(input: ClientInput): ClientSaved = throw ApiError.Validation("Somebody already has that mobile.", "phone")
        }
        var closed = false
        val form = ClientFormViewModel(null, api, handle = {})
        form.save { closed = true }
        assertFalse(closed)
        assertEquals("Somebody already has that mobile.", form.state.value.problem)
        assertEquals("phone", form.state.value.problemField)
        assertFalse(form.state.value.isSaving)
    }

    @Test fun `a contact fills what it has, says so, and leaves the rest as typed`() {
        main()
        val form = ClientFormViewModel(null, StubApi(), handle = {})
        form.edit { it.copy(notes = "Parks round the back") }
        form.fill(PickedContact(givenName = "Wren", familyName = "Halloway", phones = listOf(PickedContact.Labelled("mobile", "07700 900123"))))
        assertEquals(ClientInput(name = "Wren Halloway", phone = "07700 900123", notes = "Parks round the back"), form.state.value.input)
        assertEquals("Filled the name and mobile from your contacts. Check them before adding.", form.state.value.fromContacts)
    }

    @Test fun `a phone call in the middle does not lose what was typed`() {
        main()
        val saved = SavedStateHandle()
        ClientFormViewModel(null, StubApi(), handle = {}, saved = saved).edit { it.copy(name = "Wren", email = "wren@example.com") }
        assertEquals(ClientInput(name = "Wren", email = "wren@example.com"), ClientFormViewModel(null, StubApi(), handle = {}, saved = saved).state.value.input)
    }

    @Test fun `removing asks chairtime, then goes`() {
        main()
        val api = object : StubApi() { override suspend fun removeClient(id: String) { calls += "remove $id" } }
        var gone = false
        ClientFormViewModel(profile.client, api, handle = {}).remove { gone = true }
        assertTrue(gone)
        assertEquals(listOf("remove ${profile.client.id}"), api.calls)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HealthTest {
    @After fun reset() = Dispatchers.resetMain()
    private fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private class Gate(var answer: NotesGate.Outcome) : TimedGate() {
        var asked = 0
        override val method = UnlockWords.Method.Biometric
        override suspend fun unlock(reason: String): NotesGate.Outcome {
            if (isUnlocked()) return NotesGate.Outcome.Unlocked
            asked++
            if (answer == NotesGate.Outcome.Unlocked) opened()
            return answer
        }
    }

    private open class Shop : StubApi() {
        override suspend fun health(clientId: String): HealthResponse { calls += "health"; return notes }
    }

    private fun model(api: StubApi, gate: NotesGate) = HealthViewModel("c1", "Wren Halloway", api, ShopClock("Europe/London"), gate, handle = {})

    @Test fun `nothing is fetched — so nothing is written down as read — until the phone says yes`() {
        main()
        val api = Shop()
        val gate = Gate(NotesGate.Outcome.Cancelled)
        val model = model(api, gate)
        model.open()
        assertTrue(model.state.value.isLocked)
        assertTrue(api.calls.isEmpty())
        assertNull(model.state.value.unlockProblem) // dismissed: nothing to say

        gate.answer = NotesGate.Outcome.Failed(UnlockWords.DID_NOT_MATCH)
        model.open()
        assertEquals(UnlockWords.DID_NOT_MATCH, model.state.value.unlockProblem)
        assertTrue(api.calls.isEmpty())

        gate.answer = NotesGate.Outcome.Unlocked
        model.open()
        assertFalse(model.state.value.isLocked)
        assertEquals(listOf("health"), api.calls)
        assertTrue("PPD" in model.state.value.values.getValue("allergies"))
    }

    @Test fun `the next client inside two minutes is not asked again`() {
        main()
        val gate = Gate(NotesGate.Outcome.Unlocked)
        model(Shop(), gate).open()
        val next = model(Shop(), gate)
        assertFalse(next.state.value.isLocked)
        next.open()
        assertEquals(1, gate.asked)
    }

    @Test fun `a phone with no screen lock opens them, and says it cannot lock them`() {
        main()
        val model = model(Shop(), Gate(NotesGate.Outcome.Unprotected))
        model.open()
        assertTrue(model.state.value.isUnprotected)
        assertFalse(model.state.value.isLocked)
        assertEquals(notes, model.state.value.response)
    }

    @Test fun `leaving the app takes what was read, and the unlock, with it`() {
        main()
        val gate = Gate(NotesGate.Outcome.Unlocked)
        val model = model(Shop(), gate)
        model.open()
        model.type("allergies", "half a sentence")
        model.leftTheApp()
        assertNull(model.state.value.response)
        assertTrue(model.state.value.values.isEmpty())
        assertTrue(model.state.value.isLocked)
        assertFalse(gate.isUnlocked())
    }

    @Test fun `saving sends what is in the boxes and says how long it is kept`() {
        main()
        val api = object : Shop() {
            override suspend fun saveHealth(clientId: String, record: Map<String, String>): HealthSaved {
                calls += "save ${record["allergies"]}"; return HealthSaved(true, Instant.parse("2034-09-16T00:00:00Z"))
            }
        }
        val model = model(api, Gate(NotesGate.Outcome.Unlocked))
        model.open(); model.type("allergies", "Latex"); model.save()
        assertEquals(listOf("health", "save Latex", "health"), api.calls)
        assertEquals("Saved and encrypted. Kept until 16 September 2034." to false, model.state.value.notice)
    }

    @Test fun `erasing sends the typed word for chairtime to judge, and says what came back`() {
        main()
        val api = object : Shop() {
            override suspend fun eraseHealth(clientId: String, confirmation: String) {
                calls += "erase $confirmation"
                if (confirmation != "erase") throw ApiError.Validation("Type erase to confirm.", "confirm")
            }
        }
        val model = model(api, Gate(NotesGate.Outcome.Unlocked))
        model.open()
        model.typeConfirmation("eraze"); model.erase()
        assertEquals("Type erase to confirm." to true, model.state.value.notice)
        model.typeConfirmation("erase"); model.erase()
        assertEquals(ClientWords.HEALTH_ERASED to false, model.state.value.notice)
        assertEquals("", model.state.value.confirmation)
    }
}

class ClientProfileModelTest {
    @Test fun `a booking made from a profile is said, with the profile as it now is`() = runTest {
        val api = object : StubApi() { override suspend fun client(id: String): ClientProfileResponse { calls += "client $id"; return profile } }
        val model = ClientProfileModel("c1", api, ShopClock("Europe/London"), handle = {})
        model.load()
        model.bookedIn(Instant.parse("2026-09-17T09:00:00Z"))
        assertEquals("Booked in for Thu 17 Sept, 10:00.", model.state.value.notice)
        assertEquals(listOf("client c1", "client c1"), api.calls)
    }
}
