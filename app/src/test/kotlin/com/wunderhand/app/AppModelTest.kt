package com.wunderhand.app

import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.Phase
import com.wunderhand.app.app.Settings
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiError
import com.wunderhand.app.support.StubApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The session, against a server that answers on cue. */
class AppModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val kit: Me = ChairtimeJson.decodeFromString(
        Me.serializer(), checkNotNull(javaClass.getResourceAsStream("/me.json")).bufferedReader().use { it.readText() },
    )
    /** Somebody renting a chair in two places. */
    private val atTwoShops = kit.copy(shops = kit.shops + Me.ShopSummary("shop-2", "Ink & Iron", "ink-and-iron", "staff-2"))

    private class FakeSettings(var shop: String? = null, var server: String? = null) : Settings {
        override suspend fun chosenShopId() = shop
        override suspend fun setChosenShopId(id: String?) { shop = id }
        override suspend fun serverOverride() = server
        override suspend fun setServerOverride(url: String?) { server = url }
    }

    private class FakeApi(var token: Boolean = true, var answer: () -> Me) : StubApi() {
        override var hasToken: Boolean
            get() = token
            set(value) { token = value }
        val askedFor = mutableListOf<String?>()
        override suspend fun signIn(email: String, password: String) { token = true }
        override suspend fun signOut() { token = false }
        override suspend fun me(): Me { askedFor += tenantId; return answer() }
    }

    private val settings = FakeSettings()
    private fun cache() = OfflineCache(File(folder.root, "offline"))
    private fun model(api: FakeApi) = AppModel(settings, cache(), "http://server", connect = { api })

    @Test fun `no token is the sign-in screen, and nothing is asked`() = runTest {
        val api = FakeApi(token = false) { kit }
        val model = model(api)
        model.start(allowServerOverride = false)
        assertEquals(Phase.SignedOut, model.phase.value)
        assertTrue(api.askedFor.isEmpty())
    }

    @Test fun `a stored session opens on the shop`() = runTest {
        val model = model(FakeApi { kit })
        model.start(allowServerOverride = false)
        assertEquals(Phase.SignedIn(kit), model.phase.value)
        assertEquals(kit, cache().savedMe())
    }

    @Test fun `two shops and none chosen asks which`() = runTest {
        val model = model(FakeApi { atTwoShops })
        model.start(allowServerOverride = false)
        assertEquals(Phase.ChoosingShop(atTwoShops), model.phase.value)
    }

    @Test fun `the chosen shop is remembered and sent`() = runTest {
        val api = FakeApi { atTwoShops }
        val model = model(api)
        model.start(allowServerOverride = false)
        model.choose("shop-2")
        assertEquals("shop-2", settings.shop)
        assertEquals("shop-2", api.askedFor.last())
        assertEquals(Phase.SignedIn(atTwoShops), model.phase.value)
    }

    @Test fun `signing in forgets the shop the last person chose`() = runTest {
        settings.shop = "somebody-elses"
        val api = FakeApi(token = false) { kit }
        val model = model(api)
        model.signIn("  kit@fold.example ", "secret")
        assertNull(api.askedFor.last())
        assertEquals(Phase.SignedIn(kit), model.phase.value)
    }

    @Test fun `a shop that no longer has them falls back to their first`() = runTest {
        settings.shop = "left-last-week"
        var calls = 0
        val model = model(FakeApi { if (calls++ == 0) throw ApiError.NotMember("Not at this shop.") else kit })
        model.start(allowServerOverride = false)
        assertNull(settings.shop)
        assertEquals(Phase.SignedIn(kit), model.phase.value)
    }

    @Test fun `a session the server has ended is the sign-in screen`() = runTest {
        val model = model(FakeApi { throw ApiError.Unauthorized("Sign in again.") })
        model.start(allowServerOverride = false)
        assertEquals(Phase.SignedOut, model.phase.value)
    }

    @Test fun `no signal on a phone that has been here before opens on what it last saw`() = runTest {
        cache().save(kit)
        val model = model(FakeApi { throw ApiError.Offline() })
        model.start(allowServerOverride = false)
        assertEquals(Phase.SignedIn(kit), model.phase.value)
        assertEquals(ApiError.OFFLINE_MESSAGE, model.trouble.value)
    }

    @Test fun `no signal on a phone with nothing kept says so, and keeps the token`() = runTest {
        val api = FakeApi { throw ApiError.Offline() }
        val model = model(api)
        model.start(allowServerOverride = false)
        assertEquals(Phase.Unreachable(ApiError.OFFLINE_MESSAGE), model.phase.value)
        assertTrue(api.hasToken)
    }

    @Test fun `losing signal mid-session keeps the screen and says so in a bar`() = runTest {
        var online = true
        val model = model(FakeApi { if (online) kit else throw ApiError.Unavailable("Busy.", null) })
        model.start(allowServerOverride = false)
        online = false
        model.refresh()
        assertEquals(Phase.SignedIn(kit), model.phase.value)
        assertEquals("Busy.", model.trouble.value)
        online = true
        model.tryAgain()
        assertNull(model.trouble.value)
    }

    @Test fun `a build too old to be served is told to update`() = runTest {
        val model = model(FakeApi { throw ApiError.UpgradeRequired("Update Wunderhand to carry on.") })
        model.start(allowServerOverride = false)
        assertEquals(Phase.UpgradeRequired("Update Wunderhand to carry on."), model.phase.value)
    }

    @Test fun `signing out leaves nothing of the shop on the phone`() = runTest {
        settings.shop = kit.shop.id
        val api = FakeApi { kit }
        val model = model(api)
        model.start(allowServerOverride = false)
        model.signOut()
        assertEquals(Phase.SignedOut, model.phase.value)
        assertNull(cache().savedMe())
        assertNull(settings.shop)
        assertTrue(!api.hasToken)
    }
}
