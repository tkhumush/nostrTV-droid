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
 * Secure storage for user session and authentication data.
 * Uses EncryptedSharedPreferences for sensitive data.
 */
class SessionStore(context: Context) {
    companion object {
        private const val TAG = "SessionStore"
        private const val PREFS_NAME = "nostr_tv_session"
        private const val KEY_PUBKEY = "user_pubkey"
        private const val KEY_BUNKER_URL = "bunker_url"
        private const val KEY_BUNKER_SECRET = "bunker_secret"
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

    private val _userPubkey = MutableStateFlow(prefs.getString(KEY_PUBKEY, null))
    val userPubkey: StateFlow<String?> = _userPubkey.asStateFlow()

    fun saveSession(pubkey: String, bunkerUrl: String, secret: String) {
        Log.d(TAG, "Saving session for pubkey: ${pubkey.take(8)}...")
        prefs.edit()
            .putString(KEY_PUBKEY, pubkey)
            .putString(KEY_BUNKER_URL, bunkerUrl)
            .putString(KEY_BUNKER_SECRET, secret)
            .putBoolean(KEY_IS_AUTHENTICATED, true)
            .apply()

        _userPubkey.value = pubkey
        _isAuthenticated.value = true
    }

    fun getBunkerUrl(): String? = prefs.getString(KEY_BUNKER_URL, null)

    fun getBunkerSecret(): String? = prefs.getString(KEY_BUNKER_SECRET, null)

    fun clearSession() {
        Log.d(TAG, "Clearing session")
        prefs.edit().clear().apply()
        _userPubkey.value = null
        _isAuthenticated.value = false
    }

    fun hasValidSession(): Boolean {
        return prefs.getBoolean(KEY_IS_AUTHENTICATED, false) &&
                !prefs.getString(KEY_PUBKEY, null).isNullOrEmpty()
    }
}
