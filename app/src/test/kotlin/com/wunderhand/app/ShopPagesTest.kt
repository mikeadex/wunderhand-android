package com.wunderhand.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wunderhand.app.features.shop.TimeOffViewModel
import com.wunderhand.app.features.shop.pageKey
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Me
import com.wunderhand.core.TimeOffPerson
import com.wunderhand.core.TimeOffResponse
import com.wunderhand.core.TimeOffWords
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whose a Shop page is: the person signed in, at the shop they are acting for.
 *
 * The app's view models outlive a sign-out — they are the activity's, and it
 * is still there — so a page kept under its own name alone was handed to the
 * next person as the last one left it. These hold the page to its person, and
 * every page in Shop to that rule.
 */
class ShopPagesTest : OnMain() {
    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))

    private val owner: Me = fixture("me")
    /** On the same team, on the same phone, and not an owner. */
    private val stylist = owner.copy(user = Me.User("u-dez", "dez@fold.example"), staff = owner.staff.copy(id = "95d32e80-01b2-4e83-afc2-90e433f45286", name = "Dez Mwangi", isOwner = false))

    private val everyones: TimeOffResponse = fixture("time-off")
    private val theirOwn = TimeOffResponse(mayChooseWho = false, people = listOf(TimeOffPerson(stylist.staff.id, stylist.staff.name)))

    /** Chairtime, answering whoever is signed in. */
    private class TimeOff(private val answer: TimeOffResponse) : StubApi() {
        override suspend fun timeOff(): TimeOffResponse { calls += "timeOff"; return answer }
        override suspend fun removeTimeOff(seriesId: String, from: String, to: String, staffId: String?): TimeOffResponse { calls += "remove"; return answer.copy(entries = answer.entries.drop(1)) }
    }

    /** What `TimeOffScreen` does, in the store every screen in the app shares: the page, opened on the index's count of screens opened. */
    private fun ViewModelStore.timeOff(who: Me, api: TimeOff, visit: Int): TimeOffViewModel =
        ViewModelProvider.create(this, viewModelFactory { initializer { TimeOffViewModel(who, api, api, handle = {}, today = { "2028-04-03" }) } })[pageKey(who, "time-off"), TimeOffViewModel::class]
            .apply { enter(visit) }

    @Test fun `the next person to sign in is not shown the last person's time off`() = runTest {
        unconfined(this)
        val phone = ViewModelStore()

        // An owner opens Time off — the first screen they open — opens the form, and takes a line back.
        val kits = phone.timeOff(owner, TimeOff(everyones), visit = 1)
        kits.adding(true)
        kits.remove(everyones.entries.first())
        assertTrue(kits.state.value.isAdding)
        assertEquals(TimeOffWords.REMOVED, kits.state.value.notice)
        assertTrue(kits.state.value.response!!.choosesWho)

        // They sign out and a stylist signs in. Time off is the first screen the stylist opens too: the same count.
        val api = TimeOff(theirOwn)
        val dezs = phone.timeOff(stylist, api, visit = 1)

        assertNotSame(kits, dezs)
        assertEquals("chairtime is asked for the stylist's own", listOf("timeOff"), api.calls)
        val shown = dezs.state.value
        assertEquals(theirOwn, shown.response)
        assertFalse("nobody to choose: no Who", shown.response!!.choosesWho)
        assertNull("the owner's notice is not theirs", shown.notice)
        assertFalse("nor the owner's form", shown.isAdding)
        dezs.adding(true)
        assertEquals("their form is for them", stylist.staff.id, dezs.state.value.draft?.who)
    }

    @Test fun `a page is still the same page when the phone is turned`() = runTest {
        unconfined(this)
        val phone = ViewModelStore()
        val api = TimeOff(everyones)
        val before = phone.timeOff(owner, api, visit = 1).apply { adding(true) }
        val after = phone.timeOff(owner, api, visit = 1)
        assertSame(before, after)
        assertTrue(after.state.value.isAdding)
        // Come back to, not opened again: the list is read once more behind the form (see `cameBack`), and the form stays.
        assertEquals(listOf("timeOff", "timeOff"), api.calls)
        assertEquals(everyones, after.state.value.response)
    }

    @Test fun `a page is kept for one person at one shop`() {
        val elsewhere = owner.copy(shop = owner.shop.copy(id = "shop-2"), staff = owner.staff.copy(id = "staff-2"))
        assertNotEquals(pageKey(owner, "calendar"), pageKey(stylist, "calendar"))
        assertNotEquals(pageKey(owner, "calendar"), pageKey(elsewhere, "calendar"))
        assertNotEquals(pageKey(owner, "calendar"), pageKey(owner, "hours"))
        assertNotEquals(pageKey(owner), pageKey(owner, "hours"))
        assertEquals(pageKey(owner, "calendar"), pageKey(owner.copy(), "calendar"))
        // The index's own, as it has been since 1.0: what was open on it comes back after the system kills the app.
        assertEquals("shop-${owner.shop.id}-${owner.staff.id}", pageKey(owner))
    }

    /**
     * The rule, held over the screens themselves: nothing in Shop asks for a
     * view model by a name that leaves out whose it is. A new page that keeps
     * its model under "shop-something" fails here, by file and line.
     */
    @Test fun `every screen in Shop keeps its view model under the person's name`() {
        val shop = File("src/main/kotlin/com/wunderhand/app/features/shop")
        assertTrue("run from the app module: ${shop.absolutePath}", shop.isDirectory)
        val asked = shop.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> "${file.name}:${index + 1}: ${line.trim()}".takeIf { "viewModel(" in line && !line.trimStart().startsWith("import ") && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") } }
        }.toList()
        assertTrue("Shop's screens were not found", asked.size >= 15)
        val anybodys = asked.filterNot { "viewModel(key = pageKey(" in it }
        assertTrue("kept under a name the next person to sign in would share:\n" + anybodys.joinToString("\n"), anybodys.isEmpty())
    }
}
