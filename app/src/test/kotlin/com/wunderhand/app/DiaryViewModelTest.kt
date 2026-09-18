package com.wunderhand.app

import androidx.lifecycle.SavedStateHandle
import com.wunderhand.app.features.diary.DiaryMode
import com.wunderhand.app.features.diary.DiaryViewModel
import com.wunderhand.core.AppointmentResponse
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.DiaryResponse
import com.wunderhand.core.IsoDay
import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiError
import com.wunderhand.network.DiaryApi
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private val me = ChairtimeJson.decodeFromString(Me.serializer(), text("me"))
    private val day = ChairtimeJson.decodeFromString(DiaryResponse.serializer(), text("diary"))

    private class FakeDiary(var answer: (String?) -> DiaryResponse) : DiaryApi {
        val askedFor = mutableListOf<String?>()
        override suspend fun diary(date: String?): DiaryResponse { askedFor += date; return answer(date) }
        override suspend fun appointment(id: String): AppointmentResponse = error("not asked for here")
    }

    private val handled = mutableListOf<ApiError>()
    private fun cache() = OfflineCache(File(folder.root, "offline"))
    private fun model(api: FakeDiary, saved: SavedStateHandle = SavedStateHandle(), now: Instant = Instant.parse("2026-09-17T14:32:00Z")) =
        DiaryViewModel(me, api, cache(), handle = { handled += it }, saved = saved, io = dispatcher, now = { now })

    @Test fun `opens on today, asked for without a date`() = runTest {
        val api = FakeDiary { day }
        val state = model(api).state.value
        assertEquals(listOf<String?>(null), api.askedFor)
        assertEquals(day, state.response)
        assertNull(state.failure)
        assertTrue(state.isShowingRequestedDay)
    }

    @Test fun `a loaded day is kept for a morning without signal`() = runTest {
        model(FakeDiary { day })
        assertEquals(day, cache().savedDay(me.shop.id, day.date)?.response)
    }

    @Test fun `another day keeps the one on screen until it arrives, then replaces it`() = runTest {
        val next = IsoDay.shift(day.date, 1)
        val api = FakeDiary { asked -> if (asked == null) day else day.copy(date = next) }
        val model = model(api)
        model.show(next)
        assertEquals(listOf(null, next), api.askedFor)
        assertEquals(next, model.state.value.response?.date)
    }

    @Test fun `tapping the day already shown asks nothing`() = runTest {
        val api = FakeDiary { day }
        val model = model(api)
        model.show(day.date)
        assertEquals(1, api.askedFor.size)
    }

    @Test fun `next week is seven days on from the day shown`() = runTest {
        val api = FakeDiary { day }
        val model = model(api)
        model.shiftWeek(1)
        assertEquals(IsoDay.shift(day.date, 7), api.askedFor.last())
    }

    @Test fun `a failed reload keeps the day and says how old it is`() = runTest {
        var online = true
        val model = model(FakeDiary { if (online) day else throw ApiError.Offline() })
        online = false
        model.load()
        val state = model.state.value
        assertEquals(day, state.response)
        assertEquals(ApiError.OFFLINE_MESSAGE, state.failure)
        assertTrue(state.isShowingAKeptDay)
        assertEquals(Instant.parse("2026-09-17T14:32:00Z"), state.loadedAt)
    }

    @Test fun `no signal at launch opens on the day this phone last saw`() = runTest {
        val loaded = Instant.parse("2026-09-17T08:05:00Z")
        cache().save(day, loaded, me.shop.id)
        // The shop's today is the day that was kept.
        val now = day.dayStart.plusSeconds(12 * 3600)
        val state = model(FakeDiary { throw ApiError.Offline() }, now = now).state.value
        assertEquals(day, state.response)
        assertEquals(loaded, state.loadedAt)
        assertTrue(state.isShowingAKeptDay)
    }

    @Test fun `yesterday's kept day is not shown as today`() = runTest {
        cache().save(day, Instant.parse("2026-09-17T08:05:00Z"), me.shop.id)
        val tomorrow = day.dayStart.plusSeconds(36 * 3600)
        val state = model(FakeDiary { throw ApiError.Offline() }, now = tomorrow).state.value
        assertNull(state.response)
        assertEquals(ApiError.OFFLINE_MESSAGE, state.failure)
    }

    @Test fun `a session that has ended is the whole app's business, not a note on the diary`() = runTest {
        val state = model(FakeDiary { throw ApiError.Unauthorized("Sign in again.") }).state.value
        assertTrue(handled.single() is ApiError.Unauthorized)
        assertNull(state.failure)
    }

    @Test fun `somebody removed from the team since they were chosen is no longer the filter`() = runTest {
        val model = model(FakeDiary { day }, SavedStateHandle(mapOf("focus" to "left-last-week")))
        assertNull(model.state.value.focusStaffId)
    }

    @Test fun `narrowing to one person shows only their day`() = runTest {
        val model = model(FakeDiary { day })
        val someone = day.team.members[1]
        model.focus(someone.id)
        assertEquals(listOf(someone), model.state.value.shownMembers)
        model.focus(null)
        assertEquals(day.team.members, model.state.value.shownMembers)
    }

    @Test fun `the phone's grid is yours unless you chose somebody`() = runTest {
        val model = model(FakeDiary { day })
        val mine = day.team.members.firstOrNull { it.id == me.staff.id } ?: day.team.members.first()
        assertEquals(mine, model.state.value.gridMember(me.staff.id))
    }

    @Test fun `what was chosen comes back after the system kills the app`() = runTest {
        val saved = SavedStateHandle()
        val first = model(FakeDiary { day }, saved)
        first.setMode(DiaryMode.Week)
        first.open("appt-1")
        val second = model(FakeDiary { day }, saved)
        assertEquals(DiaryMode.Week, second.state.value.mode)
        assertEquals("appt-1", second.state.value.openAppointmentId)
    }

    @Test fun `a view the other layout does not have falls back to the day`() = runTest {
        val model = model(FakeDiary { day })
        model.setMode(DiaryMode.Grid)
        model.fitTo(wide = true)
        assertEquals(DiaryMode.Day, model.state.value.mode)
        model.setMode(DiaryMode.List)
        model.fitTo(wide = false)
        assertEquals(DiaryMode.Day, model.state.value.mode)
        assertFalse(model.state.value.isLoading)
    }
}
