package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun at(iso: String): Instant = Instant.parse(iso)

private fun block(id: String, start: String, end: String, allDay: Boolean = false, kind: String = "holiday", note: String? = null) =
    DiaryBreak(id, kind, at(start), at(end), note, allDay)

private fun member(id: String, name: String, rows: List<DiaryRow> = emptyList(), breaks: List<DiaryBreak> = emptyList()) = DiaryMember(
    id, name, name.take(2).uppercase(),
    DiaryDay(
        date = at("2026-09-15T23:00:00Z"), open = listOf(Span(at("2026-09-16T08:00:00Z"), at("2026-09-16T17:00:00Z"))),
        rows = rows, breaks = breaks, bookedCount = rows.count { it is DiaryAppointment }, freeMinutes = 0.0,
        isClosed = false, slotIntervalMinutes = 15,
    ),
)

private fun booking(id: String, start: String, end: String) = DiaryAppointment(
    id = id, bookingId = "b-$id", startsAt = at(start), endsAt = at(end), minutes = 30, status = "confirmed",
    clientName = "Client $id", clientVisitCount = 3, serviceName = "Skin fade",
)

private fun weekDay(booked: Int = 0, working: Int = 0, off: Int = 0) = WeekDay(
    isoDate = "2026-09-16", date = at("2026-09-16T11:00:00Z"), isOpen = true, booked = booked,
    freeMinutes = 0.0, openMinutes = 0.0, utilisation = 0.0, working = working, off = off,
)

/** Time off, decoded from chairtime's own responses. */
class TimeOffContractTest {
    private val list = ChairtimeJson.decodeFromString(TimeOffResponse.serializer(), Fixtures.text("time-off"))
    private val check = ChairtimeJson.decodeFromString(TimeOffCheck.serializer(), Fixtures.text("time-off-check"))

    @Test fun `the list decodes, whole days apart from blocked hours`() {
        assertTrue(list.mayChooseWho)
        assertEquals(5, list.people.size)
        assertEquals(366, list.maxDays)
        assertTrue(list.choosesWho)

        val days = list.comingUp.single()
        assertEquals("holiday", days.kind)
        assertEquals("2028-04-03", days.from); assertEquals("2028-04-05", days.to)
        assertEquals(3, days.days)
        assertTrue(days.everyone)
        assertNull(days.fromTime); assertNull(days.blockId)
        assertEquals("e220b3ce-91ff-43a7-9ece-228e158394f6", days.seriesId)

        val hour = list.blockedHours.single()
        assertFalse(hour.wholeDay)
        assertEquals("13:00", hour.fromTime); assertEquals("14:00", hour.toTime)
        assertEquals("Kit", hour.people.single().firstName)
        assertNull(hour.seriesId)
        assertEquals("7511e1aa-47f5-4305-b8fe-0cb05ef14fda", hour.blockId)
    }

    @Test fun `what is in the way decodes`() {
        assertEquals(3, check.days)
        val booking = check.inside.single()
        assertEquals(at("2028-04-18T11:00:00Z"), booking.startsAt)
        assertEquals("Kit Alvarez", booking.staffName)
        assertEquals("Clean shave", booking.serviceName)
        assertFalse(check.several)
    }

    @Test fun `the diary counts who is working and who is off`() {
        val diary = ChairtimeJson.decodeFromString(DiaryResponse.serializer(), Fixtures.text("diary"))
        assertEquals(listOf(0, 5, 5, 5, 5, 5, 0), diary.week.map { it.working })
        assertTrue(diary.week.none { it.isTimeOff })
    }

    /** A diary kept from before the server said so still opens, with nothing off. */
    @Test fun `a block and a week day decode without the new fields, and with them`() {
        val old = ChairtimeJson.decodeFromString(DiaryBreak.serializer(), """{"id":"b1","kind":"break","startsAt":"2026-09-16T12:00:00.000Z","endsAt":"2026-09-16T13:00:00.000Z"}""")
        assertFalse(old.allDay)
        val day = ChairtimeJson.decodeFromString(DiaryBreak.serializer(), """{"id":"b2","kind":"holiday","startsAt":"2026-09-16T08:00:00.000Z","endsAt":"2026-09-16T17:00:00.000Z","note":"Lisbon","allDay":true}""")
        assertTrue(day.allDay)
        assertEquals("Lisbon", day.label)

        val before = ChairtimeJson.decodeFromString(WeekDay.serializer(), """{"isoDate":"2026-09-16","date":"2026-09-16T11:00:00.000Z","isOpen":true,"booked":2,"freeMinutes":0,"openMinutes":480,"utilisation":0.5}""")
        assertEquals(0, before.working); assertEquals(0, before.off)
        assertFalse(before.isTimeOff)
        assertEquals("2 booked", before.bookedLabel)
    }

    @Test fun `a request carries who and the days, and the note only when there is one`() {
        val draft = TimeOffDraft(TimeOffDraft.EVERYONE, "2028-04-03", "2028-04-05", "  ")
        assertEquals("""{"who":"everyone","from":"2028-04-03","to":"2028-04-05"}""", ChairtimeJson.encodeToString(TimeOffWrite.serializer(), draft.write()))
        assertEquals("Lisbon", draft.copy(note = " Lisbon ").write().note)
    }
}

class TimeOffWordsTest {
    private val clock = ShopClock("Europe/London")

    private fun entry(from: String, to: String = from, note: String? = null, kind: String = "holiday", people: List<String> = listOf("Sam Reilly"), everyone: Boolean = false, wholeDay: Boolean = true) =
        TimeOffEntry(
            key = "k", kind = kind, note = note, wholeDay = wholeDay, from = from, to = to,
            fromTime = if (wholeDay) null else "13:00", toTime = if (wholeDay) null else "14:00",
            people = people.mapIndexed { i, name -> TimeOffPerson("p$i", name) }, everyone = everyone,
            seriesId = if (wholeDay) "s1" else null, blockId = if (wholeDay) null else "b1",
        )

    @Test fun `a day says its year only when it is not this one`() {
        assertEquals("Wed 3 Feb", TimeOffWords.day("2027-02-03", 2027))
        assertEquals("Wed 3 Feb 2027", TimeOffWords.day("2027-02-03", 2026))
        assertEquals("Wed 16 Sept", TimeOffWords.day("2026-09-16", 2026))
        assertEquals("not a date", TimeOffWords.day("not a date", 2026))
    }

    @Test fun `a run is its two ends, and one day is one`() {
        assertEquals("Wed 3 Feb", TimeOffWords.range("2027-02-03", "2027-02-03", 2027))
        assertEquals("Wed 3 Feb – Thu 4 Feb", TimeOffWords.range("2027-02-03", "2027-02-04", 2027))
        assertEquals("Tue 22 Dec – Sat 2 Jan 2027", TimeOffWords.range("2026-12-22", "2027-01-02", 2026))
        assertEquals(12, TimeOffWords.days("2026-12-22", "2027-01-02"))
        assertEquals(1, TimeOffWords.days("2027-02-03", "2027-02-03"))
    }

    @Test fun `hours say the day and the times`() {
        assertEquals("Wed 3 Feb, 13:00–14:00", TimeOffWords.whenLine(entry("2027-02-03", wholeDay = false), 2027))
        assertEquals("Wed 3 Feb – Thu 4 Feb", TimeOffWords.whenLine(entry("2027-02-03", "2027-02-04"), 2027))
    }

    @Test fun `the second line is the note or the kind, who when there is a choice, and how long`() {
        assertEquals("Holiday", TimeOffWords.whatLine(entry("2027-02-03"), showWho = false))
        assertEquals("Holiday · Sam", TimeOffWords.whatLine(entry("2027-02-03"), showWho = true))
        assertEquals("Lisbon · Sam, Kit · 2 days", TimeOffWords.whatLine(entry("2027-02-03", "2027-02-04", note = "Lisbon", people = listOf("Sam Reilly", "Kit Alvarez")), showWho = true))
        assertEquals("Christmas · Everyone · 12 days", TimeOffWords.whatLine(entry("2026-12-22", "2027-01-02", note = "Christmas", people = listOf("Sam Reilly", "Kit Alvarez"), everyone = true), showWho = true))
        assertEquals("Christmas · 12 days", TimeOffWords.whatLine(entry("2026-12-22", "2027-01-02", note = "Christmas"), showWho = false))
        assertEquals("Admin", TimeOffWords.whatLine(entry("2027-02-03", kind = "admin", wholeDay = false), showWho = false))
        assertEquals("Break", TimeOffWords.whatLine(entry("2027-02-03", kind = "break", note = " ", wholeDay = false), showWho = false))
    }

    @Test fun `bookings still inside are counted, and none says nothing`() {
        assertNull(TimeOffWords.insideNote(0))
        assertEquals("1 booking is still in these days. It stays until you move or cancel it.", TimeOffWords.insideNote(1))
        assertEquals("3 bookings are still in these days. They stay until you move or cancel them.", TimeOffWords.insideNote(3))
    }

    @Test fun `taking it back is worded for days, a day, or a block`() {
        val days = entry("2027-02-03", "2027-02-04"); val day = entry("2027-02-03"); val hour = entry("2027-02-03", wholeDay = false)
        assertEquals("Remove these days", TimeOffWords.removeConfirm(days))
        assertEquals("Remove this day", TimeOffWords.removeConfirm(day))
        assertEquals("Remove this block", TimeOffWords.removeConfirm(hour))
        assertEquals("Clients will be able to book these days again, in the working hours as they are set.", TimeOffWords.removeMessage(days))
        assertEquals("Clients will be able to book this day again, in the working hours as they are set.", TimeOffWords.removeMessage(day))
        assertEquals("Clients will be able to book this time again.", TimeOffWords.removeMessage(hour))
    }

    /** One person's line names them, so whoever shares the request keeps theirs. */
    @Test fun `one person's days are taken back for them, and a shared line for all of it`() {
        assertEquals("p0", entry("2027-02-03").staffIdToRemove)
        assertNull(entry("2027-02-03", people = listOf("Sam Reilly", "Kit Alvarez")).staffIdToRemove)
        // A shop of one: the only person is everyone, and the request is theirs whole.
        assertNull(entry("2027-02-03", everyone = true).staffIdToRemove)
    }

    @Test fun `what is in the way is said before anything is saved`() {
        assertEquals("There is a booking in these days already", TimeOffWords.checkHeading(1))
        assertEquals("There are 4 bookings in these days already", TimeOffWords.checkHeading(4))
        assertEquals("Nothing has been saved yet. Taking the time off does not move or cancel it, and the client is not told — that is yours to do, from the diary.", TimeOffWords.checkBody(1))
        assertEquals("Nothing has been saved yet. Taking the time off does not move or cancel them, and the clients are not told — that is yours to do, from the diary.", TimeOffWords.checkBody(4))

        val year = java.time.LocalDate.now().year
        val booking = TimeOffCheck.Inside("a1", at("$year-02-03T09:00:00Z"), "s1", "Sam Reilly", "Mina Okafor", "Root tint")
        val day = TimeOffWords.day("$year-02-03", year)
        assertEquals("$day, 09:00 · Mina Okafor · Root tint", TimeOffWords.insideLine(booking, clock, several = false))
        assertEquals("$day, 09:00 · Walk-in · Root tint · with Sam", TimeOffWords.insideLine(booking.copy(clientName = null), clock, several = true))

        assertNull(TimeOffWords.moreLine(8, "$year-02-03"))
        assertEquals("And 3 more, in the diary from $day.", TimeOffWords.moreLine(11, "$year-02-03"))
        assertTrue(TimeOffCheck(inside = listOf(booking, booking.copy(id = "a2", staffId = "s2"))).several)
    }

    @Test fun `the last day follows a later first day, and is never before it`() {
        val draft = TimeOffDraft("p1", "2027-02-03", "2027-02-03")
        assertEquals("2027-02-10", draft.withFrom("2027-02-10").to)
        assertEquals("2027-02-05", draft.withTo("2027-02-05").withFrom("2027-02-04").to)
        assertEquals("2027-02-03", draft.withTo("2027-02-01").to)
    }
}

/** Blocked time in the day's list, and a day off in the week. */
class BlockedTimeTest {
    private val clock = ShopClock("Europe/London")
    // 16 September is in summer time: 08:00Z is 09:00 in the shop.
    private val dayOff = block("d", "2026-09-16T08:00:00Z", "2026-09-16T17:00:00Z", allDay = true, note = "Lisbon")
    private val lunch = block("l", "2026-09-16T12:00:00Z", "2026-09-16T13:00:00Z", kind = "break")

    @Test fun `the same whole day across the team is one line, and an hour stays one person's`() {
        val shut = { id: String -> dayOff.copy(id = id, note = "Christmas") }
        val folded = Agenda.foldBlocks(listOf(
            Triple(shut("1"), "Ade Balogun", "a"), Triple(lunch, "Ade Balogun", "a"),
            Triple(shut("2"), "Kit Alvarez", "k"), Triple(lunch.copy(id = "l2"), "Kit Alvarez", "k"),
            Triple(dayOff, "Sam Reilly", "s"),
        ))
        assertEquals(4, folded.size)
        val shop = folded.first { it.block.note == "Christmas" }
        assertEquals(listOf("Ade Balogun", "Kit Alvarez"), shop.who)
        assertEquals(listOf("a", "k"), shop.staffIds)
        assertTrue(shop.isFolded)
        assertEquals("All day · Ade, Kit", BlockWords.detail(shop, clock, showWho = false))
        assertEquals(2, folded.count { !it.block.allDay && !it.isFolded })
        assertFalse(folded.first { it.block.note == "Lisbon" }.isFolded)
    }

    /** Found on the emulator, 23 November 2026: "The shop is off this day" over a line naming three of five. */
    @Test fun `a shared day off is the shop's only when everybody is off, and names who otherwise`() {
        assertEquals("The shop is off this day", BlockWords.sharedTitle(listOf("Ade Balogun", "Kit Alvarez"), isEverybody = true))
        assertEquals("Ade and Kit are off this day", BlockWords.sharedTitle(listOf("Ade Balogun", "Kit Alvarez"), isEverybody = false))
        assertEquals("Ade, Kit and Sam are off this day", BlockWords.sharedTitle(listOf("Ade Balogun", "Kit Alvarez", "Sam Reilly"), isEverybody = false))
        assertEquals("4 people are off this day", BlockWords.sharedTitle(listOf("Ade Balogun", "Kit Alvarez", "Sam Reilly", "Tomas Novak"), isEverybody = false))
    }

    @Test fun `where a shared day off is taken back is said only to somebody who may`() {
        assertEquals("To take days back, open Shop, then Time off.", BlockWords.sharedSays(isOwner = true, isOff = false))
        assertEquals("To take days back, open Shop, then Time off.", BlockWords.sharedSays(isOwner = true, isOff = true))
        val own = BlockWords.sharedSays(isOwner = false, isOff = true)
        assertTrue(own.startsWith("To take your own day back, open Shop, then Time off."))
        assertTrue(own.contains("Only the owner"))
        val others = BlockWords.sharedSays(isOwner = false, isOff = false)
        assertEquals("Only the owner of the shop can take these days back.", others)
        assertFalse(others.contains("open Shop"))
        assertEquals("How to take it back", BlockWords.sharedTapLabel(isOwner = false, isOff = true))
        assertEquals("Who can take it back", BlockWords.sharedTapLabel(isOwner = false, isOff = false))
    }

    @Test fun `a whole day off is above everything, and an hour falls where it is`() {
        val early = booking("1", "2026-09-16T08:00:00Z", "2026-09-16T08:30:00Z")
        val late = booking("2", "2026-09-16T14:00:00Z", "2026-09-16T14:30:00Z")
        val items = Agenda.items(listOf(
            member("a", "Ade Balogun", rows = listOf(early, late), breaks = listOf(lunch)),
            member("s", "Sam Reilly", breaks = listOf(dayOff)),
        ))
        assertEquals(listOf("block-d", "1", "block-l", "2"), items.map { it.id })
    }

    @Test fun `a day with only blocked time has lines to show`() {
        val summary = DaySummary(listOf(member("s", "Sam Reilly", breaks = listOf(dayOff))))
        assertEquals(0, summary.booked); assertEquals(1, summary.blocks)
        assertTrue(summary.hasLines)
        assertFalse(DaySummary(listOf(member("s", "Sam Reilly"))).hasLines)
    }

    @Test fun `a line says when, and whose where there is more than one person`() {
        val mine = AgendaItem.Blocked(lunch, listOf("Sam Reilly"), listOf("s"))
        assertEquals("13:00–14:00", BlockWords.detail(mine, clock, showWho = false))
        assertEquals("13:00–14:00 · Sam", BlockWords.detail(mine, clock, showWho = true))
        assertEquals("All day", BlockWords.detail(AgendaItem.Blocked(dayOff, listOf("Sam Reilly"), listOf("s")), clock, showWho = false))
        assertEquals("Break · 13:00", BlockWords.gridLabel(lunch, clock))
        assertEquals("Lisbon · All day", BlockWords.gridLabel(dayOff, clock))
        assertEquals("Break · 13:00–14:00", BlockWords.dayGridLabel(lunch, clock))
        assertEquals("Lisbon · All day", BlockWords.dayGridLabel(dayOff, clock))
    }

    @Test fun `a day is taken back, an hour is removed`() {
        assertEquals("Take this day back?", BlockWords.removeTitle(dayOff, clock))
        assertEquals("The day goes back to being bookable, in the working hours as they are set.", BlockWords.removeMessage(dayOff))
        assertEquals("Take it back", BlockWords.removeConfirm(dayOff))
        assertEquals("Remove break at 13:00?", BlockWords.removeTitle(lunch, clock))
        assertEquals("The time goes back to being bookable.", BlockWords.removeMessage(lunch))
        assertEquals("Remove it", BlockWords.removeConfirm(lunch))
    }

    @Test fun `a day everybody working has off is time off, and one person off is counted`() {
        assertTrue(weekDay(working = 3, off = 3).isTimeOff)
        assertFalse(weekDay(working = 3, off = 2).isTimeOff)
        // Nobody rostered is a closed day, not time off.
        assertFalse(weekDay(working = 0, off = 0).isTimeOff)
        assertEquals("Time off", weekDay(working = 1, off = 1).bookedLabel)
        assertEquals("Time off · 2 booked", weekDay(booked = 2, working = 1, off = 1).bookedLabel)
        assertEquals("4 booked · 1 off", weekDay(booked = 4, working = 3, off = 1).bookedLabel)
        assertEquals("4 booked", weekDay(booked = 4, working = 3).bookedLabel)
    }
}
