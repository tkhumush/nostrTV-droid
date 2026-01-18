package com.nostrtv.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.nostr.ConnectionState
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.NostrClientProvider
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val nostrClient = NostrClientProvider.instance

    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    val streams: StateFlow<List<LiveStream>> = _streams.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val profiles: StateFlow<Map<String, Profile>> = _profiles.asStateFlow()

    init {
        Log.d(TAG, "HomeViewModel initialized, connecting to relays")
        connectAndSubscribe()
    }

    private fun connectAndSubscribe() {
        viewModelScope.launch {
            try {
                // Observe connection state
                launch {
                    nostrClient.connectionState.collect { state ->
                        Log.d(TAG, "Connection state: $state")
                        _connectionState.value = state
                        if (state is ConnectionState.Connected) {
                            _isLoading.value = false
                        }
                    }
                }

                // Observe profiles
                launch {
                    nostrClient.observeProfiles().collect { profilesMap ->
                        Log.d(TAG, "Profiles updated: ${profilesMap.size} profiles")
                        _profiles.value = profilesMap
                    }
                }

                // Connect to relays
                nostrClient.connect()

                // Subscribe to live streams and collect updates
                nostrClient.subscribeToLiveStreams()
                    .collect { streamList ->
                        Log.d(TAG, "Received ${streamList.size} streams")

                        // Filter live streams, sort by most recent, keep only one per pubkey
                        val dedupedStreams = streamList
                            .filter { it.status == "live" }
                            .sortedByDescending { it.createdAt }
                            .distinctBy { it.pubkey }

                        Log.d(TAG, "After dedup: ${dedupedStreams.size} unique streams")
                        _streams.value = dedupedStreams

                        // Fetch profiles for streamers
                        val pubkeys = dedupedStreams.mapNotNull { it.streamerPubkey }.distinct()
                        nostrClient.fetchProfiles(pubkeys)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to relays", e)
                _error.value = e.message
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            nostrClient.disconnect()
            connectAndSubscribe()
        }
    }

    fun getStream(streamId: String): LiveStream? {
        return _streams.value.find { it.id == streamId }
    }

    override fun onCleared() {
        super.onCleared()
        nostrClient.disconnect()
    }
}
