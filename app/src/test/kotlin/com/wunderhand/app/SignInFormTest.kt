package com.wunderhand.app

import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.Settings
import com.wunderhand.app.features.auth.SignInForm
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.Me
import com.wunderhand.core.OfflineCache
import com.wunderhand.network.ApiError
import com.wunderhand.network.WunderhandApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SignInFormTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val kit = ChairtimeJson.decodeFromString(Me.serializer(), checkNotNull(javaClass.getResourceAsStream("/me.json")).bufferedReader().use { it.readText() })

    private class Api(val accepts: Boolean, val me: Me) : WunderhandApi {
        var sent: Pair<String, String>? = null
        override var hasToken = false
        override var tenantId: String? = null
        override suspend fun signIn(email: String, password: String) {
            sent = email to password
            if (!accepts) throw ApiError.SignInRefused("That email and password do not match. Try again.")
            hasToken = true
        }
        override suspend fun signOut() { hasToken = false }
        override suspend fun me() = me
        override suspend fun diary(date: String?) = error("not asked for")
        override suspend fun appointment(id: String) = error("not asked for")
    }

    private object NoSettings : Settings {
        override suspend fun chosenShopId(): String? = null
        override suspend fun setChosenShopId(id: String?) {}
        override suspend fun serverOverride(): String? = null
        override suspend fun setServerOverride(url: String?) {}
    }

    private fun model(api: Api) = AppModel(NoSettings, OfflineCache(File(folder.root, "offline")), "http://server") { api }

    @Test fun `nothing typed is said at once, and nothing is sent`() {
        val api = Api(accepts = true, kit)
        val form = SignInForm()
        form.signIn(model(api))
        assertEquals("Both an email and a password, please.", form.error)
        assertNull(api.sent)
    }

    /** The form belongs to the activity, so it is still there after sign-out.
     *  Found on the emulator: the last person's email and password, waiting
     *  on the sign-in screen for the next one. */
    @Test fun `once it has worked, the form forgets what was typed`() {
        val api = Api(accepts = true, kit)
        val form = SignInForm().apply { email = "kit@fold.example"; password = "a-secret" }
        form.signIn(model(api))
        assertEquals("kit@fold.example" to "a-secret", api.sent)
        assertEquals("", form.email)
        assertEquals("", form.password)
        assertNull(form.error)
    }

    @Test fun `a refusal keeps the email, drops the password, and says what the web says`() {
        val form = SignInForm().apply { email = "kit@fold.example"; password = "wrong" }
        form.signIn(model(Api(accepts = false, kit)))
        assertEquals("kit@fold.example", form.email)
        assertEquals("", form.password)
        assertEquals("That email and password do not match. Try again.", form.error)
    }
}
