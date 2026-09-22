package com.wunderhand.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The walk the profile is made from: a cold start, sign-in, the diary
 * scrolled, and the client list scrolled. Compose test tags are exposed as
 * resource ids (RootScreen sets `testTagsAsResourceId`), so the fields are
 * found by the same names the emulator test uses.
 *
 * The demo shop on production, unless the instrumentation is given
 * `email` and `password`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val args = InstrumentationRegistry.getArguments()
        val email = args.getString("email") ?: "kit@fold.example"
        val password = args.getString("password") ?: "chairtime-demo-1"

        rule.collect(packageName = "com.wunderhand.app", includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()

            // Signed out on a fresh install; already in on the runs after the first.
            device.wait(Until.findObject(By.res("email")), 15_000)?.let { field ->
                field.text = email
                device.findObject(By.res("password")).text = password
                device.findObject(By.res("signIn")).click()
            }

            // The day, then the week, each scrolled.
            waitFor(device, "New booking")
            device.wait(Until.findObject(By.scrollable(true)), 10_000)
            device.fling(Direction.DOWN)
            device.fling(Direction.UP)
            device.tap(By.text("Week"))
            device.waitForIdle()
            device.fling(Direction.DOWN)
            device.tap(By.text("Day"))
            device.waitForIdle()

            // An appointment opened and closed, when the day has one.
            if (device.tap(By.res(java.util.regex.Pattern.compile("appointment-.*")))) {
                device.wait(Until.findObject(By.res("appointmentName")), 10_000)
                device.pressBack()
                device.waitForIdle()
            }

            // The client list, scrolled.
            device.tap(By.text("Clients"))
            device.wait(Until.findObject(By.res("clientsHeading")), 10_000)
            device.fling(Direction.DOWN)
            device.fling(Direction.UP)
        }
    }

    private fun waitFor(device: UiDevice, text: String) {
        device.wait(Until.findObject(By.text(text)), 20_000)
    }

    /**
     * Compose replaces its nodes as it recomposes, so a handle found a moment
     * ago can be stale by the time it is flung — the first attempt at this walk
     * died that way while the diary was still loading. Found afresh each time,
     * and once more if it has gone stale; nothing to scroll is not a failure.
     */
    /** True when something matched and was tapped. */
    private fun UiDevice.tap(selector: BySelector): Boolean {
        repeat(3) {
            val target: UiObject2 = findObject(selector) ?: return false
            try {
                target.click()
                waitForIdle()
                return true
            } catch (_: StaleObjectException) {
                waitForIdle()
            }
        }
        return false
    }

    private fun UiDevice.fling(direction: Direction) {
        repeat(3) {
            val target: UiObject2 = findObject(By.scrollable(true)) ?: return
            try {
                target.fling(direction)
                waitForIdle()
                return
            } catch (_: StaleObjectException) {
                waitForIdle()
            }
        }
    }
}
