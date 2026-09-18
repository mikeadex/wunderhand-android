package com.wunderhand.network

import com.wunderhand.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * The one way the app talks to chairtime.
 *
 * Signs in through Better Auth's own endpoint and keeps the token it hands
 * back; everything after that is `Authorization: Bearer` to `/api/v1`.
 *
 * **No cookies, on purpose.** Better Auth checks a request's Origin only when
 * it carries cookies, and a native app has no Origin to give. A client that
 * stored the session cookie from the sign-in response would send it back on
 * the next POST and be refused as a cross-site request. The token is the
 * whole credential here.
 *
 * @param build the app's version, sent as `X-Wunderhand-Client: android/1.0`.
 * @param wait waiting, as something a test can hold still.
 */
class ApiClient(
    baseUrl: String,
    private val tokens: TokenStore,
    build: String,
    http: OkHttpClient? = null,
    private val retry: RetryPlan = RetryPlan.Standard,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
    /** Loud, for whoever is debugging; silent in a release build. */
    private val log: (String) -> Unit = {},
) : WunderhandApi {
    val baseUrl: HttpUrl = baseUrl.toHttpUrl()
    private val http: OkHttpClient = http ?: makeHttp()
    private val clientHeader = "android/$build"

    /** The shop to act for, sent as `X-Tenant-Id`. Null means the person's
     *  first shop, which is also what the web does. */
    @Volatile override var tenantId: String? = null

    override val hasToken: Boolean get() = tokens.read() != null

    // region Session

    /** Sign in with an email and password. The token is stored; nothing is
     *  returned, because what matters next is [me]. */
    override suspend fun signIn(email: String, password: String) {
        val body = ChairtimeJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("email" to email, "password" to password),
        )
        val request = Request.Builder()
            .url(url("api/auth/sign-in/email"))
            .post(body.toRequestBody(JSON))
            .header("X-Wunderhand-Client", clientHeader)
            .build()
        val reply = perform(request)
        if (!reply.isSuccessful) throw ApiError.signIn(reply.status, reply.body)
        val token = reply.header("set-auth-token")
        if (token.isNullOrEmpty()) throw ApiError.Server(ApiError.SERVER_MESSAGE)
        tokens.write(token)
    }

    /** Sign out here and on the server. The token is forgotten even if the
     *  server cannot be reached — somebody pressing Sign out means it. */
    override suspend fun signOut() {
        val token = tokens.read()
        try {
            if (token != null) {
                val request = Request.Builder()
                    .url(url("api/auth/sign-out"))
                    .post("{}".toRequestBody(JSON))
                    .header("Authorization", "Bearer $token")
                    .build()
                runCatching { perform(request) }.onFailure { if (it is CancellationException) throw it }
            }
        } finally {
            tokens.write(null)
        }
    }

    // endregion
    // region API v1

    override suspend fun me(): Me = get("api/v1/me")

    override suspend fun diary(date: String?): DiaryResponse =
        get("api/v1/diary", if (date == null) emptyMap() else mapOf("date" to date))

    override suspend fun appointment(id: String): AppointmentResponse = get("api/v1/appointments/$id")

    // Running the day

    override suspend fun close(appointmentId: String, outcome: CloseOutcome): CloseResponse =
        post("api/v1/appointments/$appointmentId/close", mapOf("outcome" to outcome.raw))

    override suspend fun slots(appointmentId: String, from: String?): SlotsResponse =
        get("api/v1/appointments/$appointmentId/slots", if (from == null) emptyMap() else mapOf("from" to from))

    override suspend fun move(appointmentId: String, to: Instant) {
        post<Map<String, String>, Ack>("api/v1/appointments/$appointmentId/move", mapOf("startsAt" to instant(to)))
    }

    override suspend fun resize(appointmentId: String, endsAt: Instant) {
        post<Map<String, String>, Ack>("api/v1/appointments/$appointmentId/resize", mapOf("endsAt" to instant(endsAt)))
    }

    override suspend fun recordConsent(appointmentId: String) {
        post<Map<String, String>, Ack>("api/v1/appointments/$appointmentId/consent", emptyMap())
    }

    override suspend fun startRepeat(appointmentId: String, intervalWeeks: Int): RepeatStarted =
        post("api/v1/appointments/$appointmentId/repeat", mapOf("intervalWeeks" to intervalWeeks))

    override suspend fun stopRepeat(appointmentId: String, cancelUpcoming: Boolean): RepeatStopped =
        delete("api/v1/appointments/$appointmentId/repeat", if (cancelUpcoming) mapOf("cancelUpcoming" to "1") else emptyMap())

    override suspend fun blockTime(request: BlockRequest): BlockCreated = post("api/v1/blocks", request)

    override suspend fun unblock(id: String) {
        delete<Ack>("api/v1/blocks/$id")
    }

    // New booking

    override suspend fun bookingServices(): BookingServicesResponse = get("api/v1/booking/services")

    override suspend fun bookingService(id: String): BookingServiceResponse = get("api/v1/booking/services/$id")

    override suspend fun bookingSlots(serviceId: String, staffId: String, addonIds: List<String>, from: String?): BookingSlotsResponse =
        // An extra is asked for once each: `addon=a&addon=b`, as the web's own form sends them.
        send("GET", "api/v1/booking/slots", buildList {
            add("service" to serviceId); add("staff" to staffId)
            addonIds.forEach { add("addon" to it) }
            from?.let { add("from" to it) }
        }, null, BookingSlotsResponse.serializer())

    override suspend fun book(serviceId: String, staffId: String, startsAt: Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean): BookingCreated =
        post("api/v1/bookings", BookingRequest(serviceId, staffId, instant(startsAt), clientId, addonIds, overridePrerequisite))

    // Clients

    override suspend fun clients(filter: ClientFilter, query: String): ClientsResponse =
        // Only what is set is asked for: "all" and an empty search are the list as it comes.
        send("GET", "api/v1/clients", buildList {
            if (filter != ClientFilter.All) add("filter" to filter.raw)
            query.trim().takeIf { it.isNotEmpty() }?.let { add("q" to it) }
        }, null, ClientsResponse.serializer())

    override suspend fun client(id: String): ClientProfileResponse = get("api/v1/clients/$id")

    override suspend fun createClient(input: ClientInput): ClientSaved = post("api/v1/clients", input)

    override suspend fun updateClient(id: String, input: ClientInput): ClientSaved = put("api/v1/clients/$id", input)

    override suspend fun removeClient(id: String) {
        delete<ClientSaved>("api/v1/clients/$id")
    }

    override suspend fun health(clientId: String): HealthResponse = get("api/v1/clients/$clientId/health")

    override suspend fun saveHealth(clientId: String, record: Map<String, String>): HealthSaved = put("api/v1/clients/$clientId/health", record)

    override suspend fun eraseHealth(clientId: String, confirmation: String) {
        delete<Ack>("api/v1/clients/$clientId/health", mapOf("confirm" to confirmation))
    }

    // The menu

    override suspend fun menu(): MenuResponse = get("api/v1/menu")
    override suspend fun menuService(id: String): MenuServiceResponse = get("api/v1/menu/$id")
    override suspend fun menuOptions(): MenuOptions = get("api/v1/menu/options")
    override suspend fun createService(write: ServiceWrite): MenuServiceResponse = post("api/v1/menu", write)
    override suspend fun updateService(id: String, write: ServiceWrite): MenuServiceResponse = put("api/v1/menu/$id", write)
    override suspend fun archiveService(id: String) { delete<SavedId>("api/v1/menu/$id") }
    override suspend fun saveSteps(serviceId: String, write: StepsWrite): MenuServiceResponse = put("api/v1/menu/$serviceId/steps", write)
    override suspend fun savePerformers(serviceId: String, write: PerformersWrite): MenuServiceResponse = put("api/v1/menu/$serviceId/performers", write)
    override suspend fun extras(serviceId: String): ExtrasResponse = get("api/v1/menu/$serviceId/extras")
    override suspend fun saveExtraLinks(serviceId: String, write: ExtraLinksWrite): ExtrasResponse = put("api/v1/menu/$serviceId/extras", write)
    override suspend fun createExtra(write: ExtraWrite): SavedId = post("api/v1/menu/extras", write)
    override suspend fun updateExtra(id: String, write: ExtraWrite): SavedId = put("api/v1/menu/extras/$id", write)
    override suspend fun retireExtra(id: String) { delete<SavedId>("api/v1/menu/extras/$id") }

    // The shop

    override suspend fun shop(): ShopResponse = get("api/v1/shop")
    override suspend fun hours(staffId: String?, outletId: String?): HoursResponse =
        get("api/v1/shop/hours", buildMap { staffId?.let { put("staff", it) }; outletId?.let { put("outlet", it) } })
    override suspend fun saveHours(write: HoursWrite): HoursResponse = put("api/v1/shop/hours", write)
    override suspend fun rules(): BookingRules = get("api/v1/shop/rules")
    override suspend fun saveRules(rules: BookingRules): BookingRules = put("api/v1/shop/rules", rules)
    override suspend fun policy(): PolicyResponse = get("api/v1/shop/policy")
    override suspend fun savePolicy(write: PolicyWrite): PolicyResponse = put("api/v1/shop/policy", write)
    override suspend fun reminders(): RemindersResponse = get("api/v1/shop/reminders")
    override suspend fun saveReminders(write: RemindersWrite): RemindersResponse = put("api/v1/shop/reminders", write)
    override suspend fun team(): TeamResponse = get("api/v1/shop/team")
    override suspend fun teamPerson(id: String): TeamPersonResponse = get("api/v1/shop/team/$id")
    override suspend fun addTeamPerson(write: TeamPersonWrite): TeamPersonResponse = post("api/v1/shop/team", write)
    override suspend fun updateTeamPerson(id: String, write: TeamPersonWrite): TeamPersonResponse = put("api/v1/shop/team/$id", write)
    override suspend fun removeTeamPerson(id: String): TeamPersonResponse = delete("api/v1/shop/team/$id")
    override suspend fun invite(staffId: String, email: String?): InviteResponse = post("api/v1/shop/team/$staffId/invite", InviteWrite(email))
    override suspend fun setOwner(staffId: String, owner: Boolean): TeamPersonResponse = put("api/v1/shop/team/$staffId/owner", OwnerWrite(owner))
    override suspend fun outlets(): OutletsResponse = get("api/v1/shop/outlets")
    override suspend fun outlet(id: String): OutletResponse = get("api/v1/shop/outlets/$id")
    override suspend fun addOutlet(write: OutletWrite): OutletResponse = post("api/v1/shop/outlets", write)
    override suspend fun updateOutlet(id: String, write: OutletWrite): OutletResponse = put("api/v1/shop/outlets/$id", write)

    // Leaving

    override suspend fun deleteLogin(password: String): LoginDeleted = post("api/v1/me/delete", PasswordWrite(password))
    override suspend fun shopClosing(): ShopClosing = get("api/v1/shop/close")
    override suspend fun closeShop(password: String, confirm: String): ShopCloseResult = post("api/v1/shop/close", CloseShopWrite(password, confirm))
    override suspend fun agreeToClose(password: String): ShopCloseResult = post("api/v1/shop/close/agree", PasswordWrite(password))
    override suspend fun refuseToClose() { post<Map<String, String>, Ack>("api/v1/shop/close/refuse", emptyMap()) }
    override suspend fun withdrawClose() { delete<Ack>("api/v1/shop/close") }

    // The waiting list and gaps

    override suspend fun waitlist(): WaitlistResponse = get("api/v1/waitlist")
    override suspend fun waitlistOptions(): WaitlistOptions = get("api/v1/waitlist/options")
    override suspend fun joinWaitlist(request: WaitlistJoinRequest): WaitlistJoined = post("api/v1/waitlist", request)
    override suspend fun leaveWaitlist(id: String) { delete<Ack>("api/v1/waitlist/$id") }
    override suspend fun gap(staffId: String, from: Instant, to: Instant): GapResponse =
        get("api/v1/gaps", mapOf("staff" to staffId, "from" to instant(from), "to" to instant(to)))
    override suspend fun offerGap(staffId: String, from: Instant, to: Instant, entryIds: List<String>): OfferSent =
        post("api/v1/gaps/offer", OfferRequest(staffId, instant(from), instant(to), entryIds))

    // The till and money

    override suspend fun checkout(bookingId: String): CheckoutResponse = get("api/v1/checkout/$bookingId")
    override suspend fun settle(bookingId: String, request: TillRequest): SettledResponse = post("api/v1/checkout/$bookingId", request)
    override suspend fun money(): MoneyResponse = get("api/v1/money")

    // endregion
    // region Plumbing

    suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap()): T =
        send("GET", path, query.toList(), null, serializer<T>())

    suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        send("POST", path, emptyList(), ChairtimeJson.encodeToString(serializer<B>(), body), serializer<T>())

    suspend inline fun <reified B, reified T> put(path: String, body: B): T =
        send("PUT", path, emptyList(), ChairtimeJson.encodeToString(serializer<B>(), body), serializer<T>())

    suspend inline fun <reified T> delete(path: String, query: Map<String, String> = emptyMap()): T =
        send("DELETE", path, query.toList(), null, serializer<T>())

    /** Send it, and where the trouble looks like it will pass, send it again
     *  before saying anything. What the screen finally sees is either the
     *  answer or the last refusal. */
    @PublishedApi
    internal suspend fun <T> send(
        method: String, path: String, query: List<Pair<String, String>>, body: String?, reply: KSerializer<T>,
    ): T {
        var attempt = 1
        var pause = retry.firstWait
        while (true) {
            try {
                return once(method, path, query, body, reply)
            } catch (error: ApiError) {
                val worthAnotherGo = method == "GET" && error.isPassing && attempt < retry.attempts
                if (!worthAnotherGo) throw error
                coroutineContext.ensureActive()
                wait(error.askedToWait ?: pause)
                pause *= 2
                attempt += 1
            }
        }
    }

    private suspend fun <T> once(
        method: String, path: String, query: List<Pair<String, String>>, body: String?, reply: KSerializer<T>,
    ): T {
        val token = tokens.read() ?: throw ApiError.Unauthorized("Sign in to carry on.")

        val url = url(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody(JSON) ?: if (method == "POST" || method == "PUT") "".toRequestBody(null) else null)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("X-Wunderhand-Client", clientHeader)
            .apply { tenantId?.let { header("X-Tenant-Id", it) } }
            .build()

        val answer = perform(request)
        if (!answer.isSuccessful) {
            ApiError.passing(answer.status, answer.header("Retry-After"))?.let { throw it }
            val error = ApiError.from(answer.status, answer.body)
            if (error is ApiError.Unauthorized) tokens.write(null)
            throw error
        }
        val text = answer.body

        return try {
            ChairtimeJson.decodeFromString(reply, text)
        } catch (error: SerializationException) {
            /* A shape this build cannot read. Said plainly to the person, and
             * loudly to whoever is debugging — it means the API and the app
             * have drifted, which the shared fixtures exist to prevent. */
            log("[ApiClient] could not decode ${reply.descriptor.serialName} from $path: ${error.message}")
            throw ApiError.Server(ApiError.SERVER_MESSAGE)
        }
    }

    private fun url(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    /** A whole answer, read to the end before it leaves the I/O thread. */
    private class Reply(val status: Int, private val headers: Headers, val body: String) {
        val isSuccessful get() = status in 200..299
        fun header(name: String): String? = headers[name]
    }

    /**
     * OkHttp's call, as a suspending one that a cancelled screen cancels.
     *
     * The body is read here, on the I/O dispatcher, and only then handed back.
     * A `Response` passed to the caller would have its body read on the
     * caller's thread — the main thread — and Android kills an app for
     * touching a socket there. It only shows when the body comes in chunks,
     * which is why it has to be impossible rather than merely avoided.
     */
    private suspend fun perform(request: Request): Reply = withContext(Dispatchers.IO) {
        val response = suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                // Cancelled between the answer arriving and anybody taking it: close it, or the connection leaks.
                override fun onResponse(call: Call, response: Response) =
                    continuation.resume(response) { _, unused, _ -> unused.close() }
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(ApiError.transport(e))
                }
            })
        }
        try {
            response.use { Reply(it.code, it.headers, it.body.string()) }
        } catch (e: IOException) {
            // The connection went while the answer was still arriving.
            throw ApiError.transport(e)
        }
    }

    // endregion

    companion object {
        private val JSON = "application/json".toMediaType()
        private val INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** An instant as chairtime reads it, and as the iPhone writes it: UTC, to the millisecond. */
        fun instant(value: Instant): String = INSTANT.format(value)

        /** No cookies, no cache, and an answer within twenty seconds or none. */
        fun makeHttp(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** A reply whose body the app does not need, only that it succeeded. */
@kotlinx.serialization.Serializable
class Ack
