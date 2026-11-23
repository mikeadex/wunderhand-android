package com.wunderhand.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API's promises, read from the fixtures chairtime writes from its own
 * real responses (scripts/sync-api-fixtures.sh). A rename on either side
 * fails here first. The iOS app's tests read the same files.
 */
class ContractTest {
    @Test fun `every fixture is here and is JSON`() {
        for (name in Fixtures.names) {
            ChairtimeJson.parseToJsonElement(Fixtures.text(name))
        }
        assertEquals(46, Fixtures.names.size)
    }

    @Test fun `me decodes from chairtime's own response`() {
        val me = ChairtimeJson.decodeFromString(Me.serializer(), Fixtures.text("me"))
        assertEquals("Kit Alvarez", me.staff.name)
        assertEquals("Fold Barbers", me.shop.name)
        assertEquals("Europe/London", me.shop.timezone)
        assertEquals("GBP", me.shop.currency)
        assertEquals(TenantStatus.Active, me.shop.status)
        assertTrue(me.shops.any { it.id == me.shop.id })
    }

    /** A state added on the server must not stop an older app opening. */
    @Test fun `an unknown shop status still decodes`() {
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("me")).jsonObject
        val shop = JsonObject(json.getValue("shop").jsonObject + ("status" to JsonPrimitive("archived")))
        val me = ChairtimeJson.decodeFromJsonElement(Me.serializer(), JsonObject(json + ("shop" to shop)))
        assertEquals(TenantStatus.Unknown("archived"), me.shop.status)
    }

    /** And a field added on the server is none of an older app's business. */
    @Test fun `a field this build has not heard of is ignored`() {
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("me")).jsonObject
        val me = ChairtimeJson.decodeFromJsonElement(
            Me.serializer(), JsonObject(json + ("somethingNew" to JsonPrimitive(true))),
        )
        assertEquals("kit@fold.example", me.user.email)
    }

    @Test fun `your page decodes from chairtime's own response`() {
        val page = ChairtimeJson.decodeFromString(PageResponse.serializer(), Fixtures.text("page"))
        assertEquals("Skin fades and beard work in Hackney", page.headline)
        assertEquals(listOf("barber", "colour"), page.trades)
        assertEquals(15, page.tradeChoices.size)
        assertTrue(page.link.endsWith("/book/fold-barbers"))
        assertTrue(page.storage)
        assertEquals(6, page.photos.size)
        assertEquals("Ade Balogun", page.photos.first().staffName)
        assertEquals(listOf("Hackney Road", "Dalston Lane"), page.outlets.map { it.name })
        assertEquals("288 Hackney Road, London, E2 7SJ", page.outlets.first().shown)
        assertEquals(PageResponse.Limits(120, 2000, 30, 140), page.limits)
        assertTrue(page.team.any { it.name == "Kit Alvarez" })
    }

    @Test fun `your website decodes from chairtime's own response`() {
        val site = ChairtimeJson.decodeFromString(SiteResponse.serializer(), Fixtures.text("site"))
        assertTrue(site.ready)
        assertEquals(15, site.designs.size)
        assertEquals(listOf("Salons and stylists", "Barbers and tattoo artists"), site.designGroups.map { it.first })
        assertTrue(site.designs.any { it.key == site.template })
        assertTrue(site.designs.all { it.imageUrl != null && it.previewPath.startsWith("/site/preview/") })
        assertEquals(listOf("Hair", "Make-up"), site.sides.sides.map { it.name })
        assertEquals(4, site.sides.max)
        assertEquals("Make-up", site.sides.side(site.sides.categories.last().sideId)?.name)
        assertEquals(5, site.sides.photos.size)
        assertTrue(site.ownAddress)
        val waiting = site.domains.single { it.state == "waiting" }
        assertEquals(listOf("TXT", "A", "A", "CNAME"), waiting.records.map { it.type })
        assertEquals("_wunderhand.${waiting.host}", waiting.records.first().fqdn)
    }

    @Test fun `a domain that is ours but not pointing here yet decodes with its records`() {
        val site = ChairtimeJson.decodeFromString(SiteResponse.serializer(), Fixtures.text("site-domain-pointing"))
        val domain = site.domains.single { it.state == "pointing" }
        assertEquals(listOf("A", "A", "CNAME"), domain.records.map { it.type })
        assertTrue(domain.message!!.contains("not pointing here yet"))
    }

    /** Silence about a domain of their own is read as no: the screen then says nothing about one. */
    @Test fun `a website answer that does not say reads as no address of its own`() {
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("site")).jsonObject
        val site = ChairtimeJson.decodeFromJsonElement(SiteResponse.serializer(), JsonObject(json - "ownAddress"))
        assertEquals(false, site.ownAddress)
    }
}
