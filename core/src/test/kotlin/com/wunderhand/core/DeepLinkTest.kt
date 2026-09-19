package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

/** Links into the app. The iOS cases, one for one, and Android's own for what a push carries. */
class DeepLinkTest {
    private val id = "fb67befa-8f43-4692-8472-e17d2df6ba35"
    private val shop = "0d0c3a2e-5b1f-4c59-9f43-6f1d2a7b8c90"

    @Test fun `the app's own scheme opens an appointment`() {
        assertEquals(DeepLink.Appointment(id, shop), DeepLink.from("wunderhand://appointment/$id?shop=$shop"))
        assertEquals(DeepLink.Appointment(id, null), DeepLink.from("wunderhand://appointment/$id"))
    }

    @Test fun `the web's address for an appointment opens it too`() {
        assertEquals(DeepLink.Appointment(id, null), DeepLink.from("https://wunderhand.com/diary/$id"))
        assertEquals(DeepLink.Appointment(id, null), DeepLink.from("https://www.wunderhand.com/diary/${id.uppercase()}"))
    }

    @Test fun `anything else is not a link`() {
        for (raw in listOf(
            "wunderhand://appointment/not-an-id", "wunderhand://clients/$id", "https://wunderhand.com/diary", "https://wunderhand.com/diary/block",
            "https://wunderhand.com/shop/billing", "https://evil.example/diary/$id", "http://wunderhand.com/diary/$id",
            // A host dressed up as ours, and a path that tries to climb.
            "https://wunderhand.com@evil.example/diary/$id", "https://wunderhand.com.evil.example/diary/$id", "wunderhand://appointment/$id/../../shop",
            "intent://appointment/$id", "", "not a url at all",
        )) assertNull(raw, DeepLink.from(raw))
        assertNull(DeepLink.from(null))
    }

    @Test fun `a push carries an appointment and its shop`() {
        assertEquals(DeepLink.Appointment(id, shop), DeepLink.appointment(id, shop))
        assertEquals(DeepLink.Appointment(id, null), DeepLink.appointment(id, "junk"))
        assertNull(DeepLink.appointment(null, shop))
        assertNull(DeepLink.appointment("../../shop", null))
    }

    @Test fun `a cancellation's gap opens with its time`() {
        val link = DeepLink.gap(id, "2026-09-18T09:00:00.000Z", "2026-09-18T09:45:00.000Z", shop) as DeepLink.Gap
        assertEquals(id, link.staffId)
        assertEquals(shop, link.shopId)
        assertEquals(Duration.ofMinutes(45), Duration.between(link.from, link.to))
        assertNotNull(DeepLink.gap(id, "2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z", null))
    }

    @Test fun `a gap that makes no sense is not a link`() {
        assertNull(DeepLink.gap("../shop", "2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z", null))
        assertNull(DeepLink.gap(id, "2026-09-18T10:00:00Z", "2026-09-18T09:00:00Z", null))
        assertNull(DeepLink.gap(id, "2026-09-18T09:00:00Z", "2026-09-20T09:00:00Z", null))
        assertNull(DeepLink.gap(id, "tomorrow", "2026-09-18T09:00:00Z", null))
    }

    @Test fun `a link waits through a sign-in, and is checked again when it comes back`() {
        val appointment = DeepLink.Appointment(id, shop)
        val gap = DeepLink.gap(id, "2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z", null)!!
        assertEquals(appointment, DeepLink.unpack(appointment.packed()))
        assertEquals(gap, DeepLink.unpack(gap.packed()))
        assertNull(DeepLink.unpack(listOf("appointment", "../../shop", "")))
        assertNull(DeepLink.unpack(listOf("billing", id, "")))
        assertNull(DeepLink.unpack(null))
    }

    // What chairtime sends (lib/push/notify.ts), as FCM hands it over.
    private val booked = mapOf("title" to "New booking", "body" to "Wren · Skin fade · today at 11:00", "kind" to "booked", "appointmentId" to id, "tenantId" to shop)
    private val cancelled = booked + mapOf("title" to "Cancelled", "kind" to "cancelled", "staffId" to id, "startsAt" to "2026-09-18T09:00:00.000Z", "endsAt" to "2026-09-18T09:45:00.000Z")

    @Test fun `a booking goes to one channel and a cancellation to the other`() {
        val push = PushPayload.from(booked)!!
        assertEquals(PushPayload.Channel.Bookings, push.channel)
        assertEquals(DeepLink.Appointment(id, shop), push.open)
        assertNull(push.gap)

        val gone = PushPayload.from(cancelled)!!
        assertEquals(PushPayload.Channel.Cancellations, gone.channel)
        assertEquals(Duration.ofMinutes(45), (gone.gap as DeepLink.Gap).let { Duration.between(it.from, it.to) })
        assertEquals(shop, gone.group)
    }

    @Test fun `the words are chairtime's, and the app adds nothing to them`() {
        val push = PushPayload.from(booked)!!
        assertEquals("New booking", push.title)
        assertEquals("Wren · Skin fade · today at 11:00", push.body)
    }

    @Test fun `news with no words is no notification, and news this build has not heard of is still news`() {
        assertNull(PushPayload.from(booked - "title"))
        assertNull(PushPayload.from(booked + ("body" to "   ")))
        assertNull(PushPayload.from(emptyMap()))
        val later = PushPayload.from(booked + ("kind" to "rescheduled"))!!
        assertEquals(PushPayload.Channel.Bookings, later.channel)
    }

    @Test fun `a crafted push opens nothing`() {
        val push = PushPayload.from(booked + mapOf("appointmentId" to "../../shop/close", "staffId" to "x", "startsAt" to "now"))!!
        assertNull(push.open)
        assertNull(push.gap)
        assertEquals(400, PushPayload.from(booked + ("body" to "x".repeat(5000)))!!.body.length)
    }

    @Test fun `the same news twice replaces itself, and different news does not`() {
        assertEquals(PushPayload.from(booked)!!.notificationId, PushPayload.from(booked + ("body" to "again"))!!.notificationId)
        assertNotEquals(PushPayload.from(booked)!!.notificationId, PushPayload.from(cancelled)!!.notificationId)
    }

    @Test fun `a phone registers as android, with no environment to name`() {
        assertEquals("""{"token":"fcm:tok_en-1","appVersion":"1.0 (1)","platform":"android"}""", ChairtimeJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration("fcm:tok_en-1", "1.0 (1)")))
        assertEquals("""{"token":"fcm:tok_en-1","platform":"android"}""", ChairtimeJson.encodeToString(DeviceRelease.serializer(), DeviceRelease("fcm:tok_en-1")))
    }
}
