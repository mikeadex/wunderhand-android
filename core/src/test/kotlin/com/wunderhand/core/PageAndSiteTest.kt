package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneId

/** Your page and Your website: the words the web uses, and what is sent. */
class PageAndSiteTest {
    private val page = ChairtimeJson.decodeFromString(PageResponse.serializer(), Fixtures.text("page"))
    private val site = ChairtimeJson.decodeFromString(SiteResponse.serializer(), Fixtures.text("site"))

    // Your page

    @Test fun `a trade reads as the web's chip does`() {
        assertEquals("Barber", PageWords.trade("barber"))
        assertEquals("Hair Removal", PageWords.trade("hair removal"))
    }

    @Test fun `a draft starts as the page is and sends every line`() {
        val draft = PageDraft(page)
        assertEquals("Skin fades and beard work in Hackney", draft.headline)
        assertEquals(setOf("barber", "colour"), draft.trades)
        assertEquals(
            PageWrite("Skin fades and beard work in Hackney", "Five chairs, walk-ins before noon.", null, null, null, listOf("barber", "colour")),
            draft.write(page.tradeChoices),
        )
    }

    @Test fun `an emptied line is left out, which clears it, and trades go in the page's order`() {
        val write = PageDraft(page).copy(headline = "  ", instagram = " foldbarbers ", trades = setOf("nails", "barber")).write(page.tradeChoices)
        assertNull(write.headline)
        assertEquals("foldbarbers", write.instagram)
        assertEquals(listOf("barber", "nails"), write.trades)
        // Left out of the JSON altogether: the server reads a missing line as cleared.
        assertEquals(false, ChairtimeJson.encodeToString(PageWrite.serializer(), write).contains("headline"))
    }

    @Test fun `a field stops at what the server takes, and a handle loses its at`() {
        val long = PageDraft(headline = "x".repeat(200), about = "y".repeat(3000), phone = "0".repeat(40), instagram = "@foldbarbers").within(page.limits)
        assertEquals(120, long.headline.length)
        assertEquals(2000, long.about.length)
        assertEquals(30, long.phone.length)
        assertEquals("foldbarbers", long.instagram)
    }

    @Test fun `the address switch is worded for one outlet or by name for several`() {
        val one = page.outlets.first()
        assertEquals("Keep Hackney Road’s street private", PageWords.addressLabel(one, 2))
        assertEquals("Keep my street address private until someone books", PageWords.addressLabel(one, 1))
        assertEquals("Off · strangers see 288 Hackney Road, London, E2 7SJ", PageWords.addressHint(one))
        assertEquals("On · strangers see Tower Hamlets, E2", PageWords.addressHint(one.copy(addressPrivate = true, shown = "Tower Hamlets, E2")))
        assertEquals(true, PageWords.addressSays(page.exampleArea).contains("only the area — Tower Hamlets, E2 —"))
    }

    @Test fun `what is worth adding depends on whether the page is live`() {
        assertNull(PageWords.worthAdding(page))
        val thin = page.copy(missing = listOf("a cover photograph", "some of your work"))
        assertEquals("You can publish now — worth adding: a cover photograph, some of your work.", PageWords.worthAdding(thin))
        assertEquals("Still worth adding: a cover photograph, some of your work.", PageWords.worthAdding(thin.copy(published = true)))
        assertEquals(PageWords.LINK_NOT_LIVE, PageWords.linkSays(false))
    }

    @Test fun `the work is counted in the singular and the plural`() {
        assertEquals("Your work — 1 photograph", PageWords.workTitle(1))
        assertEquals("Your work — 6 photographs", PageWords.workTitle(6))
    }

    // Your website

    @Test fun `a side's draft sends its categories whole and in the menu's order`() {
        val makeUp = site.sides.sides.last()
        val draft = SideDraft(makeUp).copy(line = "  ", categoryIds = site.sides.categories.map { it.id }.reversed().toSet() + "gone")
        val write = draft.write(site.sides)
        assertEquals("Make-up", write.name)
        assertNull(write.line)
        assertEquals(site.sides.categories.map { it.id }, write.categoryIds)
    }

    @Test fun `a side with no name is refused before it is sent`() {
        val problem = assertThrows(DraftProblem::class.java) { SideDraft(name = "  ").write(site.sides) }
        assertEquals("name", problem.field)
        assertEquals("A side needs a name", problem.message)
    }

    @Test fun `a side's name and line stop at the server's lengths`() {
        val draft = SideDraft(name = "n".repeat(60), line = "l".repeat(200)).within(site.sides)
        assertEquals(40, draft.name.length)
        assertEquals(120, draft.line.length)
        assertEquals("Up to 40 characters", SiteWords.nameHint(site.sides))
        assertEquals("4 sides is the most a site shows. Remove one to add another.", SiteWords.full(site.sides))
    }

    @Test fun `a category filed elsewhere says ticking it moves it`() {
        val (hair, makeUp) = site.sides.sides
        val bridal = site.sides.categories.single { it.sideId == makeUp.id }
        assertEquals("In Make-up — ticking it moves it here", SiteWords.elsewhere(bridal, hair, site.sides))
        assertNull(SiteWords.elsewhere(bridal, makeUp, site.sides))
        assertNull(SiteWords.elsewhere(site.sides.categories.first(), hair, site.sides))
        assertEquals("Make-up", SiteWords.sideExample(site.sides))
        assertEquals("Hair", SiteWords.sideExample(SiteResponse.Sides()))
    }

    @Test fun `a record says where its name really goes`() {
        val records = site.domains.single { it.isWaiting }.records
        assertEquals("(in full: _wunderhand.zz-site-api-test.co.uk)", SiteWords.nameNote(records.first()))
        assertEquals("(zz-site-api-test.co.uk itself)", SiteWords.nameNote(records[1]))
    }

    @Test fun `each state has its one button or none`() {
        fun domain(state: String) = SiteResponse.Domain("d", "example.com", state)
        assertEquals("Check the DNS now", SiteWords.button(domain("waiting")))
        assertEquals("Finish the connection", SiteWords.button(domain("finishing")))
        assertNull(SiteWords.button(domain("pointing")))
        assertNull(SiteWords.button(domain("live")))
        assertNull(SiteWords.button(domain("something new")))
    }

    @Test fun `the last check is said only while waiting and only once made`() {
        val london = ZoneId.of("Europe/London")
        val waiting = SiteResponse.Domain("d", "example.com", "waiting", checkedAt = "2026-11-14T09:05:00.000Z")
        assertEquals("Last checked 14 Nov, 09:05. Nothing found at that name yet.", SiteWords.lastChecked(waiting, london))
        assertEquals("Last checked 14 Nov, 09:05. What is there now: 1.2.3.4", SiteWords.lastChecked(waiting.copy(lastSeen = "1.2.3.4"), london))
        assertNull(SiteWords.lastChecked(waiting.copy(checkedAt = null), london))
        assertNull(SiteWords.lastChecked(waiting.copy(state = "pointing"), london))
    }

    // Pictures

    @Test fun `a picture the server calls localhost is fetched from where the app reaches the server`() {
        assertEquals("http://10.0.2.2:3100/site-designs/mila.jpg", ServerImage.reachable("http://localhost:3000/site-designs/mila.jpg", "http://10.0.2.2:3100"))
        assertEquals("http://10.0.2.2:3100/media/a%20b.webp?v=2", ServerImage.reachable("http://127.0.0.1:3000/media/a%20b.webp?v=2", "http://10.0.2.2:3100/"))
    }

    @Test fun `any other picture is left exactly as it came`() {
        val real = "https://images.wunderhand.com/shops/s1/cover/a.webp"
        assertEquals(real, ServerImage.reachable(real, "https://wunderhand.com"))
        assertEquals("https://wunderhand.com/site-designs/mila.jpg", ServerImage.reachable("https://wunderhand.com/site-designs/mila.jpg", "https://wunderhand.com"))
        assertNull(ServerImage.reachable(null, "https://wunderhand.com"))
        assertEquals("not a url at all", ServerImage.reachable("not a url at all", "https://wunderhand.com"))
    }
}
