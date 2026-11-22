package com.wunderhand.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wunderhand.app.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signing in to a login that asks for a code as well, against chairtime's dev
 * server on this Mac (`next dev -p 3100`, reached from the emulator as
 * 10.0.2.2) — the app and the API together.
 *
 * The login with two-step sign-in on, and the secret its authenticator app
 * would hold, are passed in rather than written down here: this repo is
 * public, and a secret in it would be a secret no longer. Turn two-step on for
 * a seeded login on the web, then run with those three in the shell's own
 * environment:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.wunderhand.app.TwoStepTest \
 *   -Pandroid.testInstrumentationRunnerArguments.twoStepEmail="$WH_TWO_STEP_EMAIL" \
 *   -Pandroid.testInstrumentationRunnerArguments.twoStepSecret="$WH_TWO_STEP_SECRET" \
 *   -Pandroid.testInstrumentationRunnerArguments.twoStepBackupCode="$WH_TWO_STEP_BACKUP_CODE"
 * ```
 *
 * Skipped, not failed, without them.
 *
 * One test, not several, for the reason [DiaryTest] has one: each would sign in
 * again, and both chairtime and Better Auth limit how often anybody may. The
 * two verify endpoints take three requests in ten seconds, so this waits
 * between codes rather than racing the limit it is not here to test.
 */
@RunWith(AndroidJUnit4::class)
class TwoStepTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val password = args.getString("password") ?: "chairtime-demo-1"
    private val twoStepEmail = args.getString("twoStepEmail")
    private val secret = args.getString("twoStepSecret")
    private val backupCode = args.getString("twoStepBackupCode")

    private val wrongCode =
        "That code isn’t right. Codes change every 30 seconds, so check it’s the newest one and try again."

    private fun isOnScreen(tag: String) = app.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, within: Long = 25_000) = app.waitUntil(within) { isOnScreen(tag) }

    /** Better Auth takes three of these in ten seconds. Nothing here is about
     *  that limit, so it is waited out rather than run into. */
    private fun pauseForTheRateLimit() = Thread.sleep(11_000)

    private fun signInWith(email: String) {
        app.onNodeWithTag("email").performTextReplacement(email)
        app.onNodeWithTag("password").performTextReplacement(password)
        app.onNodeWithTag("signIn").performClick()
    }

    private fun typeCode(code: String) {
        app.onNodeWithTag("code").performTextInput(code)
        app.onNodeWithTag("sendCode").performClick()
    }

    private fun signOut() {
        app.onNodeWithTag("tab-Shop").performClick()
        waitFor("signOut")
        app.onNodeWithTag("signOut").performScrollTo().performClick()
        // Asked about first: it is one slip of a thumb from the row above it.
        app.onNodeWithTag("confirm").performClick()
        waitFor("signIn")
    }

    @Test fun aCodeAfterThePassword() {
        assumeTrue(
            "Pass twoStepEmail, twoStepSecret and twoStepBackupCode to run this",
            twoStepEmail != null && secret != null && backupCode != null,
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                app.activity.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        app.waitUntil(25_000) { isOnScreen("signIn") || isOnScreen("diaryHeading") }
        if (isOnScreen("diaryHeading")) signOut()

        // The regression that matters most: a login with two-step off signs in
        // exactly as it always has, with no code screen in the way.
        signInWith("kit@fold.example")
        waitFor("diaryHeading")
        assertFalse("A login without two-step was asked for a code", isOnScreen("code"))
        signOut()

        // The password is right, so the code screen opens, worded as the web's
        // /sign-in/code words it.
        signInWith(twoStepEmail!!)
        waitFor("code", within = 20_000)
        app.onNodeWithText("Enter your code").assertExists()
        app.onNodeWithText("Open your authenticator app and type the 6-digit code it shows for Wunderhand.").assertExists()

        // A wrong code says so, and leaves them here for the next one.
        typeCode("000000")
        waitFor("codeError", within = 20_000)
        app.onNodeWithTag("codeError").assertTextContains(wrongCode)
        app.onNodeWithTag("code").assertExists()
        assertFalse(isOnScreen("diaryHeading"))

        // A backup code instead: its own words, and it signs in.
        app.onNodeWithTag("otherKindOfCode").performClick()
        app.onNodeWithText("Use a backup code").assertExists()
        app.onNodeWithText("One of the ten codes you saved when you turned two-step sign-in on. Each works once.").assertExists()
        pauseForTheRateLimit()
        typeCode(backupCode!!)
        waitFor("diaryHeading")
        signOut()

        // Start again, from the code screen back to the password.
        signInWith(twoStepEmail)
        waitFor("code", within = 20_000)
        app.onNodeWithTag("startAgain").performClick()
        waitFor("signIn", within = 10_000)

        // And the code from the authenticator app signs in.
        signInWith(twoStepEmail)
        waitFor("code", within = 20_000)
        pauseForTheRateLimit()
        typeCode(freshCode(secret!!))
        waitFor("diaryHeading")
        signOut()
    }

    /** The six digits an authenticator app would be showing: RFC 6238 over
     *  HMAC-SHA1, thirty-second steps, as the plugin's own codes are made. */
    private fun freshCode(base32Secret: String): String {
        val key = base32(base32Secret)
        val counter = ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 1000 / 30).array()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(counter)
        val offset = mac[mac.size - 1].toInt() and 0x0f
        val binary = ByteBuffer.wrap(mac, offset, 4).int and 0x7fffffff
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = StringBuilder()
        for (c in value.trimEnd('=').uppercase()) {
            val i = alphabet.indexOf(c)
            if (i >= 0) bits.append(i.toString(2).padStart(5, '0'))
        }
        return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }
}
