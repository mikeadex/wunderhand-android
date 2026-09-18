package com.wunderhand.app

import com.wunderhand.app.features.shop.AccountViewModel
import com.wunderhand.app.features.shop.CloseShopViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.LoginDeleted
import com.wunderhand.core.Me
import com.wunderhand.core.ShopCloseResult
import com.wunderhand.core.ShopClosing
import com.wunderhand.core.ShopClosure
import com.wunderhand.network.ApiError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private val me = ChairtimeJson.decodeFromString(Me.serializer(), checkNotNull(object {}.javaClass.getResourceAsStream("/me.json")).bufferedReader().use { it.readText() })
private val open = ShopClosing(slug = "fold-barbers", status = "active", graceDays = 30, requestDays = 14, upcoming = 3)
private fun request(mine: ShopClosure.Standing, agreed: Boolean = false) = ShopClosure(
    "r1", ShopClosure.Who("s1", "Kit Alvarez"), Instant.parse("2026-09-18T09:00:00Z"), Instant.parse("2026-10-02T09:00:00Z"),
    listOf(ShopClosure.Partner("s2", "Ade Balogun", agreed)), mine,
)

private open class Way : StubApi() {
    var closing = open
    override suspend fun shopClosing(): ShopClosing { calls += "closing"; return closing }
    override suspend fun deleteLogin(password: String): LoginDeleted { calls += "delete ${password.length}"; return LoginDeleted(true, 1) }
    override suspend fun closeShop(password: String, confirm: String): ShopCloseResult { calls += "close $confirm"; return ShopCloseResult(closed = true, deleteAfter = Instant.parse("2026-10-18T09:00:00Z"), upcoming = 3) }
    override suspend fun agreeToClose(password: String): ShopCloseResult { calls += "agree"; return ShopCloseResult(closed = true) }
    override suspend fun refuseToClose() { calls += "refuse"; closing = open }
    override suspend fun withdrawClose() { calls += "withdraw"; closing = open }
}

class AccountTest : OnMain() {
    @Test fun `nothing is sent without the password typed again`() = runTest {
        unconfined(this)
        val api = Way()
        val model = AccountViewModel(me, api, signedOut = { error("not yet") })
        assertNull(model.delete())
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `a deleted login signs the app out, and the password is let go of`() = runTest {
        unconfined(this)
        val api = Way()
        var out = false
        val model = AccountViewModel(me, api, signedOut = { out = true })
        model.type("hunter2!")
        model.delete()
        assertEquals(listOf("delete 8"), api.calls)
        assertTrue(out)
        assertEquals("", model.state.value.password)
    }

    @Test fun `a wrong password is said, the box is emptied, and nobody is signed out`() = runTest {
        unconfined(this)
        val api = object : Way() { override suspend fun deleteLogin(password: String) = throw ApiError.Validation("That password is not right. Nothing was deleted.", "password") }
        val model = AccountViewModel(me, api, signedOut = { error("still here") })
        model.type("wrong")
        model.delete()
        assertEquals("That password is not right. Nothing was deleted.", model.state.value.problem)
        assertEquals("", model.state.value.password)
        assertFalse(model.state.value.isDeleting)
    }

    @Test fun `the screen says where they sign in, and whose the shop is`() {
        val model = AccountViewModel(me, Way(), signedOut = {})
        assertTrue(model.whereTheySignIn.startsWith("You sign in as ${me.user.email}, at "))
        assertTrue(model.whatStays[1].endsWith(if (me.staff.isOwner) "It stays with its other owners." else "It belongs to its owners."))
    }
}

class CloseShopTest : OnMain() {
    @Test fun `closing needs the password and the address, and sends the address as typed`() = runTest {
        unconfined(this)
        val api = Way()
        val model = CloseShopViewModel(me, api, handle = {}).apply { enter(1) }
        model.typePassword("pw")
        assertNull(model.close())
        model.typeConfirm(" fold-barbers ")
        model.close()
        assertEquals(listOf("closing", "close fold-barbers"), api.calls)
        val state = model.state.value
        assertTrue(state.isClosed)
        assertEquals(Instant.parse("2026-10-18T09:00:00Z"), state.deleteAfter)
        assertEquals("", state.password)
        assertEquals("", state.typed)
    }

    @Test fun `with another owner, asking opens a request, and whoever asked can take it back`() = runTest {
        unconfined(this)
        val api = object : Way() { override suspend fun closeShop(password: String, confirm: String) = ShopCloseResult(closed = false, closure = request(ShopClosure.Standing.Requester)) }
        val model = CloseShopViewModel(me, api, handle = {}).apply { enter(1); typePassword("pw"); typeConfirm("fold-barbers"); close() }
        assertFalse(model.state.value.isClosed)
        assertEquals(ShopClosure.Standing.Requester, model.state.value.request?.mine)
        model.withdraw()
        assertNull(model.state.value.request)
        assertEquals("closing", api.calls.last())
    }

    @Test fun `a partner agrees with their own password, or ends it with a no`() = runTest {
        unconfined(this)
        val api = Way().apply { closing = open.copy(closure = request(ShopClosure.Standing.Partner)) }
        val model = CloseShopViewModel(me, api, handle = {}).apply { enter(1) }
        assertNull(model.agree())
        model.refuse()
        assertEquals(listOf("closing", "refuse", "closing"), api.calls)
        assertNull(model.state.value.request)

        api.closing = open.copy(closure = request(ShopClosure.Standing.Partner))
        model.enter(2)
        model.typePassword("pw")
        model.agree()
        assertTrue(model.state.value.isClosed)
    }

    @Test fun `a refusal is said in chairtime's words, the password goes, and the screen looks again`() = runTest {
        unconfined(this)
        val api = object : Way() { override suspend fun closeShop(password: String, confirm: String) = throw ApiError.Validation("That is not this shop’s address.", "confirm") }
        val model = CloseShopViewModel(me, api, handle = {}).apply { enter(1); typePassword("pw"); typeConfirm("fold"); close() }
        assertEquals("That is not this shop’s address.", model.state.value.problem)
        assertEquals("", model.state.value.password)
        assertEquals("fold", model.state.value.typed)
        assertEquals(listOf("closing", "closing"), api.calls)
        assertNull(model.state.value.busy)
    }

    @Test fun `leaving the app lets go of the password, and coming back to the screen starts clean`() = runTest {
        unconfined(this)
        val model = CloseShopViewModel(me, Way(), handle = {}).apply { enter(1); typePassword("pw"); typeConfirm("fold") }
        model.forgetPassword()
        assertEquals("", model.state.value.password)
        assertEquals("fold", model.state.value.typed)
        model.enter(2)
        assertEquals("", model.state.value.typed)
    }
}
