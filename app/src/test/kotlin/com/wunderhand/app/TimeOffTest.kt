package com.wunderhand.app

import com.wunderhand.app.features.shop.ShopRoute
import com.wunderhand.app.features.shop.TimeOffViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Me
import com.wunderhand.core.TimeOffCheck
import com.wunderhand.core.TimeOffDraft
import com.wunderhand.core.TimeOffResponse
import com.wunderhand.core.TimeOffWords
import com.wunderhand.core.TimeOffWrite
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

/** Shop → Time off: the list, the form that asks what is booked before it saves, and each line's way back. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeOffTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))
    private val me: Me = fixture("me")
    private val list: TimeOffResponse = fixture("time-off")
    private val inTheWay: TimeOffCheck = fixture("time-off-check")

    private open inner class Shop(private val booked: TimeOffCheck = TimeOffCheck(days = 1)) : StubApi() {
        val writes = mutableListOf<TimeOffWrite>()
        override suspend fun timeOff(): TimeOffResponse { calls += "timeOff"; return list }
        override suspend fun checkTimeOff(write: TimeOffWrite): TimeOffCheck { calls += "check"; writes += write; return booked }
        override suspend fun takeTimeOff(write: TimeOffWrite): TimeOffResponse { calls += "take"; writes += write; return list.copy(entries = list.entries.take(1)) }
        override suspend fun removeTimeOff(seriesId: String, from: String, to: String, staffId: String?): TimeOffResponse { calls += "remove $seriesId $from $to $staffId"; return list.copy(entries = list.entries.drop(1)) }
        override suspend fun unblock(id: String) { calls += "unblock $id" }
    }

    private fun model(api: Shop, who: Me = me) = TimeOffViewModel(who, api, api, handle = {}, today = { "2028-04-03" }).apply { enter(1) }

    @Test fun `time off is anybody's, and comes back where it was left`() {
        assertTrue(ShopRoute.TimeOff.isAnybodys)
        assertEquals(ShopRoute.TimeOff, ShopRoute.of("time-off"))
        assertEquals("Time off", ShopRoute.TimeOff.title)
    }

    @Test fun `the form opens on tomorrow for whoever is asking, and closing it forgets it`() {
        val model = model(Shop())
        assertEquals(2, model.state.value.response?.entries?.size)
        assertNull(model.state.value.draft)

        model.adding(true)
        assertEquals(TimeOffDraft(me.staff.id, "2028-04-04", "2028-04-04"), model.state.value.draft)
        model.adding(false)
        assertFalse(model.state.value.isAdding)
        assertNull(model.state.value.draft)
    }

    /** An owner the diary does not show is not somebody days off can be given to. */
    @Test fun `somebody who is not in the list starts on the first person there is`() {
        val model = model(Shop(), me.copy(staff = me.staff.copy(id = "not-bookable")))
        model.adding(true)
        assertEquals(list.people.first().id, model.state.value.draft?.who)
    }

    @Test fun `days with nothing booked in them are asked about, then saved`() {
        val api = Shop()
        val model = model(api)
        model.adding(true)
        model.edit { it.copy(who = TimeOffDraft.EVERYONE).withFrom("2028-04-10").withTo("2028-04-12").copy(note = "Lisbon") }
        model.save()

        assertEquals(listOf("timeOff", "check", "take"), api.calls)
        assertEquals(TimeOffWrite("everyone", "2028-04-10", "2028-04-12", "Lisbon"), api.writes.last())
        val state = model.state.value
        assertEquals(TimeOffWords.SAVED, state.notice)
        assertFalse(state.isAdding); assertFalse(state.isSaving)
        assertEquals(1, state.response?.entries?.size)
    }

    @Test fun `bookings in the days are shown first, and saved over only when asked again`() {
        val api = Shop(inTheWay)
        val model = model(api)
        model.adding(true)
        model.save()

        assertEquals(listOf("timeOff", "check"), api.calls)
        assertEquals(1, model.state.value.check?.inside?.size)
        assertTrue(model.state.value.isAdding)
        assertNull(model.state.value.notice)

        model.save()
        assertEquals(listOf("timeOff", "check", "take"), api.calls)
        assertNull(model.state.value.check)
        assertEquals(TimeOffWords.SAVED, model.state.value.notice)
    }

    @Test fun `changing the days is a new question`() {
        val api = Shop(inTheWay)
        val model = model(api)
        model.adding(true)
        model.save()
        model.edit { it.withTo("2028-04-06") }
        assertNull(model.state.value.check)

        model.save()
        assertEquals(listOf("timeOff", "check", "check"), api.calls)
        assertEquals("2028-04-06", api.writes.last().to)
    }

    @Test fun `days chairtime refuses are said against the form, which stays open`() {
        val api = object : Shop() {
            override suspend fun takeTimeOff(write: TimeOffWrite): TimeOffResponse = throw ApiError.Validation("Not saved. That is more than a year.", "to")
        }
        val model = model(api)
        model.adding(true)
        model.save()
        val state = model.state.value
        assertEquals("Not saved. That is more than a year.", state.problem?.text)
        assertEquals("to", state.problem?.field)
        assertTrue(state.isAdding); assertFalse(state.isSaving)

        model.edit { it.withTo("2028-04-04") }
        assertNull(model.state.value.problem)
    }

    @Test fun `whole days are taken back by the request, for everyone on the line`() {
        val api = Shop()
        val model = model(api)
        val days = list.comingUp.single()
        model.remove(days)
        assertEquals("remove ${days.seriesId} 2028-04-03 2028-04-05 null", api.calls.last())
        assertEquals(TimeOffWords.REMOVED, model.state.value.notice)
        assertNull(model.state.value.removing)
        assertEquals(1, model.state.value.response?.entries?.size)
    }

    @Test fun `one person's days name them, so a colleague keeps theirs`() {
        val api = Shop()
        val model = model(api)
        val one = list.comingUp.single().let { it.copy(people = it.people.take(1), everyone = false) }
        model.remove(one)
        assertEquals("remove ${one.seriesId} 2028-04-03 2028-04-05 ${one.people.single().id}", api.calls.last())
    }

    @Test fun `a blocked hour is removed as the diary removes it, and the list read again`() {
        val api = Shop()
        val model = model(api)
        val hour = list.blockedHours.single()
        model.remove(hour)
        assertEquals(listOf("timeOff", "unblock ${hour.blockId}", "timeOff"), api.calls)
        assertEquals(TimeOffWords.REMOVED, model.state.value.notice)
    }

    @Test fun `days already taken back are said, and the list is kept`() {
        val api = object : Shop() {
            override suspend fun removeTimeOff(seriesId: String, from: String, to: String, staffId: String?): TimeOffResponse = throw ApiError.NotFound("Those days are not off.")
        }
        val model = model(api)
        model.remove(list.comingUp.single())
        assertEquals("Those days are not off.", model.state.value.problem?.text)
        assertNull(model.state.value.removing)
        assertEquals(2, model.state.value.response?.entries?.size)
    }
}
