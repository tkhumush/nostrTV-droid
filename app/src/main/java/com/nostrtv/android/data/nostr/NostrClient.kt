package com.nostrtv.android.data.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * NostrClient handles connections to Nostr relays and manages subscriptions.
 * Implements relay pool management and event routing.
 */
class NostrClient {
    companion object {
        private const val TAG = "NostrClient"

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.nostr.band",
            "wss://relay.snort.social",
            "wss://nostr.wine",
            "wss://relay.primal.net",
            "wss://purplepag.es"  // Dedicated kind 0 (profile metadata) relay
        )

        private const val LIVE_STREAMS_SUB_ID = "live_streams"
        private const val CHAT_SUB_PREFIX = "chat_"
        private const val ZAPS_SUB_PREFIX = "zaps_"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connections = mutableMapOf<String, RelayConnection>()

    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    private val _chatMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val _zapReceipts = MutableStateFlow<Map<String, List<ZapReceipt>>>(emptyMap())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private var connectedCount = 0

    suspend fun connect(relays: List<String> = DEFAULT_RELAYS) {
        Log.d(TAG, "Connecting to ${relays.size} relays")
        _connectionState.value = ConnectionState.Connecting

        relays.forEach { url ->
            val connection = RelayConnectionFactory.create(url)
            connections[url] = connection

            // Listen for messages from this relay
            scope.launch {
                connection.messages.collect { message ->
                    handleRelayMessage(message)
                }
            }

            connection.connect()
        }
    }

    private fun handleRelayMessage(message: RelayMessage) {
        when (message) {
            is RelayMessage.Connected -> {
                connectedCount++
                Log.d(TAG, "Relay connected: ${message.url} ($connectedCount/${connections.size})")
                Log.w("profiledebug", "RELAY CONNECTED: ${message.url}")
                if (connectedCount == 1) {
                    _connectionState.value = ConnectionState.Connected
                }
            }
            is RelayMessage.Disconnected -> {
                connectedCount--
                Log.d(TAG, "Relay disconnected: ${message.url} ($connectedCount/${connections.size})")
                if (connectedCount == 0) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
            is RelayMessage.Text -> {
                processRelayText(message.url, message.content)
            }
            is RelayMessage.Error -> {
                Log.e(TAG, "Relay error from ${message.url}: ${message.error}")
                Log.w("profiledebug", "RELAY ERROR: ${message.url} - ${message.error}")
            }
        }
    }

    private fun processRelayText(relayUrl: String, text: String) {
        // Log raw message for profile subscriptions
        if (text.contains("profiles_") || text.contains("\"kind\":0") || text.contains("kind: 0")) {
            Log.w("profiledebug", "D. Raw relay message: ${text.take(200)}...")
        }

        val relayMessage = NostrProtocol.parseRelayMessage(text)
        if (relayMessage == null) {
            Log.w("profiledebug", "E. Failed to parse relay message: ${text.take(100)}")
            return
        }

        when (relayMessage) {
            is NostrRelayMessage.EventMessage -> {
                if (relayMessage.event.kind == NostrProtocol.KIND_METADATA) {
                    Log.w("profiledebug", "F. Received kind 0 event for sub: ${relayMessage.subscriptionId}")
                }
                handleEvent(relayMessage.subscriptionId, relayMessage.event)
            }
            is NostrRelayMessage.EndOfStoredEvents -> {
                Log.d(TAG, "EOSE for subscription: ${relayMessage.subscriptionId}")
                if (relayMessage.subscriptionId.startsWith("profiles_")) {
                    Log.w("profiledebug", "G. EOSE for profile subscription: ${relayMessage.subscriptionId}")
                }
            }
            is NostrRelayMessage.Notice -> {
                Log.w(TAG, "Relay notice: ${relayMessage.message}")
            }
            is NostrRelayMessage.Ok -> {
                Log.d(TAG, "Event ${relayMessage.eventId} accepted: ${relayMessage.accepted}")
            }
        }
    }

    private fun handleEvent(subscriptionId: String, event: NostrEvent) {
        when (event.kind) {
            NostrProtocol.KIND_LIVE_EVENT -> handleLiveStreamEvent(event)
            NostrProtocol.KIND_METADATA -> handleProfileEvent(event)
            NostrProtocol.KIND_TEXT_NOTE -> {
                if (subscriptionId.startsWith(CHAT_SUB_PREFIX)) {
                    handleChatEvent(subscriptionId, event)
                }
            }
            NostrProtocol.KIND_ZAP_RECEIPT -> handleZapReceiptEvent(subscriptionId, event)
        }
    }

    private fun handleLiveStreamEvent(event: NostrEvent) {
        Log.d(TAG, "Received live stream event: ${event.id}")

        val stream = parseLiveStreamEvent(event)
        if (stream != null) {
            _streams.update { currentList ->
                val existing = currentList.indexOfFirst { it.id == stream.id }
                if (existing >= 0) {
                    currentList.toMutableList().apply { set(existing, stream) }
                } else {
                    currentList + stream
                }
            }
        }
    }

    private fun parseLiveStreamEvent(event: NostrEvent): LiveStream? {
        return try {
            val dTag = event.getTagValue("d") ?: return null
            val title = event.getTagValue("title") ?: "Untitled Stream"
            val summary = event.getTagValue("summary") ?: ""
            val status = event.getTagValue("status") ?: "live"
            val streamingUrl = event.getTagValue("streaming") ?: ""
            val thumbnailUrl = event.getTagValue("image") ?: event.getTagValue("thumb") ?: ""
            val streamerPubkey = event.getTagValue("p") ?: event.pubkey

            // Parse current viewers if available
            val currentParticipants = event.getTagValue("current_participants")?.toIntOrNull() ?: 0

            // Get relay hints
            val relays = event.getTagValues("relay")

            // Get hashtags
            val tags = event.getTagValues("t")

            LiveStream(
                id = event.id,
                pubkey = event.pubkey,
                title = title,
                summary = summary,
                streamingUrl = streamingUrl,
                thumbnailUrl = thumbnailUrl,
                status = status,
                streamerPubkey = streamerPubkey,
                viewerCount = currentParticipants,
                tags = tags,
                relays = relays,
                dTag = dTag,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse live stream event", e)
            null
        }
    }

    private fun handleProfileEvent(event: NostrEvent) {
        Log.w("profiledebug", "7. handleProfileEvent for pubkey: ${event.pubkey.take(16)}...")
        try {
            val profile = parseProfileEvent(event)
            if (profile != null) {
                Log.w("profiledebug", "8. Parsed profile: ${profile.displayNameOrName}, picture: ${profile.picture?.take(50)}")
                _profiles.update { it + (event.pubkey to profile) }
                Log.w("profiledebug", "9. Profile stored, total profiles: ${_profiles.value.size}")
            }
        } catch (e: Exception) {
            Log.e("profiledebug", "ERROR: Failed to parse profile event", e)
        }
    }

    private fun parseProfileEvent(event: NostrEvent): Profile? {
        return try {
            val content = Json.parseToJsonElement(event.content).jsonObject
            Profile(
                pubkey = event.pubkey,
                name = content["name"]?.jsonPrimitive?.content,
                displayName = content["display_name"]?.jsonPrimitive?.content,
                picture = content["picture"]?.jsonPrimitive?.content,
                about = content["about"]?.jsonPrimitive?.content,
                nip05 = content["nip05"]?.jsonPrimitive?.content,
                lud16 = content["lud16"]?.jsonPrimitive?.content,
                lud06 = content["lud06"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profile content", e)
            null
        }
    }

    private fun handleChatEvent(subscriptionId: String, event: NostrEvent) {
        val streamId = subscriptionId.removePrefix(CHAT_SUB_PREFIX)
        val message = ChatMessage(
            id = event.id,
            pubkey = event.pubkey,
            content = event.content,
            createdAt = event.createdAt,
            authorName = _profiles.value[event.pubkey]?.displayNameOrName
        )

        _chatMessages.update { current ->
            val messages = current[streamId]?.toMutableList() ?: mutableListOf()
            if (messages.none { it.id == message.id }) {
                messages.add(message)
                messages.sortBy { it.createdAt }
            }
            current + (streamId to messages)
        }
    }

    private fun handleZapReceiptEvent(subscriptionId: String, event: NostrEvent) {
        // Parse zap receipt - will be fully implemented in Zaps checkpoint
        Log.d(TAG, "Received zap receipt: ${event.id}")
    }

    fun subscribeToLiveStreams(): Flow<List<LiveStream>> {
        Log.d(TAG, "Subscribing to live streams (kind 30311)")

        val filter = NostrFilter(
            kinds = listOf(NostrProtocol.KIND_LIVE_EVENT),
            limit = 100
        )

        val subscriptionMessage = NostrProtocol.createSubscription(LIVE_STREAMS_SUB_ID, filter)
        broadcast(subscriptionMessage)

        return _streams.asStateFlow()
    }

    fun subscribeToChatMessages(streamATag: String): Flow<List<ChatMessage>> {
        val subscriptionId = "$CHAT_SUB_PREFIX${streamATag.hashCode()}"
        Log.d(TAG, "Subscribing to chat for stream: $streamATag")

        val filter = NostrFilter(
            kinds = listOf(NostrProtocol.KIND_TEXT_NOTE),
            tags = mapOf("a" to listOf(streamATag)),
            limit = 200
        )

        val subscriptionMessage = NostrProtocol.createSubscription(subscriptionId, filter)
        broadcast(subscriptionMessage)

        return MutableStateFlow(_chatMessages.value[streamATag.hashCode().toString()] ?: emptyList())
    }

    fun subscribeToZapReceipts(streamATag: String): Flow<List<ZapReceipt>> {
        val subscriptionId = "$ZAPS_SUB_PREFIX${streamATag.hashCode()}"
        Log.d(TAG, "Subscribing to zaps for stream: $streamATag")

        val filter = NostrFilter(
            kinds = listOf(NostrProtocol.KIND_ZAP_RECEIPT),
            tags = mapOf("a" to listOf(streamATag)),
            limit = 50
        )

        val subscriptionMessage = NostrProtocol.createSubscription(subscriptionId, filter)
        broadcast(subscriptionMessage)

        return MutableStateFlow(_zapReceipts.value[streamATag.hashCode().toString()] ?: emptyList())
    }

    fun getProfile(pubkey: String): Profile? {
        return _profiles.value[pubkey]
    }

    fun observeProfile(pubkey: String): Flow<Profile?> {
        return _profiles.asStateFlow().let { flow ->
            flow.map { profiles -> profiles[pubkey] }
        }
    }

    fun fetchProfiles(pubkeys: List<String>) {
        Log.w("profiledebug", "A. fetchProfiles called with ${pubkeys.size} pubkeys")
        if (pubkeys.isEmpty()) return

        val unknownPubkeys = pubkeys.filter { it !in _profiles.value }
        Log.w("profiledebug", "B. Unknown pubkeys: ${unknownPubkeys.size}")
        if (unknownPubkeys.isEmpty()) return

        val filter = NostrFilter(
            kinds = listOf(NostrProtocol.KIND_METADATA),
            authors = unknownPubkeys
        )

        val subscriptionMessage = NostrProtocol.createSubscription("profiles_${System.currentTimeMillis()}", filter)
        Log.w("profiledebug", "C. Sending profile subscription: $subscriptionMessage")
        broadcast(subscriptionMessage)
    }

    private fun broadcast(message: String) {
        connections.values.forEach { connection ->
            connection.send(message)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from all relays")
        connections.values.forEach { it.disconnect() }
        connections.clear()
        connectedCount = 0
        _connectionState.value = ConnectionState.Disconnected
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
}
