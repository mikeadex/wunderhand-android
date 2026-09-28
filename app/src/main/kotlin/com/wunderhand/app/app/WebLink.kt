package com.wunderhand.app.app

import androidx.compose.ui.platform.UriHandler
import com.wunderhand.network.WunderhandApi

/**
 * Opens a page of the site as the person signed in to the app.
 *
 * Asks chairtime for a one-time link first ([WunderhandApi.webSession]), so the
 * browser is signed in as this person rather than as whoever it last held. If
 * that cannot be had — offline, or an older server — the plain address opens
 * and the web asks for its own sign-in, as it always did.
 */
object WebLink {
    suspend fun open(path: String, api: WunderhandApi, server: String, uri: UriHandler) {
        val clean = path.trimStart('/')
        val plain = "${server.trimEnd('/')}/$clean"
        val target = runCatching { api.webSession("/$clean").url }.getOrDefault(plain)
        runCatching { uri.openUri(target) }
    }
}
