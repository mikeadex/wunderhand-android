package com.wunderhand.network

import com.wunderhand.core.AppointmentResponse
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.DiaryResponse
import com.wunderhand.core.Me
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

    // endregion
    // region Plumbing

    suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap()): T =
        send("GET", path, query, null, serializer<T>())

    suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        send("POST", path, emptyMap(), ChairtimeJson.encodeToString(serializer<B>(), body), serializer<T>())

    suspend inline fun <reified B, reified T> put(path: String, body: B): T =
        send("PUT", path, emptyMap(), ChairtimeJson.encodeToString(serializer<B>(), body), serializer<T>())

    suspend inline fun <reified T> delete(path: String, query: Map<String, String> = emptyMap()): T =
        send("DELETE", path, query, null, serializer<T>())

    /** Send it, and where the trouble looks like it will pass, send it again
     *  before saying anything. What the screen finally sees is either the
     *  answer or the last refusal. */
    @PublishedApi
    internal suspend fun <T> send(
        method: String, path: String, query: Map<String, String>, body: String?, reply: KSerializer<T>,
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
        method: String, path: String, query: Map<String, String>, body: String?, reply: KSerializer<T>,
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
