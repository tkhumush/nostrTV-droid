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

    init {
        // Try to restore existing session
        bunkerAuthManager.restoreSession()
    }

    fun startLogin() {
        Log.d(TAG, "Starting bunker login flow")
        bunkerAuthManager.generateConnectionString()
    }

    fun cancelLogin() {
        Log.d(TAG, "Canceling login")
        bunkerAuthManager.logout()
    }

    fun connectWithBunkerUri(uri: String) {
        viewModelScope.launch {
            bunkerAuthManager.connectWithBunkerUri(uri)
        }
    }

    fun logout() {
        Log.d(TAG, "Logging out")
        bunkerAuthManager.logout()
    }

    fun isAuthenticated(): Boolean {
        return authState.value is AuthState.Authenticated
    }

    fun getUserPubkey(): String? {
        return (authState.value as? AuthState.Authenticated)?.pubkey
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(context.applicationContext) as T
        }
    }
}
