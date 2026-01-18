package com.nostrtv.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.nostr.ChatMessage
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.NostrClientProvider
import com.nostrtv.android.data.nostr.ZapReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {
    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val nostrClient = NostrClientProvider.instance

    private val _stream = MutableStateFlow<LiveStream?>(null)
    val stream: StateFlow<LiveStream?> = _stream.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _zapReceipts = MutableStateFlow<List<ZapReceipt>>(emptyList())
    val zapReceipts: StateFlow<List<ZapReceipt>> = _zapReceipts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentStreamATag: String? = null

    fun loadStream(stream: LiveStream) {
        Log.d(TAG, "Loading stream: ${stream.title}")
        _stream.value = stream
        _isLoading.value = false

        currentStreamATag = stream.aTag
        subscribeToStreamData(stream.aTag)
    }

    private fun subscribeToStreamData(aTag: String) {
        viewModelScope.launch {
            try {
                // Subscribe to chat messages
                launch {
                    nostrClient.subscribeToChatMessages(aTag)
                        .collect { messages ->
                            Log.d(TAG, "Received ${messages.size} chat messages")
                            _chatMessages.value = messages.sortedByDescending { it.createdAt }
                        }
                }

                // Subscribe to zap receipts
                launch {
                    nostrClient.subscribeToZapReceipts(aTag)
                        .collect { zaps ->
                            Log.d(TAG, "Received ${zaps.size} zap receipts")
                            _zapReceipts.value = zaps.sortedByDescending { it.createdAt }
                        }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to stream data", e)
                _error.value = e.message
            }
        }
    }

    fun publishPresence(joined: Boolean) {
        // Will be implemented in Bunker Login checkpoint when user is authenticated
        Log.d(TAG, "Presence event: joined=$joined for stream ${currentStreamATag}")
    }

    override fun onCleared() {
        super.onCleared()
        publishPresence(false)
        // Don't disconnect - shared NostrClient is managed by HomeViewModel
    }
}
