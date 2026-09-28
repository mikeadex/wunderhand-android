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
    /** A link into the web that signs the browser in as this person and lands on [path] ("/shop/payments"). Once, for a minute. */
    suspend fun webSession(path: String): com.wunderhand.core.WebSession
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
    /** With an outlet, only the people who work there. */
    suspend fun bookingService(id: String, outletId: String? = null): com.wunderhand.core.BookingServiceResponse
    /** The next open days for this person, with the extras' time in and each time priced — at one outlet's hours when an outlet is named. */
    suspend fun bookingSlots(serviceId: String, staffId: String, addonIds: List<String>, from: String? = null, outletId: String? = null): com.wunderhand.core.BookingSlotsResponse
    suspend fun book(serviceId: String, staffId: String, startsAt: java.time.Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean, outletId: String? = null): com.wunderhand.core.BookingCreated
}

/** Clients, and the medical notes kept apart from them. */
interface ClientsApi {
    suspend fun clients(filter: com.wunderhand.core.ClientFilter = com.wunderhand.core.ClientFilter.All, query: String = ""): com.wunderhand.core.ClientsResponse
    suspend fun client(id: String): com.wunderhand.core.ClientProfileResponse
    suspend fun createClient(input: com.wunderhand.core.ClientInput): com.wunderhand.core.ClientSaved
    suspend fun updateClient(id: String, input: com.wunderhand.core.ClientInput): com.wunderhand.core.ClientSaved
    suspend fun removeClient(id: String)
    /** Opening medical notes. chairtime writes this reading to the access log. */
    suspend fun health(clientId: String): com.wunderhand.core.HealthResponse
    suspend fun saveHealth(clientId: String, record: Map<String, String>): com.wunderhand.core.HealthSaved
    /** @param confirmation the word typed to mean it. chairtime checks it, not the app. */
    suspend fun eraseHealth(clientId: String, confirmation: String)
}

/** The menu: reading it is anybody's, changing it is an owner's. */
interface MenuApi {
    suspend fun menu(): com.wunderhand.core.MenuResponse
    suspend fun menuService(id: String): com.wunderhand.core.MenuServiceResponse
    /** What the service form chooses between. An owner's: anybody else is answered `not_owner`, so they are not asked for. */
    suspend fun menuOptions(): com.wunderhand.core.MenuOptions
    suspend fun createService(write: com.wunderhand.core.ServiceWrite): com.wunderhand.core.MenuServiceResponse
    suspend fun updateService(id: String, write: com.wunderhand.core.ServiceWrite): com.wunderhand.core.MenuServiceResponse
    suspend fun archiveService(id: String)
    suspend fun saveSteps(serviceId: String, write: com.wunderhand.core.StepsWrite): com.wunderhand.core.MenuServiceResponse
    suspend fun savePerformers(serviceId: String, write: com.wunderhand.core.PerformersWrite): com.wunderhand.core.MenuServiceResponse
    suspend fun extras(serviceId: String): com.wunderhand.core.ExtrasResponse
    suspend fun saveExtraLinks(serviceId: String, write: com.wunderhand.core.ExtraLinksWrite): com.wunderhand.core.ExtrasResponse
    suspend fun createExtra(write: com.wunderhand.core.ExtraWrite): com.wunderhand.core.SavedId
    suspend fun updateExtra(id: String, write: com.wunderhand.core.ExtraWrite): com.wunderhand.core.SavedId
    suspend fun retireExtra(id: String)
}

/** The shop's settings, its team and its outlets. */
interface ShopApi {
    suspend fun shop(): com.wunderhand.core.ShopResponse
    suspend fun hours(staffId: String? = null, outletId: String? = null): com.wunderhand.core.HoursResponse
    suspend fun saveHours(write: com.wunderhand.core.HoursWrite): com.wunderhand.core.HoursResponse
    suspend fun rules(): com.wunderhand.core.BookingRules
    suspend fun saveRules(rules: com.wunderhand.core.BookingRules): com.wunderhand.core.BookingRules
    suspend fun policy(): com.wunderhand.core.PolicyResponse
    suspend fun savePolicy(write: com.wunderhand.core.PolicyWrite): com.wunderhand.core.PolicyResponse
    suspend fun reminders(): com.wunderhand.core.RemindersResponse
    suspend fun saveReminders(write: com.wunderhand.core.RemindersWrite): com.wunderhand.core.RemindersResponse
    suspend fun team(): com.wunderhand.core.TeamResponse
    suspend fun teamPerson(id: String): com.wunderhand.core.TeamPersonResponse
    suspend fun addTeamPerson(write: com.wunderhand.core.TeamPersonWrite): com.wunderhand.core.TeamPersonResponse
    suspend fun updateTeamPerson(id: String, write: com.wunderhand.core.TeamPersonWrite): com.wunderhand.core.TeamPersonResponse
    suspend fun removeTeamPerson(id: String): com.wunderhand.core.TeamPersonResponse
    /** @param email null for somebody coming back, whose login still exists. */
    suspend fun invite(staffId: String, email: String?): com.wunderhand.core.InviteResponse
    suspend fun setOwner(staffId: String, owner: Boolean): com.wunderhand.core.TeamPersonResponse
    suspend fun outlets(): com.wunderhand.core.OutletsResponse
    suspend fun outlet(id: String): com.wunderhand.core.OutletResponse
    suspend fun addOutlet(write: com.wunderhand.core.OutletWrite): com.wunderhand.core.OutletResponse
    suspend fun updateOutlet(id: String, write: com.wunderhand.core.OutletWrite): com.wunderhand.core.OutletResponse
}

/** The two ways out. The password is typed again for each, sent once, and kept nowhere. */
interface LeavingApi {
    suspend fun deleteLogin(password: String): com.wunderhand.core.LoginDeleted
    suspend fun shopClosing(): com.wunderhand.core.ShopClosing
    suspend fun closeShop(password: String, confirm: String): com.wunderhand.core.ShopCloseResult
    suspend fun agreeToClose(password: String): com.wunderhand.core.ShopCloseResult
    suspend fun refuseToClose()
    suspend fun withdrawClose()
}

/** The waiting list, and filling a gap from it. */
interface WaitlistApi {
    suspend fun waitlist(): com.wunderhand.core.WaitlistResponse
    suspend fun waitlistOptions(): com.wunderhand.core.WaitlistOptions
    suspend fun joinWaitlist(request: com.wunderhand.core.WaitlistJoinRequest): com.wunderhand.core.WaitlistJoined
    suspend fun leaveWaitlist(id: String)
    /** Who could take a window in somebody's diary. */
    suspend fun gap(staffId: String, from: java.time.Instant, to: java.time.Instant): com.wunderhand.core.GapResponse
    suspend fun offerGap(staffId: String, from: java.time.Instant, to: java.time.Instant, entryIds: List<String>): com.wunderhand.core.OfferSent
}

/** The till, and what the month took. Nothing here moves money: it is the note of money that moved. */
interface MoneyApi {
    suspend fun checkout(bookingId: String): com.wunderhand.core.CheckoutResponse
    suspend fun settle(bookingId: String, request: com.wunderhand.core.TillRequest): com.wunderhand.core.SettledResponse
    /** The shop's for an owner; their own for anybody else — chairtime decides which, and sends only that. */
    suspend fun money(): com.wunderhand.core.MoneyResponse
}

/** Where chairtime sends this phone its news. */
interface DevicesApi {
    /** This phone, for the shop now open. Safe to repeat; whoever signs in on a phone takes its token from whoever had it before. */
    suspend fun registerDevice(token: String, appVersion: String)
    /** Stop sending to this phone, at every shop — on the way out, while the session still stands. */
    suspend fun releaseDevice(token: String)
}

/** Everything the app asks of chairtime. `ApiClient` is the real one. */
interface WunderhandApi : DevicesApi, SessionApi, DiaryApi, ActionsApi, BookingApi, ClientsApi, MenuApi, ShopApi, LeavingApi, WaitlistApi, MoneyApi
