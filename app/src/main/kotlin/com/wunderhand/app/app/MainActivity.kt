package com.wunderhand.app.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wunderhand.design.WunderhandTheme

/** The one activity. Every screen is Compose, drawn edge to edge. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light only in v1, so the bars' icons are dark whatever the phone is set to.
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.light(clear, clear), SystemBarStyle.light(clear, clear))
        super.onCreate(savedInstanceState)
        val container = (application as WunderhandApp).container
        setContent {
            WunderhandTheme { RootScreen(container.model, container.reachability) }
        }
    }
}
