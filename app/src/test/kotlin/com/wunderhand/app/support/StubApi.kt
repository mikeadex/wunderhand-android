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
    override suspend fun menu(): com.wunderhand.core.MenuResponse = unexpected("menu")
    override suspend fun menuService(id: String): com.wunderhand.core.MenuServiceResponse = unexpected("menuService")
    override suspend fun menuOptions(): com.wunderhand.core.MenuOptions = unexpected("menuOptions")
    override suspend fun createService(write: com.wunderhand.core.ServiceWrite): com.wunderhand.core.MenuServiceResponse = unexpected("createService")
    override suspend fun updateService(id: String, write: com.wunderhand.core.ServiceWrite): com.wunderhand.core.MenuServiceResponse = unexpected("updateService")
    override suspend fun archiveService(id: String) { unexpected("archiveService") }
    override suspend fun saveSteps(serviceId: String, write: com.wunderhand.core.StepsWrite): com.wunderhand.core.MenuServiceResponse = unexpected("saveSteps")
    override suspend fun savePerformers(serviceId: String, write: com.wunderhand.core.PerformersWrite): com.wunderhand.core.MenuServiceResponse = unexpected("savePerformers")
    override suspend fun extras(serviceId: String): com.wunderhand.core.ExtrasResponse = unexpected("extras")
    override suspend fun saveExtraLinks(serviceId: String, write: com.wunderhand.core.ExtraLinksWrite): com.wunderhand.core.ExtrasResponse = unexpected("saveExtraLinks")
    override suspend fun createExtra(write: com.wunderhand.core.ExtraWrite): com.wunderhand.core.SavedId = unexpected("createExtra")
    override suspend fun updateExtra(id: String, write: com.wunderhand.core.ExtraWrite): com.wunderhand.core.SavedId = unexpected("updateExtra")
    override suspend fun retireExtra(id: String) { unexpected("retireExtra") }
    override suspend fun shop(): com.wunderhand.core.ShopResponse = unexpected("shop")
    override suspend fun hours(staffId: String?, outletId: String?): com.wunderhand.core.HoursResponse = unexpected("hours")
    override suspend fun saveHours(write: com.wunderhand.core.HoursWrite): com.wunderhand.core.HoursResponse = unexpected("saveHours")
    override suspend fun rules(): com.wunderhand.core.BookingRules = unexpected("rules")
    override suspend fun saveRules(rules: com.wunderhand.core.BookingRules): com.wunderhand.core.BookingRules = unexpected("saveRules")
    override suspend fun policy(): com.wunderhand.core.PolicyResponse = unexpected("policy")
    override suspend fun savePolicy(write: com.wunderhand.core.PolicyWrite): com.wunderhand.core.PolicyResponse = unexpected("savePolicy")
    override suspend fun reminders(): com.wunderhand.core.RemindersResponse = unexpected("reminders")
    override suspend fun saveReminders(write: com.wunderhand.core.RemindersWrite): com.wunderhand.core.RemindersResponse = unexpected("saveReminders")
    override suspend fun team(): com.wunderhand.core.TeamResponse = unexpected("team")
    override suspend fun teamPerson(id: String): com.wunderhand.core.TeamPersonResponse = unexpected("teamPerson")
    override suspend fun addTeamPerson(write: com.wunderhand.core.TeamPersonWrite): com.wunderhand.core.TeamPersonResponse = unexpected("addTeamPerson")
    override suspend fun updateTeamPerson(id: String, write: com.wunderhand.core.TeamPersonWrite): com.wunderhand.core.TeamPersonResponse = unexpected("updateTeamPerson")
    override suspend fun removeTeamPerson(id: String): com.wunderhand.core.TeamPersonResponse = unexpected("removeTeamPerson")
    override suspend fun invite(staffId: String, email: String?): com.wunderhand.core.InviteResponse = unexpected("invite")
    override suspend fun setOwner(staffId: String, owner: Boolean): com.wunderhand.core.TeamPersonResponse = unexpected("setOwner")
    override suspend fun outlets(): com.wunderhand.core.OutletsResponse = unexpected("outlets")
    override suspend fun outlet(id: String): com.wunderhand.core.OutletResponse = unexpected("outlet")
    override suspend fun addOutlet(write: com.wunderhand.core.OutletWrite): com.wunderhand.core.OutletResponse = unexpected("addOutlet")
    override suspend fun updateOutlet(id: String, write: com.wunderhand.core.OutletWrite): com.wunderhand.core.OutletResponse = unexpected("updateOutlet")
    override suspend fun deleteLogin(password: String): com.wunderhand.core.LoginDeleted = unexpected("deleteLogin")
    override suspend fun shopClosing(): com.wunderhand.core.ShopClosing = unexpected("shopClosing")
    override suspend fun closeShop(password: String, confirm: String): com.wunderhand.core.ShopCloseResult = unexpected("closeShop")
    override suspend fun agreeToClose(password: String): com.wunderhand.core.ShopCloseResult = unexpected("agreeToClose")
    override suspend fun refuseToClose() { unexpected("refuseToClose") }
    override suspend fun withdrawClose() { unexpected("withdrawClose") }
    override suspend fun waitlist(): com.wunderhand.core.WaitlistResponse = unexpected("waitlist")
    override suspend fun waitlistOptions(): com.wunderhand.core.WaitlistOptions = unexpected("waitlistOptions")
    override suspend fun joinWaitlist(request: com.wunderhand.core.WaitlistJoinRequest): com.wunderhand.core.WaitlistJoined = unexpected("joinWaitlist")
    override suspend fun leaveWaitlist(id: String) { unexpected("leaveWaitlist") }
    override suspend fun gap(staffId: String, from: java.time.Instant, to: java.time.Instant): com.wunderhand.core.GapResponse = unexpected("gap")
    override suspend fun offerGap(staffId: String, from: java.time.Instant, to: java.time.Instant, entryIds: List<String>): com.wunderhand.core.OfferSent = unexpected("offerGap")
    override suspend fun checkout(bookingId: String): com.wunderhand.core.CheckoutResponse = unexpected("checkout")
    override suspend fun settle(bookingId: String, request: com.wunderhand.core.TillRequest): com.wunderhand.core.SettledResponse = unexpected("settle")
    override suspend fun money(): com.wunderhand.core.MoneyResponse = unexpected("money")
    // Telling chairtime about a phone is never what a test is about, and never a reason for one to fail.
    override suspend fun registerDevice(token: String, appVersion: String) { calls += "registerDevice" }
    override suspend fun releaseDevice(token: String) { calls += "releaseDevice" }
}
