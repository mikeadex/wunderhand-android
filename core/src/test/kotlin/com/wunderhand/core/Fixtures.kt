package com.wunderhand.core

/** The fixtures chairtime writes from its own real responses. */
object Fixtures {
    val names = listOf(
        "appointment", "appointment-history", "appointment-visit", "booking-service-outlets", "booking-service", "booking-services", "booking-slots", "booking-visit-outside",
        "booking-visit", "calendar", "checkout", "client-visit-address-none", "client-visit-address", "client",
        "clients", "consent-document", "consent", "diary", "gap", "health", "hours", "me", "menu-extras", "menu-options",
        "menu-service-steps", "menu-service", "menu", "money", "offer-sent", "offers-started", "offers", "outlet", "outlets", "page",
        "policy", "quiet-times-busy", "quiet-times", "reminders", "rules", "shop", "site-domain-pointing", "site", "team-invited", "team-person", "team", "time-off-check", "time-off", "waitlist",
    )

    fun text(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) {
            "no fixture called $name — run scripts/sync-api-fixtures.sh"
        }.bufferedReader().use { it.readText() }
}
