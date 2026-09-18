package com.wunderhand.app.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            WunderhandTheme { RootScreen(container.model, container.reachability, container.notesGate) }
        }
    }
}
