package com.wunderhand.network

import com.wunderhand.core.PageWrite
import com.wunderhand.core.SideWrite
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Your page and Your website: where each call goes, what it carries, and that every answer is the whole screen. */
class PageAndSiteCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun sent() = server.takeRequest()

    @Test fun `the page is read, its words saved and it is published, each answering with the page`() = runTest {
        repeat(4) { reply(200, fixture("page")) }
        val client = client()
        assertEquals(6, client.page().photos.size)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/shop/page", it.url.encodedPath) }

        // A line left empty is left out, which is how the server is told to clear it.
        assertEquals(listOf("barber", "colour"), client.savePage(PageWrite(headline = "Skin fades", trades = listOf("barber"))).trades)
        sent().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/shop/page", it.url.encodedPath)
            assertEquals("""{"headline":"Skin fades","trades":["barber"]}""", it.body!!.utf8())
        }

        client.publishPage(true)
        sent().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/shop/page/published", it.url.encodedPath)
            assertEquals("""{"published":true}""", it.body!!.utf8())
        }

        client.setAddressPrivate("o1", true)
        sent().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/shop/page/address/o1", it.url.encodedPath)
            assertEquals("""{"addressPrivate":true}""", it.body!!.utf8())
        }
    }

    @Test fun `a cover and a logo go as a file in photo, and come off with a delete`() = runTest {
        repeat(4) { reply(200, fixture("page")) }
        val client = client()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        assertTrue(client.uploadPageImage("cover", bytes).coverUrl != null)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/page/cover", it.url.encodedPath)
            assertTrue(it.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
            val body = it.body!!.utf8()
            assertTrue(body.contains("name=\"photo\"; filename=\"photo.jpg\""))
            assertTrue(body.contains("Content-Type: image/jpeg"))
        }

        // A logo's own PNG goes as it is, so what is see-through stays so.
        client.uploadPageImage("logo", bytes, "image/png")
        sent().let {
            assertEquals("/api/v1/shop/page/logo", it.url.encodedPath)
            val body = it.body!!.utf8()
            assertTrue(body.contains("name=\"photo\"; filename=\"photo.png\""))
            assertTrue(body.contains("Content-Type: image/png"))
        }

        client.removePageImage("cover")
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/page/cover", it.url.encodedPath) }
        client.removePageImage("logo")
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/page/logo", it.url.encodedPath) }
    }

    @Test fun `a photograph of the work carries what it is and who did it, when they are said`() = runTest {
        repeat(3) { reply(200, fixture("page")) }
        val client = client()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

        client.addWorkPhoto(jpeg, "ZZ Low fade", "st1")
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/page/photos", it.url.encodedPath)
            val body = it.body!!.utf8()
            assertTrue(body.contains("name=\"photo\"; filename=\"photo.jpg\""))
            assertTrue(body.contains("name=\"caption\"") && body.contains("ZZ Low fade"))
            assertTrue(body.contains("name=\"staffId\"") && body.contains("st1"))
        }

        client.addWorkPhoto(jpeg, null, null)
        sent().body!!.utf8().let { assertFalse(it.contains("name=\"caption\"")); assertFalse(it.contains("name=\"staffId\"")) }

        client.removeWorkPhoto("p1")
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/page/photos/p1", it.url.encodedPath) }
    }

    @Test fun `an upload is sent once, and what the server says of it is what is said`() = runTest {
        reply(413, """{"error":{"code":"too_large","message":"That photograph is too big. Keep it under 4 MB. Nothing changed.","field":"photo"}}""")
        val error = runCatching { client().uploadPageImage("cover", ByteArray(8)) }.exceptionOrNull() as ApiError
        assertEquals("That photograph is too big. Keep it under 4 MB. Nothing changed.", error.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a web address the server cannot read is refused against its field`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"That does not look like a web address. Try it like yourshop.com","field":"website"}}""")
        val error = runCatching { client().savePage(PageWrite(website = "nope")) }.exceptionOrNull() as ApiError.Validation
        assertEquals("website", error.field)
    }

    @Test fun `the page is an owner's`() = runTest {
        reply(403, """{"error":{"code":"not_owner","message":"Only an owner can change the shop."}}""")
        assertTrue(runCatching { client().page() }.exceptionOrNull() is ApiError.NotOwner)
    }

    @Test fun `the website is read and its design chosen`() = runTest {
        repeat(2) { reply(200, fixture("site")) }
        val client = client()
        assertEquals(15, client.site().designs.size)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/shop/site", it.url.encodedPath) }

        client.chooseDesign("grain")
        sent().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/shop/site/design", it.url.encodedPath)
            assertEquals("""{"template":"grain"}""", it.body!!.utf8())
        }
    }

    @Test fun `sides are added, saved whole, moved and removed`() = runTest {
        repeat(4) { reply(200, fixture("site")) }
        val client = client()

        assertEquals(2, client.addSide("ZZ Side").sides.sides.size)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/site/sides", it.url.encodedPath)
            assertEquals("""{"name":"ZZ Side"}""", it.body!!.utf8())
        }

        client.saveSide("s1", SideWrite("Hair", null, "ph1", listOf("c1", "c2")))
        sent().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/shop/site/sides/s1", it.url.encodedPath)
            assertEquals("""{"name":"Hair","portfolioImageId":"ph1","categoryIds":["c1","c2"]}""", it.body!!.utf8())
        }

        client.moveSide("s1", "down")
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/site/sides/s1/move", it.url.encodedPath)
            assertEquals("""{"direction":"down"}""", it.body!!.utf8())
        }

        client.removeSide("s1")
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/site/sides/s1", it.url.encodedPath) }
    }

    @Test fun `a domain is added, checked with nothing to say, and removed`() = runTest {
        reply(200, fixture("site")); reply(200, fixture("site-domain-pointing")); reply(200, fixture("site"))
        val client = client()

        client.addDomain("zz-site-api-test.co.uk")
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/site/domains", it.url.encodedPath)
            assertEquals("""{"domain":"zz-site-api-test.co.uk"}""", it.body!!.utf8())
        }

        assertEquals("pointing", client.checkDomain("d1").domains.single().state)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/shop/site/domains/d1/check", it.url.encodedPath)
            assertEquals("", it.body?.utf8().orEmpty())
        }

        client.removeDomain("d1")
        sent().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/site/domains/d1", it.url.encodedPath) }
    }

    @Test fun `a fifth side is refused in the server's sentence`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"4 sides is the most a site shows. Remove one to add another","field":"name"}}""")
        val error = runCatching { client().addSide("Fifth") }.exceptionOrNull() as ApiError.Validation
        assertEquals("4 sides is the most a site shows. Remove one to add another", error.message)
    }
}
