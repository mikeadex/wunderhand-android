package com.wunderhand.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun <T> fixture(serializer: KSerializer<T>, name: String): T = ChairtimeJson.decodeFromString(serializer, Fixtures.text(name))

class MenuTest {
    @Test fun `the menu decodes, and a service reads as the web lists it`() {
        val menu = fixture(MenuResponse.serializer(), "menu")
        assertEquals("Barber", menu.categories.first().name)
        val fade = menu.services.first { it.name == "Skin fade" }
        assertEquals("£28", fade.priceLabel("GBP"))
        assertEquals("45m", fade.metaLine)
        assertFalse(fade.isUnbookable)
        assertTrue(menu.services(menu.categories.first().id).all { it.categoryName == "Barber" })
        assertTrue(menu.countLabel.endsWith(" services"))
    }

    @Test fun `a service's detail adds up its steps`() {
        val detail = fixture(MenuServiceResponse.serializer(), "menu-service")
        assertEquals(45, detail.totalMinutes); assertEquals(45, detail.busyMinutes); assertFalse(detail.sellsFreeTime)
        assertTrue(detail.summaryLine("GBP").startsWith("Barber · £28 · 45m · booked "))
        assertEquals("Cut", detail.segments.first().name)
        assertEquals(listOf("adds 15m", "adds 5m", "no extra time"), detail.addons.map { it.timeLabel })
        assertEquals("Chair", detail.service.resourceTypeName)
    }

    @Test fun `the line under a name says what makes it awkward to book`() {
        val tint = MenuService("s", "Root tint", pricingMode = "from", pricePence = Pence(4000), minutes = 75, hasDevelopGap = true,
            prerequisiteName = "Patch test", prerequisiteLeadHours = 48, minAgeYears = 16, requiresConsent = true)
        assertEquals("1h 15m · has a develop gap · Patch test 48h before · 16+ · consent required", tint.metaLine)
        assertEquals("from £40", tint.priceLabel("GBP"))
        assertTrue(tint.isUnbookable)
        val sitting = MenuService("s", "Tattoo sitting", pricingMode = "hourly", hourlyRatePence = Pence(9000), minutes = 300, durationMode = "ranged",
            minMinutes = 180, maxMinutes = 480, prerequisiteName = "Consultation", minAgeYears = 18, requiresConsent = true, performerCount = 1)
        assertEquals("3h–8h · Consultation first · 18+ · consent required", sitting.metaLine)
        assertEquals("£90/hr", sitting.priceLabel("GBP"))
    }

    @Test fun `how often, in words`() {
        assertEquals("never booked", MenuWords.booked(0)); assertEquals("booked once", MenuWords.booked(1)); assertEquals("booked 12 times", MenuWords.booked(12))
    }

    @Test fun `a service with a develop gap sells the pro's free time`() {
        val tint = fixture(MenuServiceResponse.serializer(), "menu-service-steps")
        assertEquals(listOf(1, 2, 3), tint.segments.map { it.seq })
        assertTrue(tint.sellsFreeTime); assertEquals(90, tint.totalMinutes); assertEquals(55, tint.busyMinutes)
        assertEquals(false, tint.service.isArchived)
    }

    @Test fun `options and extras decode`() {
        val options = fixture(MenuOptions.serializer(), "menu-options")
        assertTrue(options.staff.size > 1 && options.services.isNotEmpty())
        val made = fixture(ExtrasResponse.serializer(), "menu-extras").extras.first { it.offered }
        assertTrue(made.line("GBP").contains(" · "))
    }
}

class MoneyInputTest {
    @Test fun `money as somebody types it`() {
        assertEquals(2850, MoneyInput.pence("28.50")); assertEquals(2800, MoneyInput.pence("28")); assertEquals(120000, MoneyInput.pence("£1,200"))
        assertEquals(5, MoneyInput.pence("0.049999") ?: -1) // half up, to the penny
        assertEquals(0, MoneyInput.pence("  "))
        assertNull(MoneyInput.pence("twenty")); assertNull(MoneyInput.pence("-5"))
        assertEquals("32.00", MoneyInput.pounds(Pence(3200)))
    }
}

class ServiceDraftTest {
    private fun problem(draft: ServiceDraft) = assertThrows(DraftProblem::class.java) { draft.write() }

    @Test fun `what it cannot read, it says which field`() {
        problem(ServiceDraft()).let { assertEquals("name", it.field); assertEquals("A name is needed", it.message) }
        problem(ServiceDraft(name = "Skin fade", price = "lots")).let { assertEquals("pricePence", it.field); assertEquals("Enter an amount like 28.50", it.message) }
        problem(ServiceDraft(name = "Skin fade", minAgeYears = "sixteen")).let { assertEquals("minAgeYears", it.field) }
        problem(ServiceDraft(name = "Sitting", durationMode = DurationMode.Ranged, minMinutes = "480", maxMinutes = "180")).let { assertEquals("The longest cannot be shorter than the shortest.", it.message) }
    }

    @Test fun `a plain service goes out with the web's defaults, and without what was left blank`() {
        val write = ServiceDraft(name = " Skin fade ", price = "28.50").write()
        assertEquals("Skin fade", write.name); assertEquals(2850, write.pricePence)
        assertEquals(Triple("fixed", "fixed", "at_venue"), Triple(write.pricingMode, write.durationMode, write.locationMode))
        assertTrue(write.bookableOnline && !write.requiresConsent)
        val json = ChairtimeJson.encodeToJsonElement(ServiceWrite.serializer(), write).jsonObject
        assertTrue(listOf("depositPence", "newCategory", "minMinutes").none { it in json })
    }

    @Test fun `by the hour has a rate and no price, and only a range has a range`() {
        val sitting = ServiceDraft(name = "Sitting", pricingMode = PricingMode.Hourly, price = "50", hourlyRate = "90", durationMode = DurationMode.Ranged, minMinutes = "180", maxMinutes = "480").write()
        assertNull(sitting.pricePence); assertEquals(9000, sitting.hourlyRatePence); assertEquals(180 to 480, sitting.minMinutes to sitting.maxMinutes)
        val fixed = ServiceDraft(name = "Cut", minMinutes = "180", maxMinutes = "480").write()
        assertNull(fixed.minMinutes); assertNull(fixed.maxMinutes)
    }

    @Test fun `a category typed wins over one picked, and lead hours need a prerequisite`() {
        val typed = ServiceDraft(name = "Tint", categoryId = "c1", newCategory = " Colour ", prerequisiteLeadHours = "48").write()
        assertEquals("Colour", typed.newCategory); assertNull(typed.categoryId); assertNull(typed.prerequisiteLeadHours)
        val picked = ServiceDraft(name = "Tint", categoryId = "c1", prerequisiteServiceId = "s1", prerequisiteLeadHours = "48").write()
        assertEquals("c1", picked.categoryId); assertEquals(48, picked.prerequisiteLeadHours)
    }

    @Test fun `editing starts from what is on file, and saving it unchanged changes nothing`() {
        val tint = fixture(MenuServiceResponse.serializer(), "menu-service-steps")
        val write = ServiceDraft(tint.service).write()
        assertEquals(tint.service.name, write.name); assertEquals(tint.service.pricePence?.value, write.pricePence)
        assertEquals(tint.service.categoryId, write.categoryId); assertEquals(tint.service.bookableOnline, write.bookableOnline)
    }

    @Test fun `steps drop the rows not used, keep their order, and need at least one`() {
        val write = StepDraft.write(listOf(StepDraft("Apply", "20"), StepDraft("", ""), StepDraft("Develop", "35", staffBusy = false), StepDraft("Rinse", "0")))
        assertEquals(listOf("Apply" to 20, "Develop" to 35), write.steps.map { it.label to it.minutes })
        assertEquals("A service needs at least one block of time", assertThrows(DraftProblem::class.java) { StepDraft.write(listOf(StepDraft())) }.message)
        assertEquals("steps.0.minutes", assertThrows(DraftProblem::class.java) { StepDraft.write(listOf(StepDraft("Cut", "a while"))) }.field)
        assertEquals("Holds the chair for 55m, but only takes 20m of your time.", StepDraft.summary(listOf(StepDraft("Apply", "20"), StepDraft("Develop", "35", false))))
        assertNull(StepDraft.summary(listOf(StepDraft("Cut", "45"))))
    }

    @Test fun `the minutes a pro is free are the ones that can be sold`() {
        val rows = listOf(StepDraft("Apply", "20", true), StepDraft("Develop", "35", false), StepDraft("", "", true), StepDraft("Finish", "35", true))
        assertEquals("35m is sellable.", StepDraft.sellable(rows))
        assertNull(StepDraft.sellable(rows.filter { it.staffBusy }))
        assertEquals(listOf(1, 2, 4), StepDraft.preview(rows).map { it.seq })
    }
}

class ShopTest {
    @Test fun `the index says each subject in the web's words`() {
        val shop = fixture(ShopResponse.serializer(), "shop")
        assertTrue(Regex("""\d+ (person|people)""").matches(shop.teamValue))
        assertEquals("People, seats and who works where", shop.teamHint)
        assertEquals("15 min slots", shop.rules.summary)
        assertEquals("2h notice · 10 min buffer · books 12 weeks ahead", shop.rules.detail)
        assertEquals("£10", shop.policyValue("GBP"))
        assertEquals("Free to cancel 24h before · 50% no-show fee", shop.policyHint)
        assertEquals("Off" to "Nothing is emailed before an appointment", shop.remindersValue to shop.remindersHint)
        assertEquals("Set up", shop.paymentsValue); assertEquals("Set up", shop.siteValue)
    }

    /** The one rule behind the whole app: it sells nothing. */
    @Test fun `the plan row counts seats and outlets, and never names a price`() {
        val shop = fixture(ShopResponse.serializer(), "shop")
        assertTrue(Regex("""\d+ seats? · \d+ outlets?""").matches(shop.planValue))
        val said = listOf(shop.planValue, shop.teamHint, shop.outletsHint, TeamWords.SEATS, TeamWords.REMOVING, OutletWords.ON_THE_PLAN,
            TeamWords.login(TeamPersonResponse(TeamPersonResponse.Person("p", "Ade Balogun"), viewerIsOwner = true)).text)
        for (sentence in said) assertFalse(sentence, "£" in sentence || Regex("""\d+\.\d\d""").containsMatchIn(sentence))
    }

    @Test fun `hours, policy, reminders, team and outlets decode`() {
        val hours = fixture(HoursResponse.serializer(), "hours")
        assertEquals("Kit Alvarez", hours.person?.name); assertEquals("Hackney Road", hours.outlet?.name)
        assertTrue(hours.days.all { it.opensAt.length == 5 && it.closesAt > it.opensAt })
        val policy = fixture(PolicyResponse.serializer(), "policy")
        assertTrue(policy.defaultWording.startsWith("Free to cancel up to 24 hours before.")); assertTrue(policy.wordingVersion.startsWith("v"))
        val reminders = fixture(RemindersResponse.serializer(), "reminders")
        assertEquals(3, reminders.slots.size); assertEquals("Nothing has gone out yet.", reminders.recentLabel)
        val team = fixture(TeamResponse.serializer(), "team")
        val ade = team.people.first { it.name == "Ade Balogun" }
        assertEquals("Seat" to "Barber · chair renter", ade.statusLabel to ade.hint)
        val outlets = fixture(OutletsResponse.serializer(), "outlets")
        assertEquals("288 Hackney Road, London, E2 7SJ", outlets.outlets.first().addressLine)
        assertEquals("Clients come here", outlets.outlets.first().travelLabel)
        assertEquals(fixture(BookingRules.serializer(), "rules"), fixture(ShopResponse.serializer(), "shop").rules)
    }

    @Test fun `a reminder is said in the largest unit that divides it`() {
        for ((minutes, label) in listOf(15 to "15 minutes before", 60 to "1 hour before", 120 to "2 hours before", 1440 to "1 day before", 2880 to "2 days before", 10080 to "1 week before", 90 to "90 minutes before")) {
            assertEquals(label, ReminderWords.offsetLabel(minutes))
        }
        assertEquals(1 to ReminderWords.Unit.Days, ReminderWords.split(1440)); assertEquals(2 to ReminderWords.Unit.Hours, ReminderWords.split(120))
        assertEquals(90 to ReminderWords.Unit.Minutes, ReminderWords.split(90)); assertEquals(4320, ReminderWords.minutes(3, ReminderWords.Unit.Days))
        assertEquals("days before", ReminderWords.Unit.Days.label)
    }

    @Test fun `the week starts on Monday`() {
        assertEquals(listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"), HoursResponse.weekOrder.map(HoursResponse::dayName))
    }
}

class TeamWordsTest {
    private fun someone(status: String, hasLogin: Boolean, owner: Boolean = false, viewerIsOwner: Boolean = true, isYou: Boolean = false) =
        TeamPersonResponse(TeamPersonResponse.Person("p", "Ade Balogun", accountStatus = status, hasLogin = hasLogin, isOwner = owner),
            outletIds = listOf("o1"), outlets = listOf(TeamPersonResponse.Outlet("o1", "Hackney Road"), TeamPersonResponse.Outlet("o2", "Dalston Lane")), viewerIsOwner = viewerIsOwner, isYou = isYou)

    @Test fun `a person and an invitation decode`() {
        val person = fixture(TeamPersonResponse.serializer(), "team-person")
        assertEquals("No login" to "Employed", person.person.statusLabel to person.person.employmentLabel)
        assertTrue(person.viewerIsOwner && !person.isYou)
        val invited = fixture(InviteResponse.serializer(), "team-invited")
        assertEquals("invited", invited.outcome); assertEquals("Invited", invited.asPerson.person.statusLabel)
    }

    @Test fun `the login panel offers only what can happen`() {
        assertTrue(TeamWords.login(someone("none", false)).canInvite)
        assertEquals("Invite to log in", TeamWords.loginTitle(someone("none", false).person))
        assertEquals("Send it again", TeamWords.inviteButton(someone("invited", false).person))
        val active = someone("active", true)
        assertFalse(TeamWords.login(active).canInvite); assertEquals("Has a login", TeamWords.loginTitle(active.person))
        assertTrue(TeamWords.login(active).text.startsWith("Ade can sign in already."))
        assertEquals(TeamWords.Login("Only an owner can invite somebody to log in.", false), TeamWords.login(someone("none", false, viewerIsOwner = false)))
        val back = someone("suspended", true)
        assertTrue(TeamWords.login(back).canInvite); assertEquals("Give them their login back", TeamWords.inviteButton(back.person))
    }

    @Test fun `who sees the money is said about them, or about you`() {
        val renter = someone("active", true)
        assertEquals("Ade sees only their own takings. Owners see the whole shop.", TeamWords.money(renter))
        assertEquals("Let Ade Balogun see the shop’s money", TeamWords.moneyButton(renter))
        val diaryOnly = someone("none", false)
        assertNull(TeamWords.moneyButton(diaryOnly)); assertTrue(TeamWords.needsLoginFirst(diaryOnly.person).startsWith("Invite Ade Balogun to log in first."))
        val me = someone("active", true, owner = true, isYou = true)
        assertEquals("You can see everyone’s takings on the Money tab.", TeamWords.money(me)); assertEquals("Stop seeing the shop’s money", TeamWords.moneyButton(me))
        assertEquals("Hackney Road", someone("none", false).worksAt)
        assertEquals(listOf("Employed", "Rents a chair", "Apprentice", "Office"), Employment.entries.map { it.label })
    }

    @Test fun `a status or a job this build has not heard of is shown as it came`() {
        assertEquals("on_leave", accountStatusLabel("on_leave"))
        assertEquals("contractor", TeamPersonResponse.Person("p", "X", employment = "contractor").employmentLabel)
    }
}

class OutletDraftTest {
    private fun problem(draft: OutletDraft) = assertThrows(DraftProblem::class.java) { draft.write() }

    @Test fun `an outlet that travels decodes with its ladder, and saving it unchanged sends it back`() {
        val r = fixture(OutletResponse.serializer(), "outlet")
        assertTrue(r.outlet.addressPrivate); assertEquals(15, r.outlet.servesRadiusMiles); assertEquals(listOf(3, 8, 15), r.travelBands.map { it.upToMiles })
        assertEquals("Free up to 3 miles · £5 up to 8 miles · £10 up to 15 miles", r.ladder("GBP"))
        val write = OutletDraft(r).write()
        assertEquals(r.outlet.name, write.name); assertEquals(15, write.servesRadiusMiles)
        assertEquals(listOf(0, 500, 1000), write.travelBands.map { it.feePence })
    }

    @Test fun `bands are sorted, a blank row is no band, and a blank fee is free`() {
        val write = OutletDraft(name = "Hackney Road", bands = listOf(OutletDraft.BandDraft("8", "5"), OutletDraft.BandDraft("", ""), OutletDraft.BandDraft("3", ""))).write()
        assertNull(write.servesRadiusMiles); assertEquals(30, write.defaultTravelMinutes); assertEquals("Europe/London", write.timezone)
        assertEquals(listOf(3 to 0, 8 to 500), write.travelBands.map { it.upToMiles to it.feePence })
    }

    @Test fun `what it cannot read, it says which row`() {
        assertEquals("name", problem(OutletDraft()).field)
        assertEquals("Two travel bands cannot end at the same distance", problem(OutletDraft(name = "A", bands = listOf(OutletDraft.BandDraft("3"), OutletDraft.BandDraft("3")))).message)
        assertEquals("band.0.miles", problem(OutletDraft(name = "A", bands = listOf(OutletDraft.BandDraft("far")))).field)
        assertEquals("band.0.fee", problem(OutletDraft(name = "A", bands = listOf(OutletDraft.BandDraft("3", "5000")))).field)
    }

    @Test fun `an outlet somewhere the form does not list keeps its own timezone`() {
        assertEquals(listOf("United Kingdom", "Ireland", "France, Spain, Germany", "Portugal"), OutletDraft().timezoneChoices.map { it.second })
        assertEquals("America/New York", OutletDraft(timezone = "America/New_York").timezoneChoices.last().second)
    }
}

class LeavingTest {
    private val clock = ShopClock("Europe/London")

    @Test fun `what is still booked is said, and that closing tells nobody`() {
        assertEquals("Nothing is booked ahead.", LeavingWords.upcoming(0))
        assertTrue(LeavingWords.upcoming(1).let { it.startsWith("1 appointment is booked ahead.") && "does not tell that client" in it })
        assertTrue(LeavingWords.upcoming(7).let { it.startsWith("7 appointments are booked ahead.") && "does not tell those clients" in it })
        assertTrue(LeavingWords.recordsGo(30).let { "deleted 30 days later" in it && "medical notes" in it })
        val on = Instant.ofEpochSecond(1_789_655_520)
        assertTrue(clock.longDate(on) in LeavingWords.recordsGo(on, clock))
    }

    @Test fun `what a login leaves behind counts the shops`() {
        assertEquals("Your login is gone.", LeavingWords.leftBehind(0))
        assertTrue(LeavingWords.leftBehind(1).endsWith("off the team at that shop."))
        assertTrue("at 3 shops" in LeavingWords.leftBehind(3))
    }

    @Test fun `who it waits on reads as a sentence`() {
        fun p(name: String) = ShopClosure.Partner(name, name)
        assertEquals("Everybody has agreed.", LeavingWords.waitingOn(emptyList()))
        assertEquals("Waiting on Marek Sowa.", LeavingWords.waitingOn(listOf(p("Marek Sowa"))))
        assertEquals("Waiting on Marek Sowa, Priya Raval and Joss Adeyemi.", LeavingWords.waitingOn(listOf(p("Marek Sowa"), p("Priya Raval"), p("Joss Adeyemi"))))
    }

    @Test fun `a request waiting on others decodes`() {
        val json = """{"id":"c1","requestedBy":{"staffId":"s1","name":"Joss Adeyemi"},"requestedAt":"2026-09-18T09:45:28.539Z","expiresAt":"2026-10-02T09:45:28.516Z",
            "partners":[{"staffId":"s2","name":"Marek Sowa","agreed":true},{"staffId":"s3","name":"Priya Raval","agreed":false}],"mine":"partner"}"""
        val closure = ChairtimeJson.decodeFromString(ShopClosure.serializer(), json)
        assertEquals(ShopClosure.Standing.Partner, closure.mine); assertEquals(listOf("Priya Raval"), closure.waitingOn.map { it.name })
    }

    /** chairtime may add a word for what somebody is to a request. An app that does not know it must offer no button, not guess. */
    @Test fun `a standing this build has not heard of is nobody's to act on`() {
        val json = """{"id":"c1","requestedBy":{"staffId":"s1","name":"Joss"},"requestedAt":"2026-09-18T09:45:28.539Z","expiresAt":"2026-10-02T09:45:28.516Z","partners":[],"mine":"something-new"}"""
        assertEquals(ShopClosure.Standing.None, ChairtimeJson.decodeFromString(ShopClosure.serializer(), json).mine)
    }

    @Test fun `asking can come back with a request rather than a closed shop`() {
        val json = """{"closed":false,"closure":{"id":"c1","requestedBy":{"staffId":"s1","name":"Joss"},"requestedAt":"2026-09-18T09:45:28.539Z","expiresAt":"2026-10-02T09:45:28.516Z","partners":[{"staffId":"s2","name":"Marek","agreed":false}],"mine":"requester"}}"""
        val result = ChairtimeJson.decodeFromString(ShopCloseResult.serializer(), json)
        assertFalse(result.closed); assertNull(result.deleteAfter); assertEquals(ShopClosure.Standing.Requester, result.closure?.mine)
    }

    /** Before chairtime #20 the close endpoint said nothing about requests. A build that cannot read that answer shows an error on a screen that was working the day before. */
    @Test fun `a reply older than this build, without the request fields, still reads`() {
        val closing = ChairtimeJson.decodeFromString(ShopClosing.serializer(), """{"slug":"fold-barbers","status":"active","graceDays":30,"deleteAfter":null,"upcoming":3}""")
        assertEquals(3, closing.upcoming); assertNull(closing.closure); assertNull(closing.requestDays); assertFalse(closing.isClosed)
        assertTrue(ChairtimeJson.decodeFromString(ShopClosing.serializer(), """{"slug":"fold-barbers","status":"closed","graceDays":30,"requestDays":14,"deleteAfter":"2026-10-18T09:00:00.000Z","upcoming":2,"closure":null}""").isClosed)
    }
}
