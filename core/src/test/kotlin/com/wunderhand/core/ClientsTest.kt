package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClientsTest {
    private val clock = ShopClock("Europe/London")
    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, name: String) = ChairtimeJson.decodeFromString(serializer, Fixtures.text(name))

    @Test fun `the list decodes from chairtime's own response`() {
        val list = decode(ClientsResponse.serializer(), "clients")
        assertTrue(list.clients.isNotEmpty())
        assertEquals(200, list.limit)
        assertTrue(list.count(ClientFilter.All) >= list.clients.size)
        assertTrue(ClientFilter.entries.all { it.raw in list.counts })
    }

    @Test fun `a profile decodes`() {
        val profile = decode(ClientProfileResponse.serializer(), "client")
        assertEquals("6/0 + 20vol, 35 min", profile.client.standingFormula)
        assertEquals("New client", profile.status(clock).text)
    }

    @Test fun `medical notes decode with their log`() {
        val notes = decode(HealthResponse.serializer(), "health")
        assertTrue(notes.configured)
        assertEquals("Allergies", notes.fields.first().label)
        assertTrue("PPD" in notes.value("allergies"))
        assertEquals("", notes.value("skinConditions"))
        assertTrue(notes.hasContent)
        assertEquals("opened medical notes", notes.accessLog.first().what)
    }

    @Test fun initials() {
        assertEquals("MJ", initialsOf("Mary Jane Watson"))
        assertEquals("K", initialsOf("Kit"))
        assertEquals("EW", initialsOf("ellis warner"))
        assertEquals("", initialsOf("  "))
    }

    @Test fun `a row says Booked before Due, and money only to those sent it`() {
        fun row(next: Instant?, overdue: Boolean, visits: Int = 3, spend: Int? = 0) =
            ClientRow("c", "Ellis Warner", visitCount = visits, spendPence = spend?.let(::Pence), nextAppointmentAt = next, overdue = overdue)
        assertEquals("Booked", row(Instant.now(), true).tag)
        assertEquals("Due", row(null, true).tag)
        assertNull(row(null, false).tag)
        assertEquals("1 visit", row(null, false, 1, 0).visitsAndSpend("GBP"))
        assertEquals("7 visits · £56", row(null, false, 7, 5600).visitsAndSpend("GBP"))
        assertEquals("7 visits", row(null, false, 7, null).visitsAndSpend("GBP"))
    }

    @Test fun `the profile's eyebrow follows the web's order`() {
        val base = decode(ClientProfileResponse.serializer(), "client")
        fun profile(next: Instant?, due: Boolean, regular: Boolean, visits: Int) =
            base.copy(client = base.client.copy(nextAppointmentAt = next, visitCount = visits), due = due, regular = regular)
        val october = Instant.parse("2026-10-14T10:15:00Z")
        assertEquals("Booked in 14 Oct", profile(october, true, true, 9).status(clock).text)
        assertEquals(ClientProfileResponse.Status("Due a rebook", true), profile(null, true, true, 9).status(clock))
        assertEquals("Regular", profile(null, false, true, 9).status(clock).text)
        assertEquals("Client", profile(null, false, false, 2).status(clock).text)
    }

    @Test fun `history splits into what is coming, soonest first, and what happened`() {
        val now = Instant.parse("2026-09-16T12:00:00Z")
        fun visit(id: String, at: String) = ClientProfileResponse.Visit(id, Instant.parse(at), "Skin fade", null, "confirmed")
        val base = decode(ClientProfileResponse.serializer(), "client")
        // chairtime sends history newest first.
        val profile = base.copy(history = listOf(visit("a", "2026-10-20T09:00:00Z"), visit("b", "2026-09-30T09:00:00Z"), visit("c", "2026-09-01T09:00:00Z"), visit("d", "2026-08-01T09:00:00Z")))
        assertEquals(listOf("b", "a"), profile.upcoming(now).map { it.id })
        assertEquals(listOf("c", "d"), profile.past(now).map { it.id })
    }

    @Test fun `a visit that did not happen is crossed through and says why`() {
        fun visit(status: String) = ClientProfileResponse.Visit("v", Instant.EPOCH, "Skin fade", null, status)
        assertTrue(visit("no_show").isMissed); assertEquals("No-show", visit("no_show").tag)
        assertTrue(visit("cancelled").isMissed)
        assertFalse(visit("completed").isMissed); assertNull(visit("completed").tag)
        assertNull(visit("a_status_from_the_future").tag)
    }

    @Test fun `the form starts from what is on file`() {
        val profile = decode(ClientProfileResponse.serializer(), "client").client
        val input = ClientInput(profile)
        assertEquals(profile.name, input.name)
        assertEquals(profile.email.orEmpty(), input.email)
        assertEquals("6/0 + 20vol, 35 min", input.standingFormula)
    }

    @Test fun `the words count people, and do not make the capped look missing`() {
        assertEquals("1 person", ClientWords.people(1))
        assertEquals("17 people", ClientWords.people(17))
        assertTrue("everybody is still here" in ClientWords.capped(200))
    }
}

class NotesLockTest {
    private val t0 = Instant.parse("2026-09-16T12:00:00Z")

    @Test fun `starts locked`() = assertFalse(NotesLock().isUnlocked(t0))

    @Test fun `an unlock lasts two minutes, and no longer`() {
        val lock = NotesLock().unlocked(t0)
        assertTrue(lock.isUnlocked(t0))
        assertTrue(lock.isUnlocked(t0.plusSeconds(119)))
        assertFalse(lock.isUnlocked(t0.plusSeconds(120)))
    }

    @Test fun `leaving the app locks at once`() = assertFalse(NotesLock().unlocked(t0).locked().isUnlocked(t0.plusSeconds(1)))

    @Test fun `a clock moved back is not a way in`() = assertFalse(NotesLock().unlocked(t0).isUnlocked(t0.minusSeconds(30)))

    @Test fun `it says what this phone uses`() {
        assertEquals("Unlock with your fingerprint or face", UnlockWords.button(UnlockWords.Method.Biometric))
        assertEquals("Unlock with your screen lock", UnlockWords.button(UnlockWords.Method.ScreenLock))
        assertTrue(UnlockWords.locked("Wren Halloway", UnlockWords.Method.ScreenLock).startsWith("Wren’s notes open with your screen lock,"))
        assertEquals("Open Wren Halloway’s medical notes", UnlockWords.reason("Wren Halloway"))
    }
}

class ContactFillTest {
    private val wren = PickedContact(
        givenName = "Wren", familyName = "Halloway",
        phones = listOf(PickedContact.Labelled("home", "01632 960000"), PickedContact.Labelled("mobile", " 07700 900123 ")),
        emails = listOf(PickedContact.Labelled("work", "wren@studio.example"), PickedContact.Labelled("home", "wren@example.com")),
        birthday = PickedContact.Birthday(1991, 3, 4),
    )

    @Test fun `takes the name, the mobile, their own email and the date`() {
        val filled = ContactFill.apply(wren, ClientInput())
        assertEquals(ClientInput("Wren Halloway", "07700 900123", "wren@example.com", "1991-03-04"), filled.input)
        assertEquals(listOf("name", "mobile", "email", "date of birth"), filled.what)
        assertEquals("Filled the name, mobile, email and date of birth from your contacts. Check them before adding.", ContactFill.said(filled.what))
    }

    @Test fun `any number does when there is no mobile`() {
        assertEquals("01632 960000", ContactFill.phone(PickedContact(phones = listOf(PickedContact.Labelled(null, "01632 960000")))))
        assertNull(ContactFill.phone(PickedContact()))
    }

    @Test fun `a birthday without a year is left out`() {
        assertNull(ContactFill.dateOfBirth(PickedContact(birthday = PickedContact.Birthday(null, 3, 4))))
        assertNull(ContactFill.dateOfBirth(PickedContact(birthday = PickedContact.Birthday(1604, 3, 4))))
    }

    @Test fun `a contact with no name uses its nickname or company`() {
        assertEquals("Wee Jim", ContactFill.name(PickedContact(nickname = "Wee Jim")))
        assertEquals("Fold Barbers", ContactFill.name(PickedContact(organisation = "Fold Barbers")))
        assertNull(ContactFill.name(PickedContact(givenName = "  ")))
    }

    @Test fun `leaves what the contact does not have as it was typed`() {
        val typed = ClientInput(email = "typed@example.com", notes = "Parks round the back")
        val filled = ContactFill.apply(PickedContact(givenName = "Wren", phones = listOf(PickedContact.Labelled("mobile", "07700 900123"))), typed)
        assertEquals(listOf("name", "mobile"), filled.what)
        assertEquals("typed@example.com", filled.input.email)
        assertEquals("Parks round the back", filled.input.notes)
        assertEquals("Filled the name and mobile from your contacts. Check them before adding.", ContactFill.said(filled.what))
        assertEquals("That contact has no name, number or email to use.", ContactFill.said(emptyList()))
    }

    @Test fun `Android writes a birthday with or without its year`() {
        assertEquals(PickedContact.Birthday(1991, 3, 4), ContactFill.birthday("1991-03-04"))
        assertEquals(PickedContact.Birthday(null, 3, 4), ContactFill.birthday("--03-04"))
        assertNull(ContactFill.birthday("sometime in March"))
        assertNull(ContactFill.birthday(null))
    }
}
