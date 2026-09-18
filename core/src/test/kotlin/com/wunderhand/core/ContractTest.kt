package com.wunderhand.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API's promises, read from the fixtures chairtime writes from its own
 * real responses (scripts/sync-api-fixtures.sh). A rename on either side
 * fails here first. The iOS app's tests read the same files.
 */
class ContractTest {
    @Test fun `every fixture is here and is JSON`() {
        for (name in Fixtures.names) {
            ChairtimeJson.parseToJsonElement(Fixtures.text(name))
        }
        assertEquals(29, Fixtures.names.size)
    }

    @Test fun `me decodes from chairtime's own response`() {
        val me = ChairtimeJson.decodeFromString(Me.serializer(), Fixtures.text("me"))
        assertEquals("Kit Alvarez", me.staff.name)
        assertEquals("Fold Barbers", me.shop.name)
        assertEquals("Europe/London", me.shop.timezone)
        assertEquals("GBP", me.shop.currency)
        assertEquals(TenantStatus.Active, me.shop.status)
        assertTrue(me.shops.any { it.id == me.shop.id })
    }

    /** A state added on the server must not stop an older app opening. */
    @Test fun `an unknown shop status still decodes`() {
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("me")).jsonObject
        val shop = JsonObject(json.getValue("shop").jsonObject + ("status" to JsonPrimitive("archived")))
        val me = ChairtimeJson.decodeFromJsonElement(Me.serializer(), JsonObject(json + ("shop" to shop)))
        assertEquals(TenantStatus.Unknown("archived"), me.shop.status)
    }

    /** And a field added on the server is none of an older app's business. */
    @Test fun `a field this build has not heard of is ignored`() {
        val json = ChairtimeJson.parseToJsonElement(Fixtures.text("me")).jsonObject
        val me = ChairtimeJson.decodeFromJsonElement(
            Me.serializer(), JsonObject(json + ("somethingNew" to JsonPrimitive(true))),
        )
        assertEquals("kit@fold.example", me.user.email)
    }
}
