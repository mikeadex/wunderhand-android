package com.wunderhand.app.app

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wunderhand.app.push.PushNotifier
import com.wunderhand.app.push.PushServices
import com.wunderhand.design.WunderhandTheme

/**
 * The one activity. Every screen is Compose, drawn edge to edge. A
 * FragmentActivity only because the system's biometric prompt asks for one.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light only in v1, so the bars' icons are dark whatever the phone is set to.
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.light(clear, clear), SystemBarStyle.light(clear, clear))
        super.onCreate(savedInstanceState)
        val container = (application as WunderhandApp).container
        container.notesGate.attach(this)
        // Started by a tapped notification or a link — but not again when the phone is turned, or the app comes back from the dead.
        if (savedInstanceState == null) { container.notifier.dismissFor(intent); container.push.follow(PushNotifier.linkIn(intent)) }
        setContent {
            WunderhandTheme { RootScreen(container.model, container.reachability, container.notesGate, PushServices(container.push, container.notifier)) }
        }
    }

    /** Tapped with the app already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val container = (application as WunderhandApp).container
        container.notifier.dismissFor(intent)
        container.push.follow(PushNotifier.linkIn(intent))
    }
}
