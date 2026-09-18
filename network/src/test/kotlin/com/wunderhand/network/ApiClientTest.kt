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
