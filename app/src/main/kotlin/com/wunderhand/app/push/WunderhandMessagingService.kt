package com.wunderhand.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wunderhand.app.app.WunderhandApp
import com.wunderhand.core.PushPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What Firebase hands the app: a message, or a new token.
 *
 * Android starts this with the app closed and the phone idle, so it leans on
 * nothing but the container.
 */
class WunderhandMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container get() = (application as WunderhandApp).container

    override fun onMessageReceived(message: RemoteMessage) = container.received(message.data)

    override fun onNewToken(token: String) {
        scope.launch { container.push.tokenChanged(token) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * A message arrived. Dropped unless somebody is signed in: a shop's news is
 * not shown on a phone whoever it was for has left, even if the phone could
 * not reach chairtime to say so on the way out.
 */
fun com.wunderhand.app.app.AppContainer.received(data: Map<String, String>) {
    if (!model.client.hasToken) return
    val push = PushPayload.from(data) ?: return
    notifier.show(push)
    // And whatever is on screen may be out of date.
    this.push.arrived()
}
