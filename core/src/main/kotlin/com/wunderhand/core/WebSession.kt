package com.wunderhand.core

import kotlinx.serialization.Serializable

/**
 * From the app to the web as the same person (chairtime `lib/auth/handoff.ts`).
 *
 * The app holds a bearer token and the browser a cookie, and a plain link opened
 * the site as whoever the browser had last signed in as. So a link out of the app
 * is asked for at the moment of tapping: chairtime mints a one-minute, single-use
 * link that signs the browser in as this person and sends them to the page.
 */
@Serializable data class WebSessionRequest(val path: String)

/** `POST /api/v1/web-session` */
@Serializable data class WebSession(val url: String, val expiresInSeconds: Int = 60)
