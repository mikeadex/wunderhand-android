package com.wunderhand.app.support

import com.wunderhand.core.AppointmentResponse
import com.wunderhand.core.BlockCreated
import com.wunderhand.core.BlockRequest
import com.wunderhand.core.CloseOutcome
import com.wunderhand.core.CloseResponse
import com.wunderhand.core.DiaryResponse
import com.wunderhand.core.Me
import com.wunderhand.core.RepeatStarted
import com.wunderhand.core.RepeatStopped
import com.wunderhand.core.SlotsResponse
import com.wunderhand.network.WunderhandApi
import java.time.Instant

/**
 * A chairtime that has been asked nothing yet. A test overrides the calls it
 * is about; any other call is a test asking for something it did not expect,
 * and says so.
 */
open class StubApi : WunderhandApi {
    /** Every call made, in order: "move a1 2026-09-16T10:15:00Z". */
    val calls = mutableListOf<String>()
    private fun unexpected(what: String): Nothing = error("the test did not expect $what")

    override var hasToken: Boolean = true
    override var tenantId: String? = null
    override suspend fun signIn(email: String, password: String) { hasToken = true }
    override suspend fun signOut() { hasToken = false }
    override suspend fun me(): Me = unexpected("me()")
    override suspend fun diary(date: String?): DiaryResponse = unexpected("diary($date)")
    override suspend fun appointment(id: String): AppointmentResponse = unexpected("appointment($id)")
    override suspend fun close(appointmentId: String, outcome: CloseOutcome): CloseResponse = unexpected("close")
    override suspend fun slots(appointmentId: String, from: String?): SlotsResponse = unexpected("slots")
    override suspend fun move(appointmentId: String, to: Instant) { unexpected("move") }
    override suspend fun resize(appointmentId: String, endsAt: Instant) { unexpected("resize") }
    override suspend fun recordConsent(appointmentId: String) { unexpected("recordConsent") }
    override suspend fun startRepeat(appointmentId: String, intervalWeeks: Int): RepeatStarted = unexpected("startRepeat")
    override suspend fun stopRepeat(appointmentId: String, cancelUpcoming: Boolean): RepeatStopped = unexpected("stopRepeat")
    override suspend fun blockTime(request: BlockRequest): BlockCreated = unexpected("blockTime")
    override suspend fun unblock(id: String) { unexpected("unblock") }
    override suspend fun bookingServices(): com.wunderhand.core.BookingServicesResponse = unexpected("bookingServices")
    override suspend fun bookingService(id: String): com.wunderhand.core.BookingServiceResponse = unexpected("bookingService")
    override suspend fun bookingSlots(serviceId: String, staffId: String, addonIds: List<String>, from: String?): com.wunderhand.core.BookingSlotsResponse = unexpected("bookingSlots")
    override suspend fun book(serviceId: String, staffId: String, startsAt: Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean): com.wunderhand.core.BookingCreated = unexpected("book")
    override suspend fun clients(filter: com.wunderhand.core.ClientFilter, query: String): com.wunderhand.core.ClientsResponse = unexpected("clients")
    override suspend fun client(id: String): com.wunderhand.core.ClientProfileResponse = unexpected("client")
    override suspend fun createClient(input: com.wunderhand.core.ClientInput): com.wunderhand.core.ClientSaved = unexpected("createClient")
    override suspend fun updateClient(id: String, input: com.wunderhand.core.ClientInput): com.wunderhand.core.ClientSaved = unexpected("updateClient")
    override suspend fun removeClient(id: String) { unexpected("removeClient") }
    override suspend fun health(clientId: String): com.wunderhand.core.HealthResponse = unexpected("health")
    override suspend fun saveHealth(clientId: String, record: Map<String, String>): com.wunderhand.core.HealthSaved = unexpected("saveHealth")
    override suspend fun eraseHealth(clientId: String, confirmation: String) { unexpected("eraseHealth") }
}
