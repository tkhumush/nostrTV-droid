package com.nostrtv.android.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.auth.AuthState
import com.nostrtv.android.data.auth.BunkerAuthManager
import com.nostrtv.android.data.auth.SessionStore
import com.nostrtv.android.data.nostr.NostrClient
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(
    context: Context
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val sessionStore = SessionStore(context)
    private val bunkerAuthManager = BunkerAuthManager(sessionStore)
    private val nostrClient = NostrClient()

    val authState: StateFlow<AuthState> = bunkerAuthManager.authState
    val connectionString: StateFlow<String?> = bunkerAuthManager.connectionString
    val userPubkey: StateFlow<String?> = bunkerAuthManager.userPubkey

    private val _userProfile = MutableStateFlow<Profile?>(null)
    val userProfile: StateFlow<Profile?> = _userProfile.asStateFlow()

    private val _isLoadingProfile = MutableStateFlow(false)
    val isLoadingProfile: StateFlow<Boolean> = _isLoadingProfile.asStateFlow()

    init {
        // When auth state changes to authenticated, fetch profile
        viewModelScope.launch {
            authState.collect { state ->
                if (state is AuthState.Authenticated) {
                    fetchUserProfile(state.pubkey)
                } else {
                    _userProfile.value = null
                }
            }
        }
    }

    private fun fetchUserProfile(pubkey: String) {
        Log.d(TAG, "Fetching profile for pubkey: ${pubkey.take(8)}...")
        _isLoadingProfile.value = true

        viewModelScope.launch {
            try {
                // Connect to relays
                nostrClient.connect()

                // Fetch the profile
                nostrClient.fetchProfiles(listOf(pubkey))

                // Observe profile updates
                nostrClient.observeProfile(pubkey).collect { profile ->
                    if (profile != null) {
                        Log.d(TAG, "Received profile: ${profile.displayNameOrName}")
                        _userProfile.value = profile
                        _isLoadingProfile.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch profile", e)
                _isLoadingProfile.value = false
            }
        }
    }

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
        _userProfile.value = null
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

    override fun onCleared() {
        super.onCleared()
        nostrClient.disconnect()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(context.applicationContext) as T
        }
    }
}
