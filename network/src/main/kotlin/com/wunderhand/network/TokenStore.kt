package com.wunderhand.network

/**
 * Where the session token lives. It is the whole credential, so on a phone it
 * is kept encrypted under a key the Android Keystore holds (the app module's
 * `KeystoreTokenStore`); tests keep it in memory.
 */
interface TokenStore {
    fun read(): String?
    fun write(token: String?)
}

class InMemoryTokenStore(private var token: String? = null) : TokenStore {
    @Synchronized override fun read(): String? = token
    @Synchronized override fun write(token: String?) { this.token = token }
}

/**
 * The part of the client the session hangs off: who is signed in, and for
 * which shop. Its own interface so the app's session logic can be tested
 * against something that answers on cue.
 */
interface SessionApi {
    val hasToken: Boolean
    var tenantId: String?
    suspend fun signIn(email: String, password: String)
    suspend fun signOut()
    suspend fun me(): com.wunderhand.core.Me
}
