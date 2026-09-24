package com.wunderhand.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

private fun at(iso: String): Instant = Instant.parse(iso)

private fun appointment(id: String, start: String, end: String, price: Int? = 2800) = DiaryAppointment(
    id = id, bookingId = "b-$id", startsAt = at(start), endsAt = at(end),
    minutes = Duration.between(at(start), at(end)).toMinutes().toInt(), status = "confirmed",
    clientName = "Client $id", clientVisitCount = 3, serviceName = "Skin fade",
    pricePence = price?.let(::Pence), toTakePence = price?.let(::Pence),
)

private fun member(id: String, name: String, rows: List<DiaryRow>, takings: Int? = 2800, closed: Boolean = false) = DiaryMember(
    id, name, name.take(2).uppercase(),
    DiaryDay(
        date = at("2026-09-15T23:00:00Z"),
        open = if (closed) emptyList() else listOf(Span(at("2026-09-16T08:00:00Z"), at("2026-09-16T17:00:00Z"))),
        rows = rows, bookedCount = rows.count { it is DiaryAppointment }, freeMinutes = 0.0,
        takingsPence = takings?.let(::Pence), isClosed = closed, slotIntervalMinutes = 15,
    ),
)

/** The diary, decoded from chairtime's own responses. */
class DiaryContractTest {
    private val diary = ChairtimeJson.decodeFromString(DiaryResponse.serializer(), Fixtures.text("diary"))

    @Test fun `the diary decodes`() {
        assertEquals(7, diary.week.size)
        assertTrue(diary.team.members.size > 1)
        assertEquals(diary.team.bookedCount, diary.team.members.sumOf { it.day.appointments.size })
        assertTrue(diary.team.members.all { it.day.rows.isNotEmpty() || it.day.isClosed })
        assertNotNull(diary.team.members.flatMap { it.day.appointments }.first().toTakePence)
    }

    @Test fun `rows come back as what they are`() {
        val rows = diary.team.members.flatMap { it.day.rows }
        assertTrue(rows.any { it is DiaryGap })
        assertTrue(rows.any { it is DiaryAppointment })
    }

    @Test fun `the appointment decodes`() {
        val response = ChairtimeJson.decodeFromString(AppointmentResponse.serializer(), Fixtures.text("appointment"))
        assertTrue(response.seesMoney)
        assertNotNull(response.bill)
        assertTrue(response.appointment.lineItems.isNotEmpty())
        val repeat = checkNotNull(response.repeatOptions)
        assertTrue(repeat.suggestedWeeks in repeat.intervals)
        assertEquals("Every week", RepeatOptions.label(1))
        assertEquals("Every 4 weeks", RepeatOptions.label(4))
        // The outlet it is at, for a shop with more than one to show.
        assertEquals("Hackney Road", response.appointment.outletName)
    }

    /** A non-owner is sent no price for a colleague's appointment: the key is
     *  there and null, or one day not there at all. Either way it is a day, not an error. */
    @Test fun `a day with the money taken out still decodes`() {
        fun strip(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (element) {
            is JsonObject -> JsonObject(element.filterKeys { !it.endsWith("Pence") }.mapValues { strip(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map(::strip))
            else -> element
        }
        val bare = strip(ChairtimeJson.parseToJsonElement(Fixtures.text("diary")).jsonObject)
        val day = ChairtimeJson.decodeFromJsonElement(DiaryResponse.serializer(), bare)
        assertNull(day.team.takingsPence)
        assertTrue(day.team.members.flatMap { it.day.appointments }.all { it.pricePence == null })
        assertNull(DaySummary(day.team.members).takingsPence)
    }

    @get:Rule val folder = TemporaryFolder()

    @Test fun `a kept day comes back whole, and only for the day it is`() {
        val cache = OfflineCache(File(folder.root, "offline"))
        val loaded = at("2026-09-17T14:32:00Z")
        cache.save(diary, loaded, shopId = "shop-1")
        assertEquals(OfflineCache.SavedDay(loaded, diary), cache.savedDay("shop-1", diary.date))
        assertNull(cache.savedDay("shop-1", IsoDay.shift(diary.date, 1)))
        assertNull(cache.savedDay("another-shop", diary.date))
    }
}

class AgendaTest {
    private val gap = DiaryGap(at("2026-09-16T14:00:00Z"), at("2026-09-16T15:00:00Z"), 60)

    @Test fun `folds the same gap across people into one line`() {
        val items = Agenda.items(listOf(member("a", "Ade Balogun", listOf(gap)), member("k", "Kit Alvarez", listOf(gap))))
        val free = items.single() as AgendaItem.Free
        assertEquals(listOf("Ade Balogun", "Kit Alvarez"), free.who)
        assertEquals(listOf("a", "k"), free.staffIds)
    }

    @Test fun `puts the booking before a gap at the same minute`() {
        val early = DiaryGap(at("2026-09-16T10:00:00Z"), at("2026-09-16T11:00:00Z"), 60)
        val items = Agenda.items(listOf(
            member("a", "Ade", listOf(early)),
            member("k", "Kit", listOf(appointment("1", "2026-09-16T10:00:00Z", "2026-09-16T10:45:00Z"))),
        ))
        assertTrue(items.first() is AgendaItem.Booked)
        assertEquals(2, items.size)
    }

    @Test fun `runs in time order across people`() {
        val items = Agenda.items(listOf(
            member("a", "Ade", listOf(appointment("late", "2026-09-16T15:00:00Z", "2026-09-16T15:45:00Z"))),
            member("k", "Kit", listOf(appointment("early", "2026-09-16T09:00:00Z", "2026-09-16T09:45:00Z"))),
        ))
        assertEquals(listOf("early", "late"), items.map { it.id })
    }
}

class SummaryTest {
    @Test fun `adds up bookings, takings and gaps`() {
        val gap = DiaryGap(at("2026-09-16T14:00:00Z"), at("2026-09-16T15:00:00Z"), 60)
        val summary = DaySummary(listOf(
            member("a", "Ade", listOf(appointment("1", "2026-09-16T09:00:00Z", "2026-09-16T09:45:00Z"), gap), takings = 2800),
            member("k", "Kit", listOf(appointment("2", "2026-09-16T10:00:00Z", "2026-09-16T10:45:00Z")), takings = 4000),
        ))
        assertEquals(2, summary.booked)
        assertEquals(Pence(6800), summary.takingsPence)
        assertEquals("1 gap left", summary.gapsLabel)
    }

    @Test fun `leaves takings out when anyone shown hides theirs`() {
        val summary = DaySummary(listOf(member("a", "Ade", emptyList(), takings = null), member("k", "Kit", emptyList(), takings = 4000)))
        assertNull(summary.takingsPence)
    }

    @Test fun `week takings disappear with any hidden day`() {
        val open = WeekDay("2026-09-16", at("2026-09-16T12:00:00Z"), true, 4, null, 60.0, 540.0, 0.5)
        val closed = WeekDay("2026-09-14", at("2026-09-14T12:00:00Z"), false, 0, null, 0.0, 0.0, 0.0)
        val week = WeekSummary(listOf(open, closed))
        assertNull(week.takingsPence)
        assertEquals(4, week.booked)
        assertEquals(0.5, week.utilisation, 0.0) // closed days do not drag the average down
    }
}

class GridGeometryTest {
    @Test fun `widens to whole hours`() {
        val range = GridGeometry.range(
            listOf(Span(at("2026-09-16T08:30:00Z"), at("2026-09-16T16:15:00Z"))),
            fallback = Span(at("2026-09-16T08:00:00Z"), at("2026-09-16T17:00:00Z")),
        )
        assertEquals(Span(at("2026-09-16T08:00:00Z"), at("2026-09-16T17:00:00Z")), range)
    }

    @Test fun `labels time off by where it falls`() {
        val range = Span(at("2026-09-16T08:00:00Z"), at("2026-09-16T17:00:00Z"))
        val spans = GridGeometry.offDuty(listOf(
            Span(at("2026-09-16T09:00:00Z"), at("2026-09-16T12:00:00Z")),
            Span(at("2026-09-16T13:00:00Z"), at("2026-09-16T15:00:00Z")),
        ), range)
        assertEquals(listOf(GridGeometry.OffDutyPosition.Before, GridGeometry.OffDutyPosition.Between, GridGeometry.OffDutyPosition.After), spans.map { it.position })
        assertEquals(listOf(GridGeometry.OffDutyPosition.AllDay), GridGeometry.offDuty(emptyList(), range).map { it.position })
    }

    @Test fun `density follows the web`() {
        assertEquals(GridGeometry.Density.Full, GridGeometry.density(45))
        assertEquals(GridGeometry.Density.Single, GridGeometry.density(15))
        assertEquals(GridGeometry.Density.Tick, GridGeometry.density(10))
    }
}

class WordsTest {
    @Test fun `shifts across months and years`() {
        assertEquals("2026-10-05", IsoDay.shift("2026-09-28", 7))
        assertEquals("2026-12-26", IsoDay.shift("2027-01-02", -7))
        assertEquals("2026-10-26", IsoDay.shift("2026-10-25", 1)) // the clocks going back
    }

    @Test fun `names the day`() {
        assertEquals("WED", IsoDay.weekdayShort("2026-09-16"))
        assertEquals("Wednesday", IsoDay.weekdayLong("2026-09-16"))
        assertEquals(16, IsoDay.dayOfMonth("2026-09-16"))
        assertEquals("Wednesday 16 Sept", IsoDay.weekdayDayMonth("2026-09-16"))
    }

    @Test fun ordinals() {
        val cases = listOf(1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", 11 to "11th", 12 to "12th", 13 to "13th", 21 to "21st", 112 to "112th")
        for ((n, expected) in cases) assertEquals(expected, DiaryWords.ordinal(n))
    }

    @Test fun `describes the client`() {
        assertEquals("9th visit · usually every 6 weeks", DiaryWords.about(9, true, 42.0))
        assertEquals("First visit", DiaryWords.about(1, true, null))
        assertEquals("", DiaryWords.about(null, false, null))
    }

    @Test fun `a status this build has not heard of is still said`() {
        assertEquals(DiaryWords.Status("Awaiting Patch Test", DiaryWords.StatusTone.Grey), DiaryWords.status("awaiting_patch_test", false))
        assertEquals("In the chair", DiaryWords.status("confirmed", inTheChair = true).label)
    }
}
