package com.wunderhand.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wunderhand.app.app.WunderhandApp

/** A pretended push, in the debug build only. See this source set's manifest for how, and who can send one. */
class DebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val data = extras.keySet().mapNotNull { key -> extras.getString(key)?.let { key to it } }.toMap()
        (context.applicationContext as WunderhandApp).container.received(data)
    }
}
