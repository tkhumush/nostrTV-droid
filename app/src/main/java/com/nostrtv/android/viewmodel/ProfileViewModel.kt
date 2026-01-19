package com.nostrtv.android.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.auth.AuthState
import com.nostrtv.android.data.auth.RemoteSignerManager
import com.nostrtv.android.data.auth.SessionStore
import com.nostrtv.android.data.nostr.NostrClient
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ProfileViewModel using RemoteSignerManager for NIP-46 authentication.
 */
class ProfileViewModel(
    context: Context
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val sessionStore = SessionStore(context)
    private val remoteSignerManager = RemoteSignerManager(sessionStore)
    private val nostrClient = NostrClient()

    val authState: StateFlow<AuthState> = remoteSignerManager.authState
    val connectionUri: StateFlow<String?> = remoteSignerManager.connectionUri
    val userPubkey: StateFlow<String?> = remoteSignerManager.userPubkey

    private val _userProfile = MutableStateFlow<Profile?>(null)
    val userProfile: StateFlow<Profile?> = _userProfile.asStateFlow()

    init {
        // Observe auth state changes to fetch profile when authenticated
        viewModelScope.launch {
            remoteSignerManager.authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        Log.d(TAG, "Authenticated, fetching profile for: ${state.pubkey.take(16)}...")
                        fetchUserProfile(state.pubkey)
                    }
                    else -> {
                        _userProfile.value = null
                    }
                }
            }
        }
    }

    /**
     * Start the NIP-46 login flow.
     */
    fun startLogin() {
        Log.d(TAG, "Starting login flow")
        remoteSignerManager.startLogin()
    }

    /**
     * Cancel the pending login.
     */
    fun cancelLogin() {
        Log.d(TAG, "Canceling login")
        remoteSignerManager.cancelLogin()
    }

    /**
     * Logout and clear session.
     */
    fun logout() {
        Log.d(TAG, "Logging out")
        remoteSignerManager.logout()
        _userProfile.value = null
    }

    /**
     * Fetch the user's profile from relays.
     */
    private fun fetchUserProfile(pubkey: String) {
        viewModelScope.launch {
            // Request profile fetch from relays
            nostrClient.fetchProfiles(listOf(pubkey))

            // Observe profile updates
            nostrClient.observeProfile(pubkey).collect { profile ->
                if (profile != null) {
                    Log.d(TAG, "Fetched profile: ${profile.name ?: profile.displayName}")
                    _userProfile.value = profile
                } else {
                    Log.w(TAG, "No profile found for pubkey: ${pubkey.take(16)}...")
                    // Create a minimal profile with just the pubkey
                    _userProfile.value = Profile(pubkey = pubkey)
                }
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(context.applicationContext) as T
        }
    }
}
