package com.wunderhand.app

import com.wunderhand.app.features.diary.AppointmentModel
import com.wunderhand.app.features.diary.DiaryViewModel
import com.wunderhand.app.features.diary.PendingChange
import com.wunderhand.app.features.diary.SheetAction
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ActionWords
import com.wunderhand.core.AppointmentResponse
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.CloseOutcome
import com.wunderhand.core.CloseResponse
import com.wunderhand.core.DiaryResponse
import com.wunderhand.core.IsoDay
import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.core.Pence
import com.wunderhand.core.RepeatStarted
import com.wunderhand.core.ShopClock
import com.wunderhand.core.SlotsResponse
import com.wunderhand.network.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

private fun text(name: String) = checkNotNull(object {}.javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }

/** A drag on a grid, and a tap on a break. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryChangesTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private val me = ChairtimeJson.decodeFromString(Me.serializer(), text("me"))
    private val day = ChairtimeJson.decodeFromString(DiaryResponse.serializer(), text("diary"))
    private val booking = day.team.members.flatMap { it.day.appointments }.first()
    private val handled = mutableListOf<ApiError>()

    private open inner class Shop : StubApi() {
        var loads = 0
        override suspend fun diary(date: String?): DiaryResponse { loads++; return day }
    }

    private fun model(api: StubApi) = DiaryViewModel(me, api, OfflineCache(File(folder.root, "offline")), handle = { handled += it }, io = dispatcher)

    @Test fun `a dropped booking stays where it was dropped until chairtime answers, then the day is reloaded`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val api = object : Shop() {
            override suspend fun move(appointmentId: String, to: Instant) { calls += "move $appointmentId $to"; answer.await() }
        }
        val model = model(api)
        val to = booking.startsAt.plusSeconds(900)
        model.move(booking, to)
        assertEquals(PendingChange(booking.id, PendingChange.Edge.Start, to), model.state.value.pending)
        assertEquals(1, api.loads)

        answer.complete(Unit)
        assertNull(model.state.value.pending)
        assertNull(model.state.value.actionProblem)
        assertEquals(listOf("move ${booking.id} $to"), api.calls)
        assertEquals(2, api.loads)
    }

    @Test fun `a clash puts it back and says so in chairtime's words`() = runTest {
        val api = object : Shop() {
            override suspend fun move(appointmentId: String, to: Instant) { throw ApiError.SlotTaken("Someone got there first.") }
        }
        val model = model(api)
        model.move(booking, booking.startsAt.plusSeconds(900))
        assertNull(model.state.value.pending)
        assertEquals("Someone got there first.", model.state.value.actionProblem)
        assertEquals(2, api.loads) // shown as it really is
        model.dismissActionProblem()
        assertNull(model.state.value.actionProblem)
    }

    @Test fun `a second drag while the first is unanswered does nothing`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val api = object : Shop() {
            override suspend fun move(appointmentId: String, to: Instant) { calls += "move"; answer.await() }
            override suspend fun resize(appointmentId: String, endsAt: Instant) { calls += "resize" }
        }
        val model = model(api)
        model.move(booking, booking.startsAt.plusSeconds(900))
        assertNull(model.resize(booking, booking.endsAt.plusSeconds(900)))
        answer.complete(Unit)
        assertEquals(listOf("move"), api.calls)
    }

    @Test fun `the handle changes the end, not the start`() = runTest {
        val api = object : Shop() {
            override suspend fun resize(appointmentId: String, endsAt: Instant) { calls += "resize $appointmentId $endsAt" }
        }
        val to = booking.endsAt.plusSeconds(1800)
        model(api).resize(booking, to)
        assertEquals(listOf("resize ${booking.id} $to"), api.calls)
    }

    @Test fun `a session that ended mid-drag is the app's business, and nothing is left pending`() = runTest {
        val api = object : Shop() {
            override suspend fun move(appointmentId: String, to: Instant) { throw ApiError.Unauthorized("Sign in again.") }
        }
        val model = model(api)
        model.move(booking, booking.startsAt.plusSeconds(900))
        assertTrue(handled.single() is ApiError.Unauthorized)
        assertNull(model.state.value.pending)
        assertNull(model.state.value.actionProblem)
    }

    @Test fun `a booking made goes to its day and opens there`() = runTest {
        val api = Shop()
        val model = model(api)
        model.startBooking(com.wunderhand.app.features.booking.NewBookingStart())
        val elsewhere = IsoDay.shift(day.date, 5)
        model.booked(com.wunderhand.core.BookingCreated("b1", "new-appointment", Instant.parse("2026-09-21T10:00:00Z"), elsewhere))
        val state = model.state.value
        assertNull(state.newBooking)
        assertEquals("new-appointment", state.openAppointmentId)
        assertEquals(elsewhere, state.date)
    }

    @Test fun `a tap on somebody's empty column books them there, on the shop's grid`() = runTest {
        val member = day.team.members.first { it.day.open.isNotEmpty() }
        val opens = member.day.open.first().start
        // Long before the day in the fixture, so the time has not passed.
        val model = DiaryViewModel(me, Shop(), OfflineCache(File(folder.root, "offline")), handle = {}, io = dispatcher, now = { Instant.parse("2020-01-01T00:00:00Z") })
        model.bookAt(member, minutesFromGridStart = 52, gridStart = opens)
        val start = model.state.value.newBooking!!
        assertEquals(member.id, start.staffId)
        val step = member.day.slotIntervalMinutes
        assertEquals(opens.plusSeconds((52 / step * step) * 60L), start.slot)
    }

    @Test fun `a tap on time that has passed, or that they do not work, brings only the person`() = runTest {
        val member = day.team.members.first { it.day.open.isNotEmpty() }
        val opens = member.day.open.first().start
        val passed = DiaryViewModel(me, Shop(), OfflineCache(File(folder.root, "a")), handle = {}, io = dispatcher, now = { Instant.parse("2099-01-01T00:00:00Z") })
        passed.bookAt(member, 60, opens)
        assertEquals(member.id, passed.state.value.newBooking?.staffId)
        assertNull(passed.state.value.newBooking?.slot)

        val before = DiaryViewModel(me, Shop(), OfflineCache(File(folder.root, "b")), handle = {}, io = dispatcher, now = { Instant.parse("2020-01-01T00:00:00Z") })
        before.bookAt(member, 0, opens.minusSeconds(3 * 3600))
        assertNull(before.state.value.newBooking?.slot)
    }

    @Test fun `time blocked on another day goes to that day, and on this one reloads it`() = runTest {
        val api = Shop()
        val model = model(api)
        model.blockingTime(true)
        model.blocked(on = day.date)
        assertFalse(model.state.value.isBlockingTime)
        assertEquals(2, api.loads)
        assertNull(model.state.value.date)

        model.blocked(on = IsoDay.shift(day.date, 3))
        assertEquals(IsoDay.shift(day.date, 3), model.state.value.date)
    }
}

/** What can be done to an open appointment. */
class AppointmentModelTest {
    private val appointment = ChairtimeJson.decodeFromString(AppointmentResponse.serializer(), text("appointment"))
    private val id = appointment.appointment.id
    private var dayReloads = 0
    private val handled = mutableListOf<ApiError>()

    private open inner class Shop : StubApi() {
        override suspend fun appointment(id: String): AppointmentResponse { calls += "appointment"; return appointment }
    }

    private fun model(api: StubApi) = AppointmentModel(id, api, ShopClock("Europe/London"), changed = { dayReloads++ }, handle = { handled += it })

    @Test fun `cancelling says what came back, shows the appointment as it now is, and reloads the day`() = runTest {
        val api = object : Shop() {
            override suspend fun close(appointmentId: String, outcome: CloseOutcome): CloseResponse {
                calls += "close ${outcome.raw}"
                return CloseResponse("cancelled", CloseResponse.Refund(Pence(1000), Pence(0)))
            }
        }
        val model = model(api)
        model.close(CloseOutcome.Cancelled)
        val state = model.state.value
        assertEquals("Cancelled. £10 deposit refunded.", state.notice?.text)
        assertFalse(state.notice!!.isProblem)
        assertNull(state.busy)
        assertEquals(listOf("close cancelled", "appointment"), api.calls)
        assertEquals(1, dayReloads)
    }

    @Test fun `a refusal is said as a problem, and everything is reloaded anyway`() = runTest {
        val api = object : Shop() {
            override suspend fun close(appointmentId: String, outcome: CloseOutcome): CloseResponse =
                throw ApiError.AlreadyClosed("This appointment was already cancelled.")
        }
        val model = model(api)
        model.close(CloseOutcome.Completed)
        assertEquals("This appointment was already cancelled.", model.state.value.notice?.text)
        assertTrue(model.state.value.notice!!.isProblem)
        // A refusal usually means something changed elsewhere.
        assertEquals(listOf("appointment"), api.calls)
        assertEquals(1, dayReloads)
    }

    @Test fun `one thing at a time`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val api = object : Shop() {
            override suspend fun recordConsent(appointmentId: String) { calls += "consent"; answer.await() }
            override suspend fun startRepeat(appointmentId: String, intervalWeeks: Int): RepeatStarted { calls += "repeat"; return RepeatStarted(1) }
        }
        val model = model(api)
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { model.recordConsent() }
        assertEquals(SheetAction.Consent, model.state.value.busy)
        model.startRepeat(4)
        answer.complete(Unit)
        first.join()
        assertEquals(listOf("consent", "appointment"), api.calls)
        assertEquals(ActionWords.consentRecorded(appointment.consentWording?.version), model.state.value.notice?.text)
    }

    @Test fun `a session that has ended goes to the app, and nothing is said on the sheet`() = runTest {
        val api = object : Shop() {
            override suspend fun close(appointmentId: String, outcome: CloseOutcome): CloseResponse = throw ApiError.Unauthorized("Sign in again.")
        }
        val model = model(api)
        model.close(CloseOutcome.Completed)
        assertTrue(handled.single() is ApiError.Unauthorized)
        assertNull(model.state.value.notice)
        assertNull(model.state.value.busy)
    }

    @Test fun `moving it goes back to the appointment with where it went`() = runTest {
        val to = Instant.parse("2026-10-14T10:15:00Z")
        val api = object : Shop() {
            override suspend fun move(appointmentId: String, to: Instant) { calls += "move $to" }
        }
        val model = model(api)
        assertTrue(model.moveTo(to))
        assertEquals("Moved to 11:15 on Wednesday 14 October.", model.state.value.notice?.text)
        assertEquals(listOf("move $to", "appointment"), api.calls)
        assertEquals(1, dayReloads)
    }

    @Test fun `a time taken while they were looking stays on the times, which are asked for again`() = runTest {
        val api = object : Shop() {
            override suspend fun move(appointmentId: String, to: Instant) { throw ApiError.SlotTaken("Someone got there first.") }
            override suspend fun slots(appointmentId: String, from: String?): SlotsResponse { calls += "slots"; return SlotsResponse(Instant.EPOCH, emptyList()) }
        }
        val model = model(api)
        assertFalse(model.moveTo(Instant.parse("2026-10-14T10:15:00Z")))
        assertEquals(ActionWords.SLOT_TAKEN_WHILE_LOOKING, model.state.value.slotsProblem)
        assertNull(model.state.value.moving)
        assertEquals(listOf("slots"), api.calls)
        assertEquals(0, dayReloads)
    }
}
