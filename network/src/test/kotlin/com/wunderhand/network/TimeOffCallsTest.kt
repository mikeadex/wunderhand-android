package com.wunderhand.network

import com.wunderhand.core.TimeOffWrite
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The 1.4 calls: time off read, asked about, taken and taken back. */
class TimeOffCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {}, log = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun sent() = server.takeRequest()

    @Test fun `time off is read as the list`() = runTest {
        reply(200, fixture("time-off"))
        val list = client().timeOff()
        assertEquals(2, list.entries.size)
        assertTrue(list.mayChooseWho)
        sent().let { assertEquals("GET", it.method); assertEquals("/api/v1/time-off", it.url.encodedPath) }
    }

    /** Asking changes nothing, and what the days are for is no part of the question. */
    @Test fun `what is in the days is asked without the note, and taking them sends it`() = runTest {
        reply(200, fixture("time-off-check")); reply(200, fixture("time-off"))
        val client = client()
        val write = TimeOffWrite("everyone", "2028-04-03", "2028-04-05", "Lisbon")

        assertEquals(1, client.checkTimeOff(write).inside.size)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/time-off/check", it.url.encodedPath)
            assertEquals("""{"who":"everyone","from":"2028-04-03","to":"2028-04-05"}""", it.body!!.utf8())
        }

        assertEquals(1, client.takeTimeOff(write).comingUp.size)
        sent().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/time-off", it.url.encodedPath)
            assertEquals("""{"who":"everyone","from":"2028-04-03","to":"2028-04-05","note":"Lisbon"}""", it.body!!.utf8())
        }
    }

    @Test fun `days are taken back by the request and its dates, for one person when it is theirs`() = runTest {
        reply(200, fixture("time-off")); reply(200, fixture("time-off"))
        val client = client()

        client.removeTimeOff("s1", "2028-04-03", "2028-04-05")
        sent().let {
            assertEquals("DELETE", it.method); assertEquals("/api/v1/time-off", it.url.encodedPath)
            assertEquals("s1", it.url.queryParameter("seriesId"))
            assertEquals("2028-04-03", it.url.queryParameter("from")); assertEquals("2028-04-05", it.url.queryParameter("to"))
            assertNull(it.url.queryParameter("staffId"))
        }

        client.removeTimeOff("s1", "2028-04-03", "2028-04-03", "p1")
        assertEquals("p1", sent().url.queryParameter("staffId"))
    }

    @Test fun `days that are booked over, or too many, are refused with chairtime's words`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"Not saved. The last day is before the first.","field":"to"}}""")
        val error = runCatching { client().takeTimeOff(TimeOffWrite("everyone", "2028-04-05", "2028-04-03")) }.exceptionOrNull() as ApiError.Validation
        assertEquals("to", error.field)
        assertEquals("Not saved. The last day is before the first.", error.message)
    }

    @Test fun `nothing left to take back is not found`() = runTest {
        reply(404, """{"error":{"code":"not_found","message":"Those days are not off."}}""")
        val error = runCatching { client().removeTimeOff("s1", "2028-04-03", "2028-04-05") }.exceptionOrNull()
        assertTrue(error is ApiError)
    }
}
