package com.nostrtv.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.nostr.ConnectionState
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.NostrClientProvider
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

                // Connect to relays
                nostrClient.connect()

                // Subscribe to live streams and collect updates
                nostrClient.subscribeToLiveStreams()
                    .collect { streamList ->
                        Log.d(TAG, "Received ${streamList.size} streams")
                        _streams.value = streamList.filter { it.status == "live" }
                            .sortedByDescending { it.createdAt }

                        // Fetch profiles for streamers
                        val pubkeys = streamList.mapNotNull { it.streamerPubkey }.distinct()
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
