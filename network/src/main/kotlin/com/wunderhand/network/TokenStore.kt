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
    /** A right password, which for a login with two-step sign-in on is not a
     *  session yet — see [SignIn]. */
    suspend fun signIn(email: String, password: String): SignIn
    /** The second step: a code from the authenticator app, or one of the ten
     *  backup codes. Only reachable with a [Pending] from the password step. */
    suspend fun signIn(code: String, from: Pending, kind: TwoFactorCode = TwoFactorCode.Authenticator)
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
    /** What has happened to the booking behind an appointment: who changed it, and what was sent. */
    suspend fun history(appointmentId: String): com.wunderhand.core.HistoryResponse
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
    /** Somebody for an appointment that had nobody on it: the appointment comes back with them on it. */
    suspend fun attachClient(appointmentId: String, input: com.wunderhand.core.AttachClientRequest): com.wunderhand.core.AppointmentResponse
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
    suspend fun book(serviceId: String, staffId: String, startsAt: java.time.Instant, clientId: String?, addonIds: List<String>, overridePrerequisite: Boolean, outletId: String? = null, visit: com.wunderhand.core.BookingRequest.Visit? = null): com.wunderhand.core.BookingCreated
    /** What travelling to an address costs, before anything is booked. A POST: the address stays out of the URL. */
    suspend fun visitQuote(request: com.wunderhand.core.VisitQuoteRequest): com.wunderhand.core.VisitQuote
    /** Where a client was last visited, to offer again. Its address is null when they never have been. */
    suspend fun clientVisitAddress(clientId: String): com.wunderhand.core.ClientVisitAddressResponse
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
    /** A photograph for the booking page: a JPEG the app has already brought down to size (ServicePhoto). Owner only. */
    suspend fun uploadServicePhoto(serviceId: String, jpeg: ByteArray): com.wunderhand.core.ServicePhotoResponse
    suspend fun removeServicePhoto(serviceId: String): com.wunderhand.core.ServicePhotoResponse
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
    suspend fun consent(): com.wunderhand.core.ConsentResponse
    /** Every save is a new version; the one before it is kept for whoever signed it. */
    suspend fun saveConsent(kind: String, body: String): com.wunderhand.core.ConsentDocument
    suspend fun quietTimes(): com.wunderhand.core.QuietTimesResponse
    /** Take a suggestion: one part of one weekday, priced down. Each of these answers with the whole screen. */
    suspend fun acceptQuietTime(weekday: Int, part: String, percent: Int): com.wunderhand.core.QuietTimesResponse
    suspend fun showSaving(ruleId: String, show: Boolean): com.wunderhand.core.QuietTimesResponse
    suspend fun stopQuietTime(ruleId: String): com.wunderhand.core.QuietTimesResponse
    suspend fun offers(): com.wunderhand.core.OffersResponse
    suspend fun startOffer(write: com.wunderhand.core.OfferWrite): com.wunderhand.core.OffersResponse
    suspend fun stopOffer(id: String): com.wunderhand.core.OffersResponse
    /** A person's own diary as a link for a calendar app: anybody's, not only an owner's. */
    suspend fun calendarLink(): com.wunderhand.core.CalendarLinkResponse
    /** Makes a link, switching off the one before it. The address comes back this once. */
    suspend fun makeCalendarLink(): com.wunderhand.core.CalendarLinkResponse
    suspend fun stopCalendarLink(): com.wunderhand.core.CalendarLinkResponse

    /** Your page: what a stranger sees. Each change answers with the whole page. */
    suspend fun page(): com.wunderhand.core.PageResponse
    suspend fun savePage(write: com.wunderhand.core.PageWrite): com.wunderhand.core.PageResponse
    suspend fun publishPage(published: Boolean): com.wunderhand.core.PageResponse
    /** @param kind `cover` or `logo`. @param type what [image] is: a JPEG, or a logo's own PNG. */
    suspend fun uploadPageImage(kind: String, image: ByteArray, type: String = "image/jpeg"): com.wunderhand.core.PageResponse
    suspend fun removePageImage(kind: String): com.wunderhand.core.PageResponse
    suspend fun setAddressPrivate(outletId: String, isPrivate: Boolean): com.wunderhand.core.PageResponse
    suspend fun addWorkPhoto(jpeg: ByteArray, caption: String?, staffId: String?): com.wunderhand.core.PageResponse
    suspend fun removeWorkPhoto(id: String): com.wunderhand.core.PageResponse

    /** Your website: its design, the sides of the business, and a domain of its own. Each change answers with the whole screen. */
    suspend fun site(): com.wunderhand.core.SiteResponse
    suspend fun chooseDesign(template: String): com.wunderhand.core.SiteResponse
    suspend fun addSide(name: String): com.wunderhand.core.SiteResponse
    suspend fun saveSide(id: String, write: com.wunderhand.core.SideWrite): com.wunderhand.core.SiteResponse
    suspend fun removeSide(id: String): com.wunderhand.core.SiteResponse
    /** @param direction `up` or `down`. */
    suspend fun moveSide(id: String, direction: String): com.wunderhand.core.SiteResponse
    suspend fun addDomain(domain: String): com.wunderhand.core.SiteResponse
    suspend fun removeDomain(id: String): com.wunderhand.core.SiteResponse
    /** Looks at the DNS again, or finishes a connection that is verified. */
    suspend fun checkDomain(id: String): com.wunderhand.core.SiteResponse

    /** Time off: whole days away, and the hours blocked from the diary beside them. Anybody's, for their own days. */
    suspend fun timeOff(): com.wunderhand.core.TimeOffResponse
    /** What is already booked in those days. Changes nothing: asked before [takeTimeOff], and shown. */
    suspend fun checkTimeOff(write: com.wunderhand.core.TimeOffWrite): com.wunderhand.core.TimeOffCheck
    /** Appointments already in the days stay put. Answers with the list. */
    suspend fun takeTimeOff(write: com.wunderhand.core.TimeOffWrite): com.wunderhand.core.TimeOffResponse
    /** Takes days back: a line's request and its dates, and [staffId] for one person out of several. Answers with the list. */
    suspend fun removeTimeOff(seriesId: String, from: String, to: String, staffId: String? = null): com.wunderhand.core.TimeOffResponse
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
