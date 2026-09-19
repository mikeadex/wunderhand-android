package com.wunderhand.app

import com.wunderhand.app.push.PushCoordinator
import com.wunderhand.app.push.PushTokens
import com.wunderhand.core.DeepLink
import com.wunderhand.network.ApiError
import com.wunderhand.network.DevicesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Phone(override val isConfigured: Boolean = true, var current: String? = "tok-1") : PushTokens {
    var asked = 0
    var forgotten = 0
    override suspend fun token(): String? { asked++; return current }
    override suspend fun forget() { forgotten++; current = "tok-next" }
}

private class Chairtime(var refuse: Boolean = false) : DevicesApi {
    val calls = mutableListOf<String>()
    override suspend fun registerDevice(token: String, appVersion: String) { if (refuse) throw ApiError.Offline(); calls += "register $token $appVersion" }
    override suspend fun releaseDevice(token: String) { if (refuse) throw ApiError.Offline(); calls += "release $token" }
}

class PushCoordinatorTest {
    @Test fun `chairtime hears once for a token and a shop, and again when the shop changes`() = runTest {
        val api = Chairtime()
        val push = PushCoordinator(Phone(), "1.0 (1)", mayNotify = { true })
        push.diaryLoaded(api, "shop-a"); push.diaryLoaded(api, "shop-a"); push.diaryLoaded(api, "shop-b")
        assertEquals(listOf("register tok-1 1.0 (1)", "register tok-1 1.0 (1)"), api.calls)
    }

    @Test fun `a phone that would swallow the news is not registered, and no token is asked for`() = runTest {
        val api = Chairtime()
        val phone = Phone()
        var allowed = false
        val push = PushCoordinator(phone, "1.0", mayNotify = { allowed })
        push.diaryLoaded(api, "shop-a")
        assertTrue(api.calls.isEmpty())
        assertEquals(0, phone.asked)
        // They said yes: the next load registers.
        allowed = true
        push.diaryLoaded(api, "shop-a")
        assertEquals(1, api.calls.size)
    }

    @Test fun `a build that has never heard of Firebase does nothing, and nothing fails`() = runTest {
        val api = Chairtime()
        val phone = Phone(isConfigured = false)
        val push = PushCoordinator(phone, "1.0", mayNotify = { true })
        push.diaryLoaded(api, "shop-a")
        push.signingOut(api)
        assertTrue(api.calls.isEmpty())
        assertEquals(0, phone.asked)
        assertEquals(0, phone.forgotten)
    }

    @Test fun `a refusal is tried again on the next load`() = runTest {
        val api = Chairtime(refuse = true)
        val push = PushCoordinator(Phone(), "1.0", mayNotify = { true })
        push.diaryLoaded(api, "shop-a")
        api.refuse = false
        push.diaryLoaded(api, "shop-a")
        assertEquals(listOf("register tok-1 1.0"), api.calls)
    }

    @Test fun `a new token goes to chairtime for the shop that is open`() = runTest {
        val api = Chairtime()
        val push = PushCoordinator(Phone(), "1.0", mayNotify = { true })
        // Before anybody is signed in there is nobody to register it for.
        push.tokenChanged("tok-0")
        assertTrue(api.calls.isEmpty())
        push.diaryLoaded(api, "shop-a")
        push.tokenChanged("tok-2")
        assertEquals(listOf("register tok-0 1.0", "register tok-2 1.0"), api.calls)
    }

    @Test fun `signing out takes the phone off the list, then throws the token away`() = runTest {
        val api = Chairtime()
        val phone = Phone()
        val push = PushCoordinator(phone, "1.0", mayNotify = { true })
        push.diaryLoaded(api, "shop-a")
        push.follow(DeepLink.appointment("fb67befa-8f43-4692-8472-e17d2df6ba35", null))
        push.signingOut(api)
        assertEquals("release tok-1", api.calls.last())
        assertEquals(1, phone.forgotten)
        assertNull(push.pendingLink.value)
        // Whoever signs in next gets a token of their own.
        push.diaryLoaded(api, "shop-a")
        assertEquals("register tok-next 1.0", api.calls.last())
    }

    @Test fun `with no signal on the way out the token still goes, so nothing more arrives for whoever left`() = runTest {
        val api = Chairtime()
        val phone = Phone()
        val push = PushCoordinator(phone, "1.0", mayNotify = { true })
        push.diaryLoaded(api, "shop-a")
        api.refuse = true
        push.signingOut(api)
        assertEquals(1, phone.forgotten)
    }

    @Test fun `a link waits until it is followed`() = runTest {
        val push = PushCoordinator(Phone(), "1.0", mayNotify = { true })
        val link = DeepLink.appointment("fb67befa-8f43-4692-8472-e17d2df6ba35", null)
        push.follow(null)
        assertNull(push.pendingLink.value)
        push.follow(link)
        assertEquals(link, push.pendingLink.value)
        push.followed()
        assertNull(push.pendingLink.value)
    }
}
