package com.nostrtv.android.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.auth.AuthState
import com.nostrtv.android.data.auth.BunkerAuthManager
import com.nostrtv.android.data.auth.SessionStore
import com.nostrtv.android.data.nostr.NostrClientProvider
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ProfileViewModel(
    context: Context
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val sessionStore = SessionStore(context)
    private val bunkerAuthManager = BunkerAuthManager(sessionStore)
    private val nostrClient = NostrClientProvider.instance

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
        Log.w("profiledebug", "1. fetchUserProfile called for pubkey: ${pubkey.take(16)}...")
        _isLoadingProfile.value = true

        viewModelScope.launch {
            try {
                // Check if profile is already cached
                val cachedProfile = nostrClient.getProfile(pubkey)
                if (cachedProfile != null) {
                    Log.w("profiledebug", "2. Profile found in cache: ${cachedProfile.displayNameOrName}")
                    _userProfile.value = cachedProfile
                    _isLoadingProfile.value = false
                    return@launch
                }

                Log.w("profiledebug", "2. Profile not in cache, requesting from relays...")

                // Request the profile from relays
                nostrClient.fetchProfiles(listOf(pubkey))
                Log.w("profiledebug", "3. Profile request sent, waiting for response...")

                // Wait for profile with timeout (15 seconds)
                val profile = withTimeoutOrNull(15000) {
                    nostrClient.observeProfile(pubkey).filterNotNull().first()
                }

                if (profile != null) {
                    Log.w("profiledebug", "4. Received profile: ${profile.displayNameOrName}")
                    _userProfile.value = profile
                } else {
                    Log.w("profiledebug", "4. Timeout waiting for profile, pubkey may not have kind 0 event")
                }
                _isLoadingProfile.value = false
            } catch (e: Exception) {
                Log.e("profiledebug", "ERROR: Failed to fetch profile", e)
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
        // Don't disconnect - shared NostrClient is managed by HomeViewModel
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(context.applicationContext) as T
        }
    }
}
