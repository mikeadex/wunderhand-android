package com.wunderhand.app

import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.Phase
import com.wunderhand.app.app.Settings
import com.wunderhand.app.features.auth.CodeForm
import com.wunderhand.app.features.auth.SignInForm
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiError
import com.wunderhand.network.Pending
import com.wunderhand.network.SignIn
import com.wunderhand.network.TwoFactorCode
import com.wunderhand.network.TwoFactorRefusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The second step of signing in: the session's side of it, and the screen's.
 *
 * Every sentence here is the web's own, from `app/(auth)/sign-in/code/page.tsx`
 * and `app/(auth)/sign-in/page.tsx`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoStepTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val kit: Me = ChairtimeJson.decodeFromString(
        Me.serializer(), checkNotNull(javaClass.getResourceAsStream("/me.json")).bufferedReader().use { it.readText() },
    )

    private val wrongCode = "That code isn’t right. Codes change every 30 seconds, so check it’s the newest one and try again."
    private val ranOut = "That sign-in ran out of time. Start again with your email and password."

    private val half = Pending.forPreview()

    /** A chairtime where the password step asks for a code, and the code step
     *  answers however the test says. */
    private class Api(
        val onPassword: () -> SignIn = { SignIn.NeedsCode(Pending.forPreview()) },
        val onCode: (String, TwoFactorCode) -> Unit = { _, _ -> },
        val me: Me? = null,
    ) : StubApi() {
        val codesSent = mutableListOf<Pair<String, TwoFactorCode>>()
        var askedWhoWeAre = false
        init { hasToken = false }
        override suspend fun signIn(email: String, password: String): SignIn = onPassword()
        override suspend fun signIn(code: String, from: Pending, kind: TwoFactorCode) {
            codesSent += code to kind
            onCode(code, kind)
            hasToken = true
        }
        override suspend fun me(): Me { askedWhoWeAre = true; return checkNotNull(me) }
    }

    private class FakeSettings(var shop: String? = null) : Settings {
        override suspend fun chosenShopId() = shop
        override suspend fun setChosenShopId(id: String?) { shop = id }
        override suspend fun serverOverride(): String? = null
        override suspend fun setServerOverride(url: String?) {}
    }

    private fun model(api: Api) =
        AppModel(FakeSettings(), OfflineCache(File(folder.root, "offline")), "http://server", connect = { api })

    // The session

    @Test fun `a password step that asks for a code opens the code screen, and signs nobody in`() = runTest {
        val api = Api()
        val model = model(api)
        model.signIn("nadia@okonjo.example", "secret")
        assertTrue(model.phase.value is Phase.NeedsCode)
        assertFalse(api.askedWhoWeAre)
    }

    @Test fun `a right code is a session, and the shop opens`() = runTest {
        val api = Api(me = kit)
        val model = model(api)
        model.signIn("nadia@okonjo.example", "secret")
        model.signIn("123456", half, TwoFactorCode.Authenticator)
        assertEquals(Phase.SignedIn(kit), model.phase.value)
        assertEquals(listOf("123456" to TwoFactorCode.Authenticator), api.codesSent)
    }

    /** A wrong code is worth another go on the same screen, so the screen is
     *  left where it is and told. */
    @Test fun `a wrong code is the screen's to say, and the code screen stays`() = runTest {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.WrongCode, wrongCode) }, me = kit)
        val model = model(api)
        model.signIn("nadia@okonjo.example", "secret")
        val refusal = runCatching { model.signIn("000000", half, TwoFactorCode.Authenticator) }.exceptionOrNull()
        assertEquals(wrongCode, refusal?.message)
        assertTrue(model.phase.value is Phase.NeedsCode)
    }

    /** Nothing more can be done with a sign-in that is spent, so this one
     *  refusal is the session's to handle rather than the screen's: it would
     *  otherwise leave somebody typing codes into something that will not take
     *  another. */
    @Test fun `a sign-in that ran out of time goes back to the password, with the reason`() = runTest {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.StartAgain, ranOut) }, me = kit)
        val model = model(api)
        model.signIn("nadia@okonjo.example", "secret")
        model.signIn("123456", half, TwoFactorCode.Authenticator)
        assertEquals(Phase.SignedOut, model.phase.value)
        assertEquals(ranOut, model.signInNote.value)
    }

    @Test fun `Start again goes back to the password with nothing to say`() = runTest {
        val model = model(Api())
        model.signIn("nadia@okonjo.example", "secret")
        model.startSignInAgain()
        assertEquals(Phase.SignedOut, model.phase.value)
        assertNull(model.signInNote.value)
    }

    @Test fun `typing a password again clears the note the last attempt left`() = runTest {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.StartAgain, ranOut) }, me = kit)
        val model = model(api)
        model.signIn("nadia@okonjo.example", "secret")
        model.signIn("123456", half, TwoFactorCode.Authenticator)
        assertEquals(ranOut, model.signInNote.value)
        model.signIn("nadia@okonjo.example", "secret")
        assertNull(model.signInNote.value)
    }

    /** The reason a two-step sign-in was sent back here is the sign-in
     *  screen's to show, alongside its own refusals. */
    @Test fun `the sign-in screen says the note the code step left`() = runTest {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.StartAgain, ranOut) }, me = kit)
        val model = model(api)
        model.signIn("nadia@okonjo.example", "secret")
        model.signIn("123456", half, TwoFactorCode.Authenticator)
        assertEquals(ranOut, SignInForm().note(model.signInNote.value))
    }

    // The code screen

    @Test fun `nothing typed is said at once, and nothing is sent`() {
        val api = Api()
        val form = CodeForm()
        form.signIn(model(api), half)
        assertEquals("Type the code first.", form.error)
        assertTrue(api.codesSent.isEmpty())
    }

    @Test fun `spaces alone are nothing typed`() {
        val api = Api()
        val form = CodeForm().apply { type("   ") }
        form.signIn(model(api), half)
        assertEquals("Type the code first.", form.error)
        assertTrue(api.codesSent.isEmpty())
    }

    /** The next code the authenticator shows is a different six digits, so the
     *  field is cleared for it. */
    @Test fun `a wrong code clears the field and says what the web says`() {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.WrongCode, wrongCode) }, me = kit)
        val form = CodeForm().apply { type("000000") }
        form.signIn(model(api), half)
        assertEquals(wrongCode, form.error)
        assertEquals("", form.code)
    }

    /** A pause is not worth another go, so what was typed is left where it is. */
    @Test fun `a pause leaves the code alone`() {
        val paused = "Too many wrong codes. Sign-in with a code is paused for 15 minutes on this login."
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.Locked, paused) }, me = kit)
        val form = CodeForm().apply { type("123456") }
        form.signIn(model(api), half)
        assertEquals(paused, form.error)
        assertEquals("123456", form.code)
    }

    @Test fun `a backup code goes as a backup code`() {
        val api = Api(me = kit)
        val form = CodeForm().apply { useOtherKind(); type("abcd-efgh") }
        assertTrue(form.isBackup)
        form.signIn(model(api), half)
        assertEquals(listOf("abcd-efgh" to TwoFactorCode.Backup), api.codesSent)
    }

    @Test fun `changing kind clears what was typed and the refusal`() {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.WrongCode, wrongCode) }, me = kit)
        val form = CodeForm().apply { type("123456") }
        form.signIn(model(api), half)
        assertEquals(wrongCode, form.error)
        form.useOtherKind()
        assertTrue(form.isBackup)
        assertEquals("", form.code)
        assertNull(form.error)
        form.useOtherKind()
        assertFalse(form.isBackup)
    }

    /** Six digits, as `tidyCode` would leave them: a code read off a screen as
     *  "123 456" or filled in from the keyboard arrives the way the server wants. */
    @Test fun `an authenticator code is six digits and nothing else`() {
        val form = CodeForm()
        form.type("12 34-56")
        assertEquals("123456", form.code)
        form.type("1234567890")
        assertEquals("123456", form.code)
        form.type("12a3b4")
        assertEquals("1234", form.code)
    }

    /** A backup code's hyphens belong to it, and the web only trims one. */
    @Test fun `a backup code keeps its hyphens`() {
        val form = CodeForm().apply { useOtherKind() }
        form.type("abcd-efgh")
        assertEquals("abcd-efgh", form.code)
    }

    /** The form belongs to the activity, so it is still there for the next
     *  person to sign in. Found on the emulator: a backup-code screen, and the
     *  last refusal, waiting on a sign-in that had nothing to do with them. */
    @Test fun `a sign-in the form has not seen starts the screen fresh`() {
        val api = Api(onCode = { _, _ -> throw ApiError.TwoFactor(TwoFactorRefusal.WrongCode, wrongCode) }, me = kit)
        val form = CodeForm().apply { opened(half); useOtherKind(); type("abcd-efgh") }
        form.signIn(model(api), half)
        assertEquals(wrongCode, form.error)

        // The same sign-in, after a rotation: left exactly as it was.
        form.opened(half)
        assertTrue(form.isBackup)
        assertEquals(wrongCode, form.error)

        // Somebody signing in again: the authenticator, and nothing to say.
        form.opened(Pending.forPreview("better-auth.two_factor=another"))
        assertFalse(form.isBackup)
        assertEquals("", form.code)
        assertNull(form.error)
    }

    @Test fun `a code screen that has worked forgets what was typed`() {
        val api = Api(me = kit)
        val form = CodeForm().apply { type("123456") }
        form.signIn(model(api), half)
        assertEquals("", form.code)
        assertNull(form.error)
    }
}
