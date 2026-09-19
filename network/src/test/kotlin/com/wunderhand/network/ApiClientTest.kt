package com.wunderhand.network

import com.wunderhand.core.Me
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ApiClientTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore("a-token")
    private val waits = mutableListOf<Duration>()

    private val meJson = checkNotNull(javaClass.getResourceAsStream("/me.json")).bufferedReader().use { it.readText() }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    /** A client whose waits are written down rather than waited. */
    private fun client(plan: RetryPlan = RetryPlan.Standard) =
        ApiClient(server.url("/").toString(), tokens, build = "1.0", retry = plan, wait = { waits += it })

    private fun reply(code: Int = 200, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())
    }

    private fun refusal(code: String, message: String = "No.", field: String? = null) =
        """{"error":{"code":"$code","message":"$message"${field?.let { ""","field":"$it"""" } ?: ""}}}"""

    private suspend fun refused(block: suspend () -> Unit): ApiError =
        try { block(); fail("expected a refusal"); error("unreachable") } catch (e: ApiError) { e }

    // Trouble that passes

    @Test fun `a deploy going out is waited out rather than shown`() = runTest {
        reply(503); reply(502); reply(200, meJson)
        val me: Me = client().me()
        assertEquals("Kit Alvarez", me.staff.name)
        assertEquals(listOf(400.milliseconds, 800.milliseconds), waits)
        assertEquals(3, server.requestCount)
    }

    @Test fun `it gives up after three goes and says what the last one said`() = runTest {
        repeat(3) { reply(503) }
        val error = refused { client().me() }
        assertTrue(error is ApiError.Unavailable)
        assertTrue(error.isPassing)
        assertEquals(3, server.requestCount)
    }

    @Test fun `a server that says how long is believed`() = runTest {
        reply(503, "", "Retry-After" to "2"); reply(200, meJson)
        client().me()
        assertEquals(listOf(2.seconds), waits)
    }

    @Test fun `a ridiculous Retry-After is capped so the app never looks stuck`() = runTest {
        reply(503, "", "Retry-After" to "3600"); reply(200, meJson)
        client().me()
        assertEquals(listOf(5.seconds), waits)
    }

    @Test fun `something that changes the day is never sent twice`() = runTest {
        reply(503)
        val error = refused { client().post<Map<String, String>, Ack>("api/v1/appointments/a1/close", mapOf("outcome" to "done")) }
        assertTrue(error is ApiError.Unavailable)
        assertEquals(1, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test fun `a refusal meant for the person is not retried`() = runTest {
        reply(403, refusal("not_owner", "Only an owner can change this."))
        val error = refused { client().me() }
        assertTrue(error is ApiError.NotOwner)
        assertEquals("Only an owner can change this.", error.message)
        assertEquals(1, server.requestCount)
    }

    // Signing in

    @Test fun `sign-in keeps the token from the header`() = runTest {
        tokens.write(null)
        reply(200, """{"token":"ignored"}""", "set-auth-token" to "fresh-token")
        client().signIn("kit@fold.example", "secret")
        assertEquals("fresh-token", tokens.read())
        val sent = server.takeRequest()
        assertEquals("/api/auth/sign-in/email", sent.url.encodedPath)
        assertEquals("android/1.0", sent.headers["X-Wunderhand-Client"])
        assertTrue(sent.body!!.utf8().contains("\"email\":\"kit@fold.example\""))
    }

    /** Better Auth checks Origin only on a request that carries cookies, and
     *  a native app has no Origin to give. */
    @Test fun `never sends a cookie back`() = runTest {
        tokens.write(null)
        reply(200, "{}", "set-auth-token" to "fresh-token", "Set-Cookie" to "better-auth.session_token=abc; Path=/; HttpOnly")
        reply(200, meJson)
        val client = client()
        client.signIn("kit@fold.example", "secret")
        client.me()
        server.takeRequest()
        val second = server.takeRequest()
        assertNull(second.headers["Cookie"])
        assertEquals("Bearer fresh-token", second.headers["Authorization"])
    }

    @Test fun `wrong details say what the web says`() = runTest {
        reply(401, """{"code":"INVALID_EMAIL_OR_PASSWORD","message":"Invalid email or password"}""")
        val error = refused { client().signIn("kit@fold.example", "wrong") }
        assertTrue(error is ApiError.SignInRefused)
        assertEquals("That email and password do not match. Try again.", error.message)
    }

    @Test fun `too many attempts is its own sentence`() = runTest {
        reply(429, "")
        val error = refused { client().signIn("kit@fold.example", "wrong") }
        assertEquals("Too many attempts in a short time. Wait a few minutes and try again.", error.message)
    }

    // The session

    @Test fun `sends the chosen shop`() = runTest {
        reply(200, meJson)
        client().apply { tenantId = "shop-2" }.me()
        assertEquals("shop-2", server.takeRequest().headers["X-Tenant-Id"])
    }

    @Test fun `no shop chosen sends no header, and the server picks the first`() = runTest {
        reply(200, meJson)
        client().me()
        assertNull(server.takeRequest().headers["X-Tenant-Id"])
    }

    @Test fun `a 401 forgets the token`() = runTest {
        reply(401, refusal("unauthorized", "Sign in again."))
        assertTrue(refused { client().me() } is ApiError.Unauthorized)
        assertNull(tokens.read())
    }

    @Test fun `a server failure keeps the token`() = runTest {
        reply(500, "<html>oops</html>")
        assertTrue(refused { client().me() } is ApiError.Server)
        assertEquals("a-token", tokens.read())
    }

    @Test fun `with no token nothing is sent at all`() = runTest {
        tokens.write(null)
        assertTrue(refused { client().me() } is ApiError.Unauthorized)
        assertEquals(0, server.requestCount)
    }

    @Test fun `maps refusal codes`() = runTest {
        val cases = mapOf(
            "not_member" to ApiError.NotMember::class, "shop_inactive" to ApiError.ShopInactive::class,
            "not_found" to ApiError.NotFound::class, "slot_taken" to ApiError.SlotTaken::class,
            "too_early" to ApiError.TooEarly::class, "already_settled" to ApiError.AlreadySettled::class,
            "already_closed" to ApiError.AlreadyClosed::class, "upgrade_required" to ApiError.UpgradeRequired::class,
            "too_young" to ApiError.Ineligible::class, "missing_prerequisite" to ApiError.Ineligible::class,
            "a_code_from_the_future" to ApiError.Server::class,
        )
        for ((code, expected) in cases) {
            reply(409, refusal(code))
            assertEquals(code, expected, refused { client().me() }::class)
        }
    }

    @Test fun `a refused field comes back by name`() = runTest {
        reply(422, refusal("validation", "That mobile is already on file.", field = "phone"))
        val error = refused { client().me() } as ApiError.Validation
        assertEquals("phone", error.field)
    }

    @Test fun `a shape this build cannot read is said plainly, not thrown as a crash`() = runTest {
        reply(200, """{"user":"not an object"}""")
        val error = refused { client().me() }
        assertTrue(error is ApiError.Server)
        assertEquals(ApiError.SERVER_MESSAGE, error.message)
    }

    // Threads

    /**
     * Android kills an app that reads a socket on its main thread, and a
     * reply's body is a socket until it has been read to the end. So the body
     * must be read before the answer is handed back to whoever asked — here,
     * a single thread standing in for the main one. Found as a crash on a cold
     * launch: it only shows when the body arrives in chunks.
     */
    @Test fun `a reply is read to the end before it reaches the thread that asked`() {
        val readOn = mutableListOf<String>()
        val http = ApiClient.makeHttp().newBuilder().eventListener(object : okhttp3.EventListener() {
            override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) { readOn += Thread.currentThread().name }
        }).build()
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(meJson, 64).build())
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "stands-in-for-main") }
        try {
            kotlinx.coroutines.runBlocking(main.asCoroutineDispatcher()) {
                ApiClient(server.url("/").toString(), tokens, build = "1.0", http = http).me()
            }
        } finally {
            main.shutdown()
        }
        assertEquals(1, readOn.size)
        assertTrue("read on ${readOn.single()}", readOn.single() != "stands-in-for-main")
    }

    // No signal

    @Test fun `no connection is offline, not a failure`() = runTest {
        val client = client(RetryPlan.StraightAway)
        server.close()
        val error = refused { client.me() }
        assertTrue(error is ApiError.Offline)
        assertEquals("a-token", tokens.read())
    }

    @Test fun `sign-out forgets the token even when the server is unreachable`() = runTest {
        val client = client()
        server.close()
        client.signOut()
        assertNull(tokens.read())
    }
}

class DiaryCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})

    @Test fun `today is asked for without a date, so the shop decides what today is`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("diary")).build())
        val day = client().diary(null)
        assertEquals("/api/v1/diary", server.takeRequest().url.encodedPath)
        assertEquals(7, day.week.size)
    }

    @Test fun `another day is asked for by the shop's calendar date`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("diary")).build())
        client().diary("2026-09-21")
        assertEquals("2026-09-21", server.takeRequest().url.queryParameter("date"))
    }

    @Test fun `an appointment is asked for by its id`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("appointment")).build())
        val response = client().appointment("appt-1")
        assertEquals("/api/v1/appointments/appt-1", server.takeRequest().url.encodedPath)
        assertTrue(response.appointment.lineItems.isNotEmpty())
    }
}

/** Running the day: what is sent, where, and what a refusal comes back as. */
class ActionCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private val at = java.time.Instant.parse("2026-09-16T10:15:00Z")

    @Test fun `moves with an instant chairtime reads`() = runTest {
        reply(200, "{}")
        client().move("a1", at)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/v1/appointments/a1/move", sent.url.encodedPath)
        assertEquals("""{"startsAt":"2026-09-16T10:15:00.000Z"}""", sent.body!!.utf8())
    }

    @Test fun `resizes by the end`() = runTest {
        reply(200, "{}")
        client().resize("a1", at)
        val sent = server.takeRequest()
        assertEquals("/api/v1/appointments/a1/resize", sent.url.encodedPath)
        assertEquals("""{"endsAt":"2026-09-16T10:15:00.000Z"}""", sent.body!!.utf8())
    }

    @Test fun `a clash comes back as slot taken, in chairtime's words`() = runTest {
        reply(409, """{"error":{"code":"slot_taken","message":"Someone got there first."}}""")
        val error = try { client().move("a1", at); null } catch (e: ApiError) { e }
        assertTrue(error is ApiError.SlotTaken)
        assertEquals("Someone got there first.", error?.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `closing sends the outcome and reads the refund`() = runTest {
        reply(200, """{"outcome":"cancelled","refund":{"refundPence":1000,"keptPence":0}}""")
        val closed = client().close("a1", com.wunderhand.core.CloseOutcome.Cancelled)
        val sent = server.takeRequest()
        assertEquals("/api/v1/appointments/a1/close", sent.url.encodedPath)
        assertEquals("""{"outcome":"cancelled"}""", sent.body!!.utf8())
        assertEquals(1000, closed.refund?.refundPence?.value)
    }

    @Test fun `a no-show is chairtime's word for it, not Kotlin's`() = runTest {
        reply(200, """{"outcome":"no_show","refund":null}""")
        client().close("a1", com.wunderhand.core.CloseOutcome.NoShow)
        assertEquals("""{"outcome":"no_show"}""", server.takeRequest().body!!.utf8())
    }

    @Test fun `too early to mark done is its own refusal`() = runTest {
        reply(409, """{"error":{"code":"too_early","message":"Mark done opens when they arrive."}}""")
        val error = try { client().close("a1", com.wunderhand.core.CloseOutcome.Completed); null } catch (e: ApiError) { e }
        assertTrue(error is ApiError.TooEarly)
    }

    @Test fun `later days are asked for from a date`() = runTest {
        reply(200, """{"currentStartsAt":"2026-09-16T09:00:00.000Z","days":[]}""")
        client().slots("a1", from = "2026-09-24")
        val sent = server.takeRequest()
        assertEquals("/api/v1/appointments/a1/slots", sent.url.encodedPath)
        assertEquals("2026-09-24", sent.url.queryParameter("from"))
    }

    @Test fun `a repeat is started in weeks and stopped with or without what it booked`() = runTest {
        reply(200, """{"booked":3,"skipped":1}""")
        reply(200, """{"cancelled":0}""")
        reply(200, """{"cancelled":2}""")
        val client = client()
        assertEquals(3, client.startRepeat("a1", 4).booked)
        assertEquals("""{"intervalWeeks":4}""", server.takeRequest().body!!.utf8())
        client.stopRepeat("a1", cancelUpcoming = false)
        server.takeRequest().let { assertEquals("DELETE", it.method); assertNull(it.url.queryParameter("cancelUpcoming")) }
        assertEquals(2, client.stopRepeat("a1", cancelUpcoming = true).cancelled)
        assertEquals("1", server.takeRequest().url.queryParameter("cancelUpcoming"))
    }

    @Test fun `consent is recorded against the appointment`() = runTest {
        reply(200, "{}")
        client().recordConsent("a1")
        assertEquals("/api/v1/appointments/a1/consent", server.takeRequest().url.encodedPath)
    }

    @Test fun `time is blocked, and unblocked by its id`() = runTest {
        reply(200, """{"id":"b1","date":"2026-09-16"}""")
        reply(200, "{}")
        val client = client()
        val created = client.blockTime(com.wunderhand.core.BlockRequest("s1", com.wunderhand.core.BlockRequest.Kind.Break, "2026-09-16", "13:00", "13:30", "Dentist"))
        assertEquals("b1", created.id)
        assertEquals("""{"staffId":"s1","kind":"break","date":"2026-09-16","from":"13:00","to":"13:30","note":"Dentist"}""", server.takeRequest().body!!.utf8())
        client.unblock("b1")
        server.takeRequest().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/blocks/b1", it.url.encodedPath) }
    }
}

/** A new booking: what is asked for, and what a refusal comes back as. */
class BookingCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private val at = java.time.Instant.parse("2026-09-16T10:15:00Z")

    @Test fun `slots ask for every extra, each by name`() = runTest {
        reply(200, fixture("booking-slots"))
        client().bookingSlots("s1", "p1", listOf("a1", "a2"), from = "2026-09-24")
        val url = server.takeRequest().url
        assertEquals("/api/v1/booking/slots", url.encodedPath)
        assertEquals("s1", url.queryParameter("service"))
        assertEquals("p1", url.queryParameter("staff"))
        assertEquals(listOf("a1", "a2"), url.queryParameterValues("addon"))
        assertEquals("2026-09-24", url.queryParameter("from"))
    }

    @Test fun `booking sends the choices and the instant`() = runTest {
        reply(200, """{"bookingId":"b1","appointmentId":"a1","startsAt":"2026-09-16T10:15:00.000Z","date":"2026-09-16"}""")
        val created = client().book("s1", "p1", at, clientId = "c1", addonIds = listOf("x"), overridePrerequisite = true)
        val sent = server.takeRequest()
        assertEquals("/api/v1/bookings", sent.url.encodedPath)
        assertEquals("""{"serviceId":"s1","staffId":"p1","startsAt":"2026-09-16T10:15:00.000Z","clientId":"c1","addonIds":["x"],"overridePrerequisite":true}""", sent.body!!.utf8())
        assertEquals("2026-09-16", created.date)
    }

    @Test fun `a walk-in is a booking with no client in it`() = runTest {
        reply(200, """{"bookingId":"b1","appointmentId":"a1","startsAt":"2026-09-16T10:15:00.000Z","date":"2026-09-16"}""")
        client().book("s1", "p1", at, clientId = null, addonIds = emptyList(), overridePrerequisite = false)
        assertTrue("clientId" !in server.takeRequest().body!!.utf8())
    }

    @Test fun `a refused rule comes back by name, and a booking is never sent twice`() = runTest {
        reply(422, """{"error":{"code":"too_young","message":"This service is for over-18s."}}""")
        val error = try { client().book("s1", "p1", at, "c1", emptyList(), false); null } catch (e: ApiError) { e }
        assertEquals("too_young", (error as ApiError.Ineligible).rule)
        assertEquals("This service is for over-18s.", error.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `the menu and a service are asked for where the web asks`() = runTest {
        reply(200, fixture("booking-services")); reply(200, fixture("booking-service"))
        val client = client()
        assertTrue(client.bookingServices().services.isNotEmpty())
        assertEquals("/api/v1/booking/services", server.takeRequest().url.encodedPath)
        client.bookingService("s1")
        assertEquals("/api/v1/booking/services/s1", server.takeRequest().url.encodedPath)
    }
}

class ClientCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun `the client list asks only for what is set`() = runTest {
        reply(200, fixture("clients")); reply(200, fixture("clients"))
        val client = client()
        client.clients()
        assertNull(server.takeRequest().url.query)
        client.clients(com.wunderhand.core.ClientFilter.NoShows, "  wren ")
        val url = server.takeRequest().url
        assertEquals("no_shows", url.queryParameter("filter"))
        assertEquals("wren", url.queryParameter("q"))
    }

    @Test fun `changing a client sends the whole form, and a new one is posted`() = runTest {
        reply(200, """{"id":"c1"}"""); reply(200, """{"id":"c2"}""")
        val client = client()
        val input = com.wunderhand.core.ClientInput(name = "Wren Halloway", phone = "07700 900123")
        client.updateClient("c1", input)
        server.takeRequest().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/clients/c1", it.url.encodedPath)
            assertEquals("""{"name":"Wren Halloway","phone":"07700 900123","email":"","dateOfBirth":"","notes":"","standingFormula":""}""", it.body!!.utf8())
        }
        assertEquals("c2", client.createClient(input).id)
        server.takeRequest().let { assertEquals("POST", it.method); assertEquals("/api/v1/clients", it.url.encodedPath) }
    }

    @Test fun `a taken mobile comes back against the phone field`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"Somebody already has that mobile.","field":"phone"}}""")
        val error = try { client().createClient(com.wunderhand.core.ClientInput(name = "W")); null } catch (e: ApiError) { e }
        assertEquals("phone", (error as ApiError.Validation).field)
    }

    @Test fun `medical notes are read, saved and erased at their own address`() = runTest {
        reply(200, fixture("health")); reply(200, """{"saved":true,"retainUntil":"2034-09-16T00:00:00.000Z"}"""); reply(200, "{}")
        val client = client()
        assertTrue(client.health("c1").hasContent)
        assertEquals("/api/v1/clients/c1/health", server.takeRequest().url.encodedPath)
        assertTrue(client.saveHealth("c1", mapOf("allergies" to "PPD")).retainUntil != null)
        server.takeRequest().let { assertEquals("PUT", it.method); assertEquals("""{"allergies":"PPD"}""", it.body!!.utf8()) }
        client.eraseHealth("c1", "erase")
        server.takeRequest().let { assertEquals("DELETE", it.method); assertEquals("erase", it.url.queryParameter("confirm")) }
    }

    /** The word is chairtime's to check: the app sends what was typed and says what comes back. */
    @Test fun `erasing carries the typed word, right or wrong`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"Type erase to confirm.","field":"confirm"}}""")
        val error = try { client().eraseHealth("c1", "eraze"); null } catch (e: ApiError) { e }
        assertEquals("Type erase to confirm.", error?.message)
        assertEquals("eraze", server.takeRequest().url.queryParameter("confirm"))
    }
}

class ShopAndLeavingCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private val person = """{"person":{"id":"p1","name":"Ade Balogun","employment":"employed","accountStatus":"none","isBookable":true,"hasLogin":false,"isOwner":false},"outletIds":[],"outlets":[],"viewerIsOwner":true,"isYou":false}"""

    @Test fun `somebody else's hours are asked for by who and where`() = runTest {
        reply(200, """{"staff":[],"outlets":[],"days":[]}"""); reply(200, """{"staff":[],"outlets":[],"days":[]}""")
        val client = client()
        client.hours()
        assertNull(server.takeRequest().url.query)
        client.hours("s1", "o1")
        server.takeRequest().url.let { assertEquals("s1", it.queryParameter("staff")); assertEquals("o1", it.queryParameter("outlet")) }
    }

    @Test fun `an owner-only read comes back as not owner, in chairtime's words`() = runTest {
        reply(403, """{"error":{"code":"not_owner","message":"Only an owner can change the menu."}}""")
        val error = try { client().menuOptions(); null } catch (e: ApiError) { e }
        assertTrue(error is ApiError.NotOwner)
        assertEquals("Only an owner can change the menu.", error?.message)
    }

    @Test fun `an invitation goes to an address, or to nobody for somebody coming back`() = runTest {
        reply(200, person.dropLast(1) + ""","outcome":"invited","message":"Invitation sent."}"""); reply(200, person.dropLast(1) + ""","outcome":"restored","message":"They are back."}""")
        val client = client()
        assertEquals("Invitation sent.", client.invite("p1", "ade@example.com").message)
        server.takeRequest().let { assertEquals("/api/v1/shop/team/p1/invite", it.url.encodedPath); assertEquals("""{"email":"ade@example.com"}""", it.body!!.utf8()) }
        client.invite("p1", null)
        assertEquals("{}", server.takeRequest().body!!.utf8())
    }

    @Test fun `who sees the money is its own act, at its own address`() = runTest {
        reply(200, person)
        client().setOwner("p1", true)
        server.takeRequest().let { assertEquals("PUT", it.method); assertEquals("/api/v1/shop/team/p1/owner", it.url.encodedPath); assertEquals("""{"owner":true}""", it.body!!.utf8()) }
    }

    @Test fun `steps are saved as the whole list, in order`() = runTest {
        reply(200, """{"service":{"id":"s1","name":"Tint","minutes":55},"segments":[]}""")
        client().saveSteps("s1", com.wunderhand.core.StepsWrite(listOf(com.wunderhand.core.StepsWrite.Step("Apply", 20, true), com.wunderhand.core.StepsWrite.Step("Develop", 35, false))))
        server.takeRequest().let {
            assertEquals("/api/v1/menu/s1/steps", it.url.encodedPath)
            assertEquals("""{"steps":[{"label":"Apply","minutes":20,"staffBusy":true},{"label":"Develop","minutes":35,"staffBusy":false}]}""", it.body!!.utf8())
        }
    }

    @Test fun `deleting a login sends the password typed again, once`() = runTest {
        reply(200, """{"deleted":true,"shopsLeft":2}""")
        assertEquals(2, client().deleteLogin("hunter2").shopsLeft)
        server.takeRequest().let { assertEquals("/api/v1/me/delete", it.url.encodedPath); assertEquals("""{"password":"hunter2"}""", it.body!!.utf8()) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `closing a shop asks, agrees, refuses and withdraws at their own addresses`() = runTest {
        reply(200, """{"closed":true,"deleteAfter":"2026-10-18T09:00:00.000Z","upcoming":2}"""); reply(200, """{"closed":true}"""); reply(200, "{}"); reply(200, "{}")
        val client = client()
        assertTrue(client.closeShop("pw", "fold-barbers").closed)
        server.takeRequest().let { assertEquals("POST", it.method); assertEquals("/api/v1/shop/close", it.url.encodedPath); assertEquals("""{"password":"pw","confirm":"fold-barbers"}""", it.body!!.utf8()) }
        client.agreeToClose("pw"); assertEquals("/api/v1/shop/close/agree", server.takeRequest().url.encodedPath)
        client.refuseToClose(); assertEquals("/api/v1/shop/close/refuse", server.takeRequest().url.encodedPath)
        client.withdrawClose(); server.takeRequest().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/shop/close", it.url.encodedPath) }
    }

    @Test fun `a wrong password is said, and nothing was closed`() = runTest {
        reply(422, """{"error":{"code":"validation","message":"That password is not right. Nothing was closed.","field":"password"}}""")
        val error = try { client().closeShop("wrong", "fold-barbers"); null } catch (e: ApiError) { e }
        assertEquals("password", (error as ApiError.Validation).field)
    }
}

class WaitlistAndMoneyCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private val from = java.time.Instant.parse("2026-09-18T13:00:00Z")
    private val to = java.time.Instant.parse("2026-09-18T14:30:00Z")

    @Test fun `a gap is asked for by whose it is and when, to the millisecond`() = runTest {
        reply(200, """{"staffId":"s1","from":"2026-09-18T13:00:00.000Z","to":"2026-09-18T14:30:00.000Z","gapMinutes":90,"suggested":2,"waitingCount":4,"candidates":[]}""")
        assertEquals(90, client().gap("s1", from, to).gapMinutes)
        server.takeRequest().url.let {
            assertEquals("/api/v1/gaps", it.encodedPath)
            assertEquals("s1", it.queryParameter("staff"))
            assertEquals("2026-09-18T13:00:00.000Z", it.queryParameter("from"))
            assertEquals("2026-09-18T14:30:00.000Z", it.queryParameter("to"))
        }
    }

    @Test fun `an offer names the window and who it goes to`() = runTest {
        reply(200, """{"broadcastId":"b1","emailWorking":true,"sent":[{"name":"Wren Halloway","phone":"07700900123","email":null,"url":"https://wunderhand.com/offer/abc"}]}""")
        val sent = client().offerGap("s1", from, to, listOf("e1", "e2"))
        assertEquals(1, sent.toText.size)
        server.takeRequest().let {
            assertEquals("/api/v1/gaps/offer", it.url.encodedPath)
            assertEquals("""{"staffId":"s1","from":"2026-09-18T13:00:00.000Z","to":"2026-09-18T14:30:00.000Z","entryIds":["e1","e2"]}""", it.body!!.utf8())
        }
    }

    @Test fun `joining says only what was chosen, and leaving is a delete`() = runTest {
        reply(200, """{"id":"w1"}"""); reply(200, "{}")
        val client = client()
        client.joinWaitlist(com.wunderhand.core.WaitlistJoinRequest("c1", "sv1", days = listOf(4, 5), parts = listOf("e")))
        assertEquals("""{"clientId":"c1","serviceId":"sv1","days":[4,5],"parts":["e"]}""", server.takeRequest().body!!.utf8())
        client.leaveWaitlist("w1")
        server.takeRequest().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/waitlist/w1", it.url.encodedPath) }
    }

    @Test fun `the till is rung through once, and a second press is its own refusal`() = runTest {
        reply(200, """{"settledAt":"2026-09-18T14:00:00.000Z","receipt":{"subtotalPence":2800,"takenPence":3100,"tipPence":300,"method":"card"}}""")
        reply(409, """{"error":{"code":"already_settled","message":"This has already been checked out."}}""")
        val client = client()
        assertEquals(3100, client.settle("b1", com.wunderhand.core.TillRequest(tipPence = 300, method = "card")).receipt.takenPence.value)
        server.takeRequest().let {
            assertEquals("/api/v1/checkout/b1", it.url.encodedPath)
            assertEquals("""{"extraPence":0,"tipPence":300,"method":"card"}""", it.body!!.utf8())
        }
        val error = try { client.settle("b1", com.wunderhand.core.TillRequest()); null } catch (e: ApiError) { e }
        assertTrue(error is ApiError.AlreadySettled)
        // Money is never asked for twice on the app's own initiative.
        assertEquals(2, server.requestCount)
    }

    @Test fun `money is one question, and chairtime decides whose`() = runTest {
        reply(200, """{"scope":"mine","shopName":"Fold Barbers","currency":"GBP"}""")
        assertTrue(!client().money().isShop)
        assertEquals("/api/v1/money", server.takeRequest().url.encodedPath)
    }
}

class DeviceCallsTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = ApiClient(server.url("/").toString(), InMemoryTokenStore("a-token"), build = "1.0", wait = {})
    // FCM's tokens are not hex: colons, dashes, underscores, and long.
    private val token = "dGVzdA:APA91b" + "x_Y-z".repeat(30)

    @Test fun `a phone registers as android, for the shop now open`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        client().apply { tenantId = "t1" }.registerDevice(token, "1.0 (1)")
        server.takeRequest().let {
            assertEquals("/api/v1/devices", it.url.encodedPath)
            assertEquals("t1", it.headers["X-Tenant-Id"])
            assertEquals("""{"token":"$token","appVersion":"1.0 (1)","platform":"android"}""", it.body!!.utf8())
        }
    }

    @Test fun `leaving names the phone in the body, never in the address`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        client().releaseDevice(token)
        server.takeRequest().let {
            assertEquals("POST", it.method)
            assertEquals("/api/v1/devices/release", it.url.encodedPath)
            assertTrue(token !in it.url.toString())
            assertEquals("""{"token":"$token","platform":"android"}""", it.body!!.utf8())
        }
    }
}
