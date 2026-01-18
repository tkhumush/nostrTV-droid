package com.nostrtv.android.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Secure storage for user session and NIP-46 authentication data.
 * Uses EncryptedSharedPreferences for sensitive data like private keys.
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
        private const val KEY_IS_AUTHENTICATED = "is_authenticated"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _isAuthenticated = MutableStateFlow(prefs.getBoolean(KEY_IS_AUTHENTICATED, false))
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _userPubkey = MutableStateFlow(prefs.getString(KEY_USER_PUBKEY, null))
    val userPubkey: StateFlow<String?> = _userPubkey.asStateFlow()

    /**
     * Save a complete NIP-46 session.
     */
    fun saveSession(
        pubkey: String,
        bunkerPubkey: String,
        clientPrivateKey: String,
        relay: String,
        secret: String
    ) {
        Log.d(TAG, "Saving session for pubkey: ${pubkey.take(8)}...")
        prefs.edit()
            .putString(KEY_USER_PUBKEY, pubkey)
            .putString(KEY_BUNKER_PUBKEY, bunkerPubkey)
            .putString(KEY_CLIENT_PRIVATE_KEY, clientPrivateKey)
            .putString(KEY_RELAY, relay)
            .putString(KEY_SECRET, secret)
            .putBoolean(KEY_IS_AUTHENTICATED, true)
            .apply()

        _userPubkey.value = pubkey
        _isAuthenticated.value = true
    }

    /**
     * Get the saved session data.
     */
    fun getSavedSession(): SavedSession? {
        if (!prefs.getBoolean(KEY_IS_AUTHENTICATED, false)) {
            return null
        }

        val userPubkey = prefs.getString(KEY_USER_PUBKEY, null) ?: return null
        val bunkerPubkey = prefs.getString(KEY_BUNKER_PUBKEY, null) ?: return null
        val clientPrivateKey = prefs.getString(KEY_CLIENT_PRIVATE_KEY, null) ?: return null
        val relay = prefs.getString(KEY_RELAY, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null

        return SavedSession(
            userPubkey = userPubkey,
            bunkerPubkey = bunkerPubkey,
            clientPrivateKey = clientPrivateKey,
            relay = relay,
            secret = secret
        )
    }

    /**
     * Clear all session data.
     */
    fun clearSession() {
        Log.d(TAG, "Clearing session")
        prefs.edit().clear().apply()
        _userPubkey.value = null
        _isAuthenticated.value = false
    }

    /**
     * Check if there's a valid saved session.
     */
    fun hasValidSession(): Boolean {
        return prefs.getBoolean(KEY_IS_AUTHENTICATED, false) &&
                !prefs.getString(KEY_USER_PUBKEY, null).isNullOrEmpty() &&
                !prefs.getString(KEY_CLIENT_PRIVATE_KEY, null).isNullOrEmpty()
    }
}

/**
 * Data class representing a saved NIP-46 session.
 */
data class SavedSession(
    val userPubkey: String,
    val bunkerPubkey: String,
    val clientPrivateKey: String,
    val relay: String,
    val secret: String
)
