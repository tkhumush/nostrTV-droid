package com.nostrtv.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.nostr.ConnectionState
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.NostrClient
import com.nostrtv.android.data.nostr.NostrClientProvider
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class HomeTab {
    CURATED,
    FOLLOWING
}

class HomeViewModel : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val nostrClient = NostrClientProvider.instance

    private val _allStreams = MutableStateFlow<List<LiveStream>>(emptyList())

    private val _selectedTab = MutableStateFlow(HomeTab.CURATED)
    val selectedTab: StateFlow<HomeTab> = _selectedTab.asStateFlow()

    private val _curatedStreams = MutableStateFlow<List<LiveStream>>(emptyList())
    val curatedStreams: StateFlow<List<LiveStream>> = _curatedStreams.asStateFlow()

    private val _followingStreams = MutableStateFlow<List<LiveStream>>(emptyList())
    val followingStreams: StateFlow<List<LiveStream>> = _followingStreams.asStateFlow()

    private val _adminFollowList = MutableStateFlow<Set<String>>(emptySet())
    private val _userFollowList = MutableStateFlow<Set<String>>(emptySet())

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

    fun selectTab(tab: HomeTab) {
        _selectedTab.value = tab
    }

    fun setUserPubkey(pubkey: String?) {
        if (pubkey != null) {
            Log.d(TAG, "Fetching follow list for user: ${pubkey.take(16)}...")
            nostrClient.fetchFollowList(pubkey)
            viewModelScope.launch {
                nostrClient.observeFollowList(pubkey).collect { follows ->
                    Log.d(TAG, "User follow list updated: ${follows.size} follows")
                    _userFollowList.value = follows
                    updateFilteredStreams()
                }
            }
        } else {
            _userFollowList.value = emptySet()
            updateFilteredStreams()
        }
    }

    private fun updateFilteredStreams() {
        val allStreams = _allStreams.value
        val adminFollows = _adminFollowList.value
        val userFollows = _userFollowList.value

        // Curated: streams from pubkeys that admin follows
        _curatedStreams.value = if (adminFollows.isEmpty()) {
            allStreams // Show all streams while admin follow list is loading
        } else {
            allStreams.filter { stream ->
                stream.pubkey in adminFollows || stream.streamerPubkey in adminFollows
            }
        }

        // Following: streams from pubkeys that user follows
        _followingStreams.value = allStreams.filter { stream ->
            stream.pubkey in userFollows || stream.streamerPubkey in userFollows
        }

        Log.d(TAG, "Filtered streams - Curated: ${_curatedStreams.value.size}, Following: ${_followingStreams.value.size}")
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
                            // Fetch admin follow list once connected
                            nostrClient.fetchFollowList(NostrClient.ADMIN_PUBKEY)
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

                // Observe admin follow list
                launch {
                    nostrClient.observeFollowList(NostrClient.ADMIN_PUBKEY).collect { follows ->
                        Log.d(TAG, "Admin follow list updated: ${follows.size} follows")
                        _adminFollowList.value = follows
                        updateFilteredStreams()
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
                        _allStreams.value = dedupedStreams
                        updateFilteredStreams()

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
        return _allStreams.value.find { it.id == streamId }
    }

    /**
     * Fetch a user's profile from relays.
     */
    fun fetchUserProfile(pubkey: String) {
        nostrClient.fetchProfiles(listOf(pubkey))
    }

    override fun onCleared() {
        super.onCleared()
        nostrClient.disconnect()
    }
}
