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
import com.nostrtv.android.data.nostr.Profile
import com.nostrtv.android.data.nostr.ZapReceipt
import com.nostrtv.android.data.zap.ZapInvoiceResult
import com.nostrtv.android.data.zap.ZapManager
import com.nostrtv.android.ui.zap.ZapFlowState
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
    private val zapManager = ZapManager(remoteSignerManager, nostrClient)

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

    // Zap flow state
    private val _zapFlowState = MutableStateFlow<ZapFlowState>(ZapFlowState.Hidden)
    val zapFlowState: StateFlow<ZapFlowState> = _zapFlowState.asStateFlow()

    private val _streamerProfile = MutableStateFlow<Profile?>(null)
    val streamerProfile: StateFlow<Profile?> = _streamerProfile.asStateFlow()

    // Sign-in prompt state
    private val _showSignInPrompt = MutableStateFlow(false)
    val showSignInPrompt: StateFlow<Boolean> = _showSignInPrompt.asStateFlow()

    private var currentStreamATag: String? = null
    private var hasAnnouncedPresence = false
    private var pendingZapAmountSats: Long = 0
    private var pendingZapTimestamp: Long = 0

    init {
        // Check authentication status
        _isAuthenticated.value = remoteSignerManager.isAuthenticated()
    }

    fun loadStream(stream: LiveStream, profile: Profile? = null) {
        Log.d(TAG, "Loading stream: ${stream.title}")
        _stream.value = stream
        _streamerProfile.value = profile
        _isLoading.value = false

        currentStreamATag = stream.aTag
        subscribeToStreamData(stream.aTag)

        // Fetch streamer profile if not provided
        if (profile == null && stream.streamerPubkey != null) {
            viewModelScope.launch {
                val fetchedProfile = nostrClient.getProfile(stream.streamerPubkey)
                if (fetchedProfile != null) {
                    _streamerProfile.value = fetchedProfile
                }
            }
        }
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
                            val sortedZaps = zaps.sortedByDescending { it.createdAt }
                            _zapReceipts.value = sortedZaps
                            // Check if any of these zaps match our pending zap
                            checkForZapConfirmation(sortedZaps)
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
     * If not authenticated, shows the sign-in prompt instead.
     */
    fun sendChatMessage(content: String) {
        val aTag = currentStreamATag
        if (aTag == null) {
            Log.w(TAG, "No stream aTag, cannot send chat message")
            return
        }

        if (!remoteSignerManager.isAuthenticated()) {
            Log.d(TAG, "User not authenticated, showing sign-in prompt")
            _showSignInPrompt.value = true
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

    // ==================== Zap Flow Methods ====================

    /**
     * Start the zap flow by showing the amount selection dialog.
     * If not authenticated, shows the sign-in prompt instead.
     */
    fun startZapFlow() {
        if (!remoteSignerManager.isAuthenticated()) {
            Log.d(TAG, "User not authenticated, showing sign-in prompt")
            _showSignInPrompt.value = true
            return
        }

        val profile = _streamerProfile.value
        if (profile == null) {
            Log.w(TAG, "Cannot zap: no streamer profile")
            _zapFlowState.value = ZapFlowState.Error("Streamer profile not available")
            return
        }

        if (profile.lud16.isNullOrEmpty() && profile.lud06.isNullOrEmpty()) {
            Log.w(TAG, "Cannot zap: streamer has no Lightning address")
            _zapFlowState.value = ZapFlowState.Error("Streamer has no Lightning address")
            return
        }

        Log.d(TAG, "Starting zap flow for ${profile.displayNameOrName}")
        _zapFlowState.value = ZapFlowState.SelectAmount
    }

    /**
     * Handle amount selection and request the Lightning invoice.
     */
    fun selectZapAmount(amountSats: Long) {
        val profile = _streamerProfile.value
        if (profile == null) {
            _zapFlowState.value = ZapFlowState.Error("Streamer profile not available")
            return
        }

        Log.d(TAG, "Zap amount selected: $amountSats sats")
        _zapFlowState.value = ZapFlowState.Loading(amountSats)
        pendingZapAmountSats = amountSats
        pendingZapTimestamp = System.currentTimeMillis() / 1000

        viewModelScope.launch {
            try {
                val result = zapManager.requestZapInvoice(
                    recipientProfile = profile,
                    amountSats = amountSats,
                    comment = "",
                    aTag = currentStreamATag
                )

                when (result) {
                    is ZapInvoiceResult.Success -> {
                        Log.d(TAG, "Got Lightning invoice: ${result.invoice.take(50)}...")
                        _zapFlowState.value = ZapFlowState.ShowQR(
                            invoice = result.invoice,
                            amountSats = result.amountSats
                        )
                    }
                    is ZapInvoiceResult.Error -> {
                        Log.e(TAG, "Failed to get invoice: ${result.message}")
                        _zapFlowState.value = ZapFlowState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting zap invoice", e)
                _zapFlowState.value = ZapFlowState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Dismiss the zap flow overlay.
     */
    fun dismissZapFlow() {
        Log.d(TAG, "Dismissing zap flow")
        _zapFlowState.value = ZapFlowState.Hidden
        pendingZapAmountSats = 0
        pendingZapTimestamp = 0
    }

    /**
     * Dismiss the sign-in prompt overlay.
     */
    fun dismissSignInPrompt() {
        Log.d(TAG, "Dismissing sign-in prompt")
        _showSignInPrompt.value = false
    }

    /**
     * Check if a zap receipt matches our pending zap.
     * Called when new zap receipts arrive.
     */
    private fun checkForZapConfirmation(zaps: List<ZapReceipt>) {
        // Only check if we're waiting for a zap (showing QR)
        val currentState = _zapFlowState.value
        if (currentState !is ZapFlowState.ShowQR) return
        if (pendingZapTimestamp == 0L) return

        val userPubkey = remoteSignerManager.getUserPubkey() ?: return

        // Look for a zap from us that was created after we started the flow
        val matchingZap = zaps.find { zap ->
            zap.senderPubkey == userPubkey &&
            zap.createdAt >= pendingZapTimestamp - 10 && // Allow 10 second buffer
            zap.amountSats == pendingZapAmountSats
        }

        if (matchingZap != null) {
            Log.d(TAG, "Found matching zap receipt! Confirming zap of ${matchingZap.amountSats} sats")
            _zapFlowState.value = ZapFlowState.Confirmed(matchingZap.amountSats)
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
