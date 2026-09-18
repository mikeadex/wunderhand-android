package com.wunderhand.app.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wunderhand.network.TokenStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The session token, encrypted under a key that never leaves the Android
 * Keystore.
 *
 * The token is the whole credential, so it is not written anywhere as itself.
 * An AES key is made inside the Keystore (hardware-backed where the phone has
 * it), the token is sealed with it, and only the sealed bytes go to disk — in
 * the app's no-backup folder, so they never travel to another phone, where the
 * key to open them would not exist anyway.
 *
 * This is what `EncryptedSharedPreferences` did before it was deprecated,
 * written out: one key, one file.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val file = File(context.noBackupFilesDir, "session")
    private val lock = Any()
    /** Read once, then kept: the token is wanted on every request. */
    private var cached: String? = null
    private var loaded = false

    override fun read(): String? = synchronized(lock) {
        if (!loaded) {
            cached = open()
            loaded = true
        }
        cached
    }

    override fun write(token: String?) = synchronized(lock) {
        cached = token
        loaded = true
        if (token == null) {
            file.delete()
        } else {
            runCatching { seal(token) }.onFailure { file.delete() }
        }
        Unit
    }

    private fun seal(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        // The nonce is not a secret; it goes in front of what it opened.
        file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed)
    }

    /** Anything that cannot be opened — a file cut short, a key the system has
     *  thrown away after a restore — is a signed-out phone, not a crash. */
    private fun open(): String? = runCatching {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
    }.getOrElse {
        file.delete()
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wunderhand.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
