package com.wunderhand.network

/**
 * Where the session token lives. It is the whole credential, so on a phone it
 * is kept encrypted under a key the Android Keystore holds (the app module's
 * `KeystoreTokenStore`); tests keep it in memory.
 */
interface TokenStore {
    fun read(): String?
    fun write(token: String?)
}

class InMemoryTokenStore(private var token: String? = null) : TokenStore {
    @Synchronized override fun read(): String? = token
    @Synchronized override fun write(token: String?) { this.token = token }
}

/**
 * The part of the client the session hangs off: who is signed in, and for
 * which shop. Its own interface so the app's session logic can be tested
 * against something that answers on cue.
 */
interface SessionApi {
    val hasToken: Boolean
    var tenantId: String?
    suspend fun signIn(email: String, password: String)
    suspend fun signOut()
    suspend fun me(): com.wunderhand.core.Me
}

/** The day, and one appointment in it. */
interface DiaryApi {
    /** The diary screen for a day, as the shop's calendar date; today when null. */
    suspend fun diary(date: String?): com.wunderhand.core.DiaryResponse
    suspend fun appointment(id: String): com.wunderhand.core.AppointmentResponse
}

/**
 * Running the day: what can be done to an appointment, and to the time around
 * it. Nothing here checks a rule first — chairtime decides, and a refusal
 * comes back as a sentence.
 */
interface ActionsApi {
    suspend fun close(appointmentId: String, outcome: com.wunderhand.core.CloseOutcome): com.wunderhand.core.CloseResponse
    /** Where it could move to: the next open days with the same person. */
    suspend fun slots(appointmentId: String, from: String? = null): com.wunderhand.core.SlotsResponse
    suspend fun move(appointmentId: String, to: java.time.Instant)
    suspend fun resize(appointmentId: String, endsAt: java.time.Instant)
    suspend fun recordConsent(appointmentId: String)
    suspend fun startRepeat(appointmentId: String, intervalWeeks: Int): com.wunderhand.core.RepeatStarted
    suspend fun stopRepeat(appointmentId: String, cancelUpcoming: Boolean): com.wunderhand.core.RepeatStopped
    suspend fun blockTime(request: com.wunderhand.core.BlockRequest): com.wunderhand.core.BlockCreated
    suspend fun unblock(id: String)
}

/** A new booking, a step at a time. */
interface BookingApi {
    suspend fun bookingServices(): com.wunderhand.core.BookingServicesResponse
    suspend fun bookingService(id: String): com.wunderhand.core.BookingServiceResponse
    /** The next open days for this person, with the extras' time in and each time priced. */
    suspend fun bookingSlots(serviceId: String, staffId: String, addonIds: List<String>, from: String? = null): com.wunderhand.core.BookingSlotsResponse
    suspend fun book(serviceId: String, staffId: String, startsAt: java.time.Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean): com.wunderhand.core.BookingCreated
}

/** Everything the app asks of chairtime. `ApiClient` is the real one. */
interface WunderhandApi : SessionApi, DiaryApi, ActionsApi, BookingApi
