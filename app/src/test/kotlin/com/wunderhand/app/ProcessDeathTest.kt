package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.menu.MenuViewModel
import com.wunderhand.app.features.shop.OutletsViewModel
import com.wunderhand.app.features.shop.TeamPersonViewModel
import com.wunderhand.app.features.shop.TeamViewModel
import com.wunderhand.app.features.waitlist.WaitlistViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Me
import com.wunderhand.core.MenuResponse
import com.wunderhand.core.OutletResponse
import com.wunderhand.core.OutletsResponse
import com.wunderhand.core.TeamPersonResponse
import com.wunderhand.core.TeamResponse
import com.wunderhand.core.WaitlistResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun text(name: String) = checkNotNull(object {}.javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))

private class Back : StubApi() {
    override suspend fun menu(): MenuResponse = fixture("menu")
    override suspend fun team(): TeamResponse = fixture("team")
    override suspend fun teamPerson(id: String): TeamPersonResponse = fixture("team-person")
    override suspend fun outlets(): OutletsResponse = fixture("outlets")
    override suspend fun outlet(id: String): OutletResponse { calls += "outlet $id"; return fixture("outlet") }
    override suspend fun waitlist(): WaitlistResponse = fixture("waitlist")
}

/**
 * The system kills an app in the background whenever it likes. What was typed
 * into a form was always kept; found in A8, *that the form was open* was not —
 * so the app came back to the screen behind it and the draft was never seen
 * again. Each test here is a view model made twice over one SavedStateHandle,
 * which is what a process death is.
 */
class ProcessDeathTest : OnMain() {
    private val me: Me = fixture("me")

    @Test fun `a new service's form comes back`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        MenuViewModel(me, Back(), {}, handle).adding(true)
        assertTrue(MenuViewModel(me, Back(), {}, handle).state.value.isAdding)
        MenuViewModel(me, Back(), {}, handle).created("s1")
        assertFalse(MenuViewModel(me, Back(), {}, handle).state.value.isAdding)
    }

    @Test fun `the form for somebody new on the team comes back`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        TeamViewModel(Back(), {}, handle).adding(true)
        assertTrue(TeamViewModel(Back(), {}, handle).state.value.isAdding)
    }

    @Test fun `a person being changed comes back mid-change, and nobody else does`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        TeamPersonViewModel(Back(), {}, handle).apply { enter("p1", 1); editing(true) }
        assertTrue(TeamPersonViewModel(Back(), {}, handle).apply { enter("p1", 1) }.state.value.isEditing)
        assertFalse(TeamPersonViewModel(Back(), {}, handle).apply { enter("p2", 2) }.state.value.isEditing)
    }

    @Test fun `a new outlet's form comes back as it was, and one being changed is fetched again`() = runTest {
        unconfined(this)
        val fresh = SavedStateHandle()
        OutletsViewModel(Back(), {}, fresh).add()
        val back = OutletsViewModel(Back(), {}, fresh).state.value
        assertTrue(back.isEditing)
        assertNull(back.editing)
        // The same form, so the same draft: the key it is kept under has not moved.
        assertEquals(1, back.formVisit)

        val changing = SavedStateHandle()
        OutletsViewModel(Back(), {}, changing).open("o1")
        val api = Back()
        val again = OutletsViewModel(api, {}, changing).state.value
        assertEquals(listOf("outlet o1"), api.calls)
        assertTrue(again.isEditing)
        assertEquals(1, again.formVisit)

        OutletsViewModel(Back(), {}, changing).closeForm(savedOne = false)
        assertFalse(OutletsViewModel(Back(), {}, changing).state.value.isEditing)
    }

    @Test fun `who is being added to the waiting list comes back`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        WaitlistViewModel(Back(), {}, saved = handle).adding(true)
        val back = WaitlistViewModel(Back(), {}, saved = handle).state.value
        assertTrue(back.isAdding)
        assertEquals(1, back.addVisit)
        WaitlistViewModel(Back(), {}, saved = handle).added("Wren")
        assertFalse(WaitlistViewModel(Back(), {}, saved = handle).state.value.isAdding)
    }
}
