package com.nostrtv.android.data.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure session storage using Android Keystore-backed EncryptedSharedPreferences.
 */
class SessionStore(context: Context) {
    companion object {
        private const val TAG = "SessionStore"
        private const val PREFS_NAME = "nostr_tv_session"
        private const val KEY_USER_PUBKEY = "user_pubkey"
        private const val KEY_BUNKER_PUBKEY = "bunker_pubkey"
        private const val KEY_CLIENT_PRIVATE_KEY = "client_private_key"
        private const val KEY_RELAY = "relay"
        private const val KEY_SECRET = "secret"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Save session data securely.
     */
    fun saveSession(
        userPubkey: String,
        bunkerPubkey: String,
        clientPrivateKey: String,
        relay: String,
        secret: String
    ) {
        Log.d(TAG, "Saving session for user: ${userPubkey.take(16)}...")
        prefs.edit()
            .putString(KEY_USER_PUBKEY, userPubkey)
            .putString(KEY_BUNKER_PUBKEY, bunkerPubkey)
            .putString(KEY_CLIENT_PRIVATE_KEY, clientPrivateKey)
            .putString(KEY_RELAY, relay)
            .putString(KEY_SECRET, secret)
            .apply()
    }

    /**
     * Get saved session if exists.
     */
    fun getSavedSession(): SavedSession? {
        val userPubkey = prefs.getString(KEY_USER_PUBKEY, null) ?: return null
        val bunkerPubkey = prefs.getString(KEY_BUNKER_PUBKEY, null) ?: return null
        val clientPrivateKey = prefs.getString(KEY_CLIENT_PRIVATE_KEY, null) ?: return null
        val relay = prefs.getString(KEY_RELAY, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null

        Log.d(TAG, "Restored session for user: ${userPubkey.take(16)}...")
        return SavedSession(
            userPubkey = userPubkey,
            bunkerPubkey = bunkerPubkey,
            clientPrivateKey = clientPrivateKey,
            relay = relay,
            secret = secret
        )
    }

    /**
     * Clear saved session.
     */
    fun clearSession() {
        Log.d(TAG, "Clearing session")
        prefs.edit().clear().apply()
    }

    /**
     * Check if a session exists.
     */
    fun hasSession(): Boolean {
        return prefs.getString(KEY_USER_PUBKEY, null) != null
    }
}

/**
 * Saved session data.
 */
data class SavedSession(
    val userPubkey: String,
    val bunkerPubkey: String,
    val clientPrivateKey: String,
    val relay: String,
    val secret: String
)
