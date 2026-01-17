package com.nostrtv.android.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.auth.AuthState
import com.nostrtv.android.data.auth.BunkerAuthManager
import com.nostrtv.android.data.auth.SessionStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(
    context: Context
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val sessionStore = SessionStore(context)
    private val bunkerAuthManager = BunkerAuthManager(sessionStore)

    val authState: StateFlow<AuthState> = bunkerAuthManager.authState
    val connectionString: StateFlow<String?> = bunkerAuthManager.connectionString
    val userPubkey: StateFlow<String?> = bunkerAuthManager.userPubkey

    /**
     * Start the NIP-46 login flow.
     * Generates a connection URI for QR code display.
     */
    fun startLogin() {
        Log.d(TAG, "Starting bunker login flow")
        bunkerAuthManager.startLogin()
    }

    /**
     * Cancel the pending login attempt.
     */
    fun cancelLogin() {
        Log.d(TAG, "Canceling login")
        bunkerAuthManager.cancelLogin()
    }

    /**
     * Logout and clear session.
     */
    fun logout() {
        Log.d(TAG, "Logging out")
        bunkerAuthManager.logout()
    }

    /**
     * Check if user is authenticated.
     */
    fun isAuthenticated(): Boolean {
        return authState.value is AuthState.Authenticated
    }

    /**
     * Get the authenticated user's public key.
     */
    fun getUserPubkey(): String? {
        return (authState.value as? AuthState.Authenticated)?.pubkey
    }

    /**
     * Get the BunkerAuthManager for signing events.
     */
    fun getAuthManager(): BunkerAuthManager = bunkerAuthManager

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(context.applicationContext) as T
        }
    }
}
