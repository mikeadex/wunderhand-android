package com.wunderhand.core

/** The fixtures chairtime writes from its own real responses. */
object Fixtures {
    val names = listOf(
        "appointment", "booking-service-outlets", "booking-service", "booking-services", "booking-slots", "checkout", "client",
        "clients", "diary", "gap", "health", "hours", "me", "menu-extras", "menu-options",
        "menu-service-steps", "menu-service", "menu", "money", "offer-sent", "outlet", "outlets",
        "policy", "reminders", "rules", "shop", "team-invited", "team-person", "team", "waitlist",
    )

    fun text(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) {
            "no fixture called $name — run scripts/sync-api-fixtures.sh"
        }.bufferedReader().use { it.readText() }
}
