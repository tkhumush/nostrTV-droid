package com.nostrtv.android.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nostrtv.android.data.auth.RemoteSignerManager
import com.nostrtv.android.data.auth.SessionStore
import com.nostrtv.android.data.nostr.ChatManager
import com.nostrtv.android.data.nostr.ChatMessage
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.NostrClientProvider
import com.nostrtv.android.data.nostr.PresenceManager
import com.nostrtv.android.data.nostr.ZapReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    context: Context
) : ViewModel() {
    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val nostrClient = NostrClientProvider.instance
    private val sessionStore = SessionStore(context)
    private val remoteSignerManager = RemoteSignerManager(sessionStore)
    private val presenceManager = PresenceManager(remoteSignerManager, nostrClient)
    private val chatManager = ChatManager(remoteSignerManager, nostrClient)

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

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _isSendingMessage = MutableStateFlow(false)
    val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()

    private var currentStreamATag: String? = null
    private var hasAnnouncedPresence = false

    init {
        // Check authentication status
        _isAuthenticated.value = remoteSignerManager.isAuthenticated()
    }

    fun loadStream(stream: LiveStream) {
        Log.d(TAG, "Loading stream: ${stream.title}")
        _stream.value = stream
        _isLoading.value = false

        currentStreamATag = stream.aTag
        subscribeToStreamData(stream.aTag)
    }

    private fun subscribeToStreamData(aTag: String) {
        // Subscribe to both chat and zaps in a single relay subscription
        nostrClient.subscribeToStreamEvents(aTag)

        viewModelScope.launch {
            try {
                // Observe chat messages
                launch {
                    nostrClient.observeChatMessages(aTag)
                        .collect { messages ->
                            Log.d(TAG, "Received ${messages.size} chat messages")
                            _chatMessages.value = messages.sortedByDescending { it.createdAt }
                        }
                }

                // Observe zap receipts
                launch {
                    nostrClient.observeZapReceipts(aTag)
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
        val aTag = currentStreamATag
        Log.w("presence", "publishPresence called: joined=$joined, aTag=$aTag")

        if (aTag == null) {
            Log.w("presence", "No stream aTag, skipping presence")
            return
        }

        Log.w("presence", "Checking authentication status...")
        Log.w("presence", "RemoteSignerManager.isAuthenticated: ${remoteSignerManager.isAuthenticated()}")
        Log.w("presence", "RemoteSignerManager.getUserPubkey: ${remoteSignerManager.getUserPubkey()?.take(16)}")

        viewModelScope.launch {
            if (joined) {
                Log.w("presence", "Attempting to publish presence JOIN for stream: $aTag")
                val success = presenceManager.announceJoin(aTag)
                if (success) {
                    hasAnnouncedPresence = true
                    Log.w("presence", "Successfully announced presence join")
                } else {
                    Log.w("presence", "Failed to announce presence (user may not be authenticated)")
                }
            } else {
                if (hasAnnouncedPresence) {
                    Log.w("presence", "Attempting to publish presence LEAVE")
                    val success = presenceManager.announceLeave()
                    if (success) {
                        hasAnnouncedPresence = false
                        Log.w("presence", "Successfully announced presence leave")
                    }
                } else {
                    Log.w("presence", "No presence was announced, skipping leave")
                }
            }
        }
    }

    /**
     * Send a chat message to the current stream.
     */
    fun sendChatMessage(content: String) {
        val aTag = currentStreamATag
        if (aTag == null) {
            Log.w(TAG, "No stream aTag, cannot send chat message")
            return
        }

        if (!remoteSignerManager.isAuthenticated()) {
            Log.w(TAG, "Not authenticated, cannot send chat message")
            return
        }

        viewModelScope.launch {
            _isSendingMessage.value = true
            try {
                val success = chatManager.sendMessage(aTag, content)
                if (success) {
                    Log.d(TAG, "Chat message sent successfully")
                } else {
                    Log.w(TAG, "Failed to send chat message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending chat message", e)
            } finally {
                _isSendingMessage.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Announce leaving when ViewModel is cleared (user navigates away)
        viewModelScope.launch {
            if (hasAnnouncedPresence) {
                presenceManager.announceLeave()
            }
        }
        // Don't disconnect - shared NostrClient is managed by HomeViewModel
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return PlayerViewModel(context.applicationContext) as T
        }
    }
}
