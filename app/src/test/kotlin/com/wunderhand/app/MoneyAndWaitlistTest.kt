package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.money.MoneyViewModel
import com.wunderhand.app.features.money.TillViewModel
import com.wunderhand.app.features.waitlist.GapViewModel
import com.wunderhand.app.features.waitlist.GapWindow
import com.wunderhand.app.features.waitlist.WaitingFor
import com.wunderhand.app.features.waitlist.WaitlistJoinViewModel
import com.wunderhand.app.features.waitlist.WaitlistViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.CheckoutResponse
import com.wunderhand.core.ClientFilter
import com.wunderhand.core.ClientsResponse
import com.wunderhand.core.GapResponse
import com.wunderhand.core.Me
import com.wunderhand.core.MoneyResponse
import com.wunderhand.core.OfferSent
import com.wunderhand.core.Pence
import com.wunderhand.core.Receipt
import com.wunderhand.core.SettledResponse
import com.wunderhand.core.TillMethod
import com.wunderhand.core.TillRequest
import com.wunderhand.core.WaitlistJoinRequest
import com.wunderhand.core.WaitlistJoined
import com.wunderhand.core.WaitlistOptions
import com.wunderhand.core.WaitlistResponse
import com.wunderhand.network.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun text(name: String) = checkNotNull(object {}.javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))
private val me: Me = fixture("me")
private val waiting: WaitlistResponse = fixture("waitlist")
private val gap: GapResponse = fixture("gap")
private val offered: OfferSent = fixture("offer-sent")
private val settledBill: CheckoutResponse = fixture("checkout")
private val openBill = settledBill.copy(settledAt = null, receipt = null)
private val money: MoneyResponse = fixture("money")
private val clientList: ClientsResponse = fixture("clients")

private open class Desk : StubApi() {
    var lastJoin: WaitlistJoinRequest? = null
    var lastOffer: List<String>? = null
    var lastTill: TillRequest? = null
    var bill = openBill

    override suspend fun waitlist(): WaitlistResponse { calls += "waitlist"; return waiting }
    override suspend fun waitlistOptions(): WaitlistOptions { calls += "options"; return WaitlistOptions(listOf(WaitlistOptions.Option("sv1", "Skin fade"), WaitlistOptions.Option("sv2", "Root tint")), listOf(WaitlistOptions.Option("s1", "Kit Alvarez"))) }
    override suspend fun joinWaitlist(request: WaitlistJoinRequest): WaitlistJoined { calls += "join"; lastJoin = request; return WaitlistJoined("w-new") }
    override suspend fun leaveWaitlist(id: String) { calls += "leave $id" }
    override suspend fun clients(filter: ClientFilter, query: String): ClientsResponse { calls += "clients '$query'"; return clientList }
    override suspend fun gap(staffId: String, from: Instant, to: Instant): GapResponse { calls += "gap $staffId"; return gap }
    override suspend fun offerGap(staffId: String, from: Instant, to: Instant, entryIds: List<String>): OfferSent { calls += "offer"; lastOffer = entryIds; return offered }
    override suspend fun checkout(bookingId: String): CheckoutResponse { calls += "checkout $bookingId"; return bill }
    override suspend fun settle(bookingId: String, request: TillRequest): SettledResponse { calls += "settle"; lastTill = request; bill = settledBill; return SettledResponse(Instant.parse("2026-09-18T14:00:00Z"), Receipt()) }
    override suspend fun money(): MoneyResponse { calls += "money"; return money }
}

class WaitlistTest : OnMain() {
    @Test fun `taking somebody off says so, and looks at the list again`() = runTest {
        unconfined(this)
        val api = Desk()
        val model = WaitlistViewModel(api, handle = {})
        val row = waiting.waiting.first()
        model.remove(row)
        assertEquals(listOf("waitlist", "leave ${row.id}", "waitlist"), api.calls)
        assertEquals("${row.clientName} is off the list.", model.state.value.notice)
        assertNull(model.state.value.removingId)
    }

    @Test fun `the eyebrow counts people, offers out, and the longest wait`() = runTest {
        unconfined(this)
        val longest = waiting.longestWaitingSince!!
        val model = WaitlistViewModel(Desk(), handle = {}, now = { longest.plusSeconds(9 * 86_400) })
        val eyebrow = model.eyebrow(model.state.value)
        val n = waiting.waiting.size
        assertTrue(eyebrow, eyebrow.startsWith("$n ${if (n == 1) "person" else "people"}"))
        assertTrue(eyebrow, eyebrow.endsWith("longest waiting 9 days"))
    }

    @Test fun `from a client's profile the form is up at once, and adding says who`() = runTest {
        unconfined(this)
        val model = WaitlistViewModel(Desk(), handle = {}, addStraightAway = true)
        assertTrue(model.state.value.isAdding)
        model.added("Wren Halloway")
        assertFalse(model.state.value.isAdding)
        assertEquals("Wren Halloway is on the list.", model.state.value.notice)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `looking somebody up waits for the typing to stop`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = Desk()
        val form = WaitlistJoinViewModel(api, api, handle = {})
        runCurrent()
        form.search("w"); form.search("wr"); form.search("wre")
        advanceTimeBy(249); runCurrent()
        assertEquals(listOf("options"), api.calls)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("options", "clients 'wre'"), api.calls)
        assertTrue(form.state.value.matches.size <= 6)
    }

    @Test fun `blank means any, and the times of day go in the day's order`() = runTest {
        unconfined(this)
        val api = Desk()
        val form = WaitlistJoinViewModel(api, api, handle = {}, preset = WaitingFor("c1", "Wren Halloway"))
        assertEquals("sv1", form.state.value.serviceId)
        var said: String? = null
        form.part("e"); form.part("m"); form.day(5); form.day(4); form.day(5); form.latest("2026-10-14")
        form.save { said = it }
        assertEquals(WaitlistJoinRequest("c1", "sv1", null, null, "2026-10-14", listOf(4), listOf("m", "e")), api.lastJoin)
        assertEquals("Wren Halloway", said)
    }

    @Test fun `nobody chosen is nothing sent, and dates the wrong way round are said`() = runTest {
        unconfined(this)
        val api = Desk()
        val form = WaitlistJoinViewModel(api, api, handle = {})
        assertNull(form.save { error("nobody") })
        form.choose(clientList.clients.first())
        form.earliest("2026-10-20"); form.latest("2026-10-01")
        assertNull(form.save { error("backwards") })
        assertTrue(form.state.value.problem!!.contains("before"))
        assertFalse("join" in api.calls)
    }

    @Test fun `half a form survives the system taking the app away`() = runTest {
        unconfined(this)
        val api = Desk()
        val handle = SavedStateHandle()
        WaitlistJoinViewModel(api, api, {}, WaitingFor("c1", "Wren Halloway"), handle).apply { service("sv2"); staff("s1"); day(1); part("a") }
        val back = WaitlistJoinViewModel(api, api, {}, null, handle).state.value
        assertEquals(WaitingFor("c1", "Wren Halloway"), back.client)
        assertEquals("sv2", back.serviceId)
        assertEquals("s1", back.staffId)
        assertEquals(setOf(1), back.days)
        assertEquals(setOf("a"), back.parts)
    }
}

class GapTest : OnMain() {
    private val window = GapWindow(gap.staffId, gap.from, gap.to)

    @Test fun `the best fits are ticked to begin with, and go out in chairtime's order`() = runTest {
        unconfined(this)
        val api = Desk()
        val model = GapViewModel(window, api, handle = {})
        assertEquals(gap.preselected, model.state.value.ticked)
        val all = gap.candidates.map { it.entryId }
        // Ticked backwards; sent best fit first.
        for (id in all) if (id in model.state.value.ticked) model.tick(id)
        for (id in all.reversed()) model.tick(id)
        model.offer()
        assertEquals(all, api.lastOffer)
        assertEquals(offered, model.state.value.sent)
    }

    @Test fun `nobody ticked is nothing offered`() = runTest {
        unconfined(this)
        val api = Desk()
        val model = GapViewModel(window, api, handle = {})
        for (id in gap.preselected) model.tick(id)
        assertFalse(model.state.value.canOffer)
        assertNull(model.offer())
        assertFalse("offer" in api.calls)
    }

    @Test fun `a refusal is said and the ticks stay`() = runTest {
        unconfined(this)
        val api = object : Desk() { override suspend fun offerGap(staffId: String, from: Instant, to: Instant, entryIds: List<String>) = throw ApiError.SlotTaken("That time has just been booked.") }
        val model = GapViewModel(window, api, handle = {}).apply { offer() }
        assertEquals("That time has just been booked.", model.state.value.failure)
        assertEquals(gap.preselected, model.state.value.ticked)
        assertNull(model.state.value.sent)
    }

    @Test fun `a window comes back after the system has had the app`() {
        assertEquals(window, GapWindow.unpack(window.packed()))
        assertEquals(90, window.minutes)
        assertNull(GapWindow.unpack(arrayListOf("s1", "soon", "later")))
    }
}

class TillTest : OnMain() {
    @Test fun `what to ask for moves as it is typed, and stops at anything that is not money`() = runTest {
        unconfined(this)
        val model = TillViewModel("b1", Desk(), handle = {})
        val before = model.state.value.owing!!.duePence.value
        model.edit { it.copy(extra = "12", tip = "3.50") }
        assertEquals(before + 1550, model.state.value.owing!!.duePence.value)
        model.edit { it.copy(tip = "a bit") }
        assertNull(model.state.value.owing)
    }

    @Test fun `rung through once, with what was typed, and the appointment behind is told`() = runTest {
        unconfined(this)
        val api = Desk()
        val model = TillViewModel("b1", api, handle = {})
        var told = 0
        model.edit { it.copy(extra = "12", extraNote = " Pomade ", tip = "", method = TillMethod.Card) }
        model.settle { told++ }
        assertEquals(TillRequest(1200, "Pomade", 0, "card"), api.lastTill)
        assertEquals(listOf("checkout b1", "settle", "checkout b1"), api.calls)
        assertTrue(model.state.value.view!!.isSettled)
        assertEquals(1, told)
        // Settled: a second press is not a second ring.
        assertNull(model.settle { told++ })
        assertEquals(1, api.calls.count { it == "settle" })
    }

    @Test fun `money that is not money is said before anything is sent`() = runTest {
        unconfined(this)
        val api = Desk()
        val model = TillViewModel("b1", api, handle = {})
        model.edit { it.copy(tip = "-5") }
        assertNull(model.settle { error("not sent") })
        assertEquals("Enter an amount like 12 or 12.50.", model.state.value.problem)
        assertFalse("settle" in api.calls)
    }

    @Test fun `already rung through elsewhere is said, and the bill is shown as it now is`() = runTest {
        unconfined(this)
        val api = object : Desk() {
            override suspend fun settle(bookingId: String, request: TillRequest): SettledResponse { bill = settledBill; throw ApiError.AlreadySettled("This has already been checked out.") }
        }
        val model = TillViewModel("b1", api, handle = {})
        model.settle { error("it was not this press that settled it") }
        assertEquals("This has already been checked out.", model.state.value.problem)
        assertTrue(model.state.value.view!!.isSettled)
        assertEquals(Pence(4500), model.state.value.view!!.receipt?.takenPence)
        assertFalse(model.state.value.isSettling)
    }

    @Test fun `half a bill survives the phone ringing`() = runTest {
        unconfined(this)
        val handle = SavedStateHandle()
        TillViewModel("b1", Desk(), {}, handle).edit { it.copy(extra = "8", extraNote = "Wax", method = TillMethod.Other) }
        val back = TillViewModel("b1", Desk(), {}, handle).state.value
        assertEquals("8", back.extra)
        assertEquals("Wax", back.extraNote)
        assertEquals(TillMethod.Other, back.method)
    }
}

class MoneyTabTest : OnMain() {
    @Test fun `the month is compared with the same point of the last one`() = runTest {
        unconfined(this)
        val model = MoneyViewModel(me, Desk(), handle = {}, now = { Instant.parse("2026-09-04T10:00:00Z") })
        assertEquals("September", model.monthName)
        val comparison = model.comparison(money)
        assertTrue(comparison.text, comparison.text.endsWith("on the same point last month") || comparison.text.startsWith("First month"))

        val late = MoneyViewModel(me, Desk(), handle = {}, now = { Instant.parse("2026-09-29T10:00:00Z") }).comparison(money)
        assertTrue(late.text, late.text.endsWith("on last month") || late.text.startsWith("First month"))
        assertNull(late.finished)
    }

    @Test fun `a refusal is said, and the figures on screen stay`() = runTest {
        unconfined(this)
        var fail = false
        val api = object : Desk() { override suspend fun money() = if (fail) throw ApiError.Server("Try again in a moment.") else super.money() }
        val model = MoneyViewModel(me, api, handle = {})
        fail = true
        model.load(byHand = true)
        assertEquals("Try again in a moment.", model.state.value.failure)
        assertEquals(money, model.state.value.response)
        assertFalse(model.state.value.isRefreshing)
    }
}
