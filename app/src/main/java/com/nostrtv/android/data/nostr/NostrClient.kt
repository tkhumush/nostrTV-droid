package com.nostrtv.android.data.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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
            "wss://nos.lol",
            "wss://relay.fountain.fm",    // Streaming-focused relay (used by zap.stream)
            "wss://relay.divine.video",   // Streaming-focused relay (used by zap.stream)
            "wss://purplepag.es"          // Dedicated kind 0 (profile metadata) relay
        )

        private const val LIVE_STREAMS_SUB_ID = "live_streams"
        private const val STREAM_EVENTS_SUB_PREFIX = "stream_"
        private const val FOLLOW_LIST_SUB_PREFIX = "follows_"

        // Memory limits
        private const val MAX_CHAT_MESSAGES = 500
        private const val MAX_ZAP_RECEIPTS = 50

        // Admin pubkey for curated streams
        const val ADMIN_PUBKEY = "f67a7093fdd829fae5796250cf0932482b1d7f40900110d0d932b5a7fb37755d"
    }

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connections = mutableMapOf<String, RelayConnection>()

    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    private val _chatMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val _zapReceipts = MutableStateFlow<Map<String, List<ZapReceipt>>>(emptyMap())
    private val _followLists = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

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
            NostrProtocol.KIND_CONTACTS -> {
                // Follow list (kind 3)
                if (subscriptionId.startsWith(FOLLOW_LIST_SUB_PREFIX)) {
                    handleFollowListEvent(event)
                }
            }
            NostrProtocol.KIND_LIVE_CHAT -> {
                // NIP-53 live chat messages (kind 1311)
                if (subscriptionId.startsWith(STREAM_EVENTS_SUB_PREFIX)) {
                    handleChatEvent(subscriptionId, event)
                }
            }
            NostrProtocol.KIND_ZAP_RECEIPT -> {
                if (subscriptionId.startsWith(STREAM_EVENTS_SUB_PREFIX)) {
                    handleZapReceiptEvent(subscriptionId, event)
                }
            }
        }
    }

    private fun handleLiveStreamEvent(event: NostrEvent) {
        Log.d(TAG, "Received live stream event: ${event.id}")

        val stream = parseLiveStreamEvent(event)
        if (stream != null) {
            _streams.update { currentList ->
                // NIP-33: Replace events with same pubkey + d-tag (parameterized replaceable events)
                // Only replace if the new event is newer (by created_at timestamp)
                val existingIndex = currentList.indexOfFirst {
                    it.pubkey == stream.pubkey && it.dTag == stream.dTag
                }
                if (existingIndex >= 0) {
                    val oldStream = currentList[existingIndex]
                    if (stream.createdAt >= oldStream.createdAt) {
                        Log.d(TAG, "Replacing stream: ${stream.dTag} from ${stream.pubkey.take(16)} - status: ${oldStream.status} -> ${stream.status} (newer: ${stream.createdAt} >= ${oldStream.createdAt})")
                        currentList.toMutableList().apply { set(existingIndex, stream) }
                    } else {
                        Log.d(TAG, "Ignoring older stream event: ${stream.dTag} from ${stream.pubkey.take(16)} (${stream.createdAt} < ${oldStream.createdAt})")
                        currentList // Keep the existing newer stream
                    }
                } else {
                    Log.d(TAG, "Adding new stream: ${stream.dTag} from ${stream.pubkey.take(16)} - status: ${stream.status}")
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

    private fun handleFollowListEvent(event: NostrEvent) {
        Log.d(TAG, "Received follow list for pubkey: ${event.pubkey.take(16)}...")
        val followedPubkeys = event.getAllTags("p").mapNotNull { it.getOrNull(1) }.toSet()
        Log.d(TAG, "Follow list contains ${followedPubkeys.size} pubkeys")
        _followLists.update { it + (event.pubkey to followedPubkeys) }
    }

    fun fetchFollowList(pubkey: String) {
        Log.d(TAG, "Fetching follow list for: ${pubkey.take(16)}...")
        if (pubkey in _followLists.value) {
            Log.d(TAG, "Follow list already cached for: ${pubkey.take(16)}")
            return
        }

        val filter = NostrFilter(
            kinds = listOf(NostrProtocol.KIND_CONTACTS),
            authors = listOf(pubkey),
            limit = 1
        )

        val subscriptionMessage = NostrProtocol.createSubscription("${FOLLOW_LIST_SUB_PREFIX}${pubkey.take(8)}", filter)
        broadcast(subscriptionMessage)
    }

    fun observeFollowList(pubkey: String): Flow<Set<String>> {
        return _followLists.asStateFlow().map { it[pubkey] ?: emptySet() }
    }

    fun getFollowList(pubkey: String): Set<String>? {
        return _followLists.value[pubkey]
    }

    private fun handleChatEvent(subscriptionId: String, event: NostrEvent) {
        val streamId = subscriptionId.removePrefix(STREAM_EVENTS_SUB_PREFIX)
        val profile = _profiles.value[event.pubkey]

        val message = ChatMessage(
            id = event.id,
            pubkey = event.pubkey,
            content = event.content,
            createdAt = event.createdAt,
            authorName = profile?.displayNameOrName,
            authorPicture = profile?.picture
        )

        _chatMessages.update { current ->
            val messages = current[streamId]?.toMutableList() ?: mutableListOf()
            if (messages.none { it.id == message.id }) {
                messages.add(message)
                messages.sortBy { it.createdAt }
                // Keep only the latest 500 messages to prevent unbounded memory growth
                while (messages.size > MAX_CHAT_MESSAGES) {
                    messages.removeAt(0)
                }
            }
            current + (streamId to messages)
        }

        // Fetch profile if we don't have it
        if (event.pubkey !in _profiles.value) {
            fetchProfiles(listOf(event.pubkey))
        }
    }

    private fun handleZapReceiptEvent(subscriptionId: String, event: NostrEvent) {
        Log.d(TAG, "Received zap receipt: ${event.id}")

        val streamKey = subscriptionId.removePrefix(STREAM_EVENTS_SUB_PREFIX)
        val zapReceipt = parseZapReceiptEvent(event)

        if (zapReceipt != null) {
            _zapReceipts.update { current ->
                val zaps = current[streamKey]?.toMutableList() ?: mutableListOf()
                if (zaps.none { it.id == zapReceipt.id }) {
                    zaps.add(zapReceipt)
                    zaps.sortByDescending { it.createdAt }
                    // Keep only the latest zaps to prevent unbounded memory growth
                    while (zaps.size > MAX_ZAP_RECEIPTS) {
                        zaps.removeAt(zaps.lastIndex)
                    }
                }
                current + (streamKey to zaps)
            }

            // Fetch sender profile if we don't have it
            zapReceipt.senderPubkey?.let { pubkey ->
                if (pubkey !in _profiles.value) {
                    fetchProfiles(listOf(pubkey))
                }
            }
        }
    }

    private fun parseZapReceiptEvent(event: NostrEvent): ZapReceipt? {
        return try {
            val bolt11 = event.getTagValue("bolt11") ?: ""
            val preimage = event.getTagValue("preimage")
            val descriptionJson = event.getTagValue("description") ?: return null

            // Parse the zap request from description
            val zapRequest = Json.parseToJsonElement(descriptionJson).jsonObject

            // Get sender pubkey from the zap request
            val senderPubkey = zapRequest["pubkey"]?.jsonPrimitive?.content

            // Get recipient from p tag
            val recipientPubkey = event.getTagValue("p") ?: ""

            // Get amount from the zap request's amount tag or parse from bolt11
            var amountMilliSats = 0L
            val zapRequestTags = zapRequest["tags"]?.jsonArray
            zapRequestTags?.forEach { tag ->
                val tagArray = tag.jsonArray
                if (tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "amount") {
                    amountMilliSats = tagArray[1].jsonPrimitive.content.toLongOrNull() ?: 0L
                }
            }

            // Get message from zap request content
            val message = zapRequest["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            // Get sender profile if available
            val senderProfile = senderPubkey?.let { _profiles.value[it] }

            ZapReceipt(
                id = event.id,
                bolt11 = bolt11,
                preimage = preimage,
                description = descriptionJson,
                senderPubkey = senderPubkey,
                recipientPubkey = recipientPubkey,
                amountMilliSats = amountMilliSats,
                createdAt = event.createdAt,
                senderName = senderProfile?.displayNameOrName,
                senderPicture = senderProfile?.picture,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse zap receipt event", e)
            null
        }
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

    /**
     * Subscribe to all stream-related events (chat messages + zap receipts) in a single subscription.
     * This is more efficient than separate subscriptions since they share the same `a` tag filter.
     */
    fun subscribeToStreamEvents(streamATag: String) {
        val streamKey = streamATag.hashCode().toString()
        val subscriptionId = "$STREAM_EVENTS_SUB_PREFIX$streamKey"
        Log.d(TAG, "Subscribing to stream events (chat + zaps) for: $streamATag (key: $streamKey)")

        val filter = NostrFilter(
            kinds = listOf(NostrProtocol.KIND_LIVE_CHAT, NostrProtocol.KIND_ZAP_RECEIPT),
            tags = mapOf("a" to listOf(streamATag)),
            limit = 200
        )

        val subscriptionMessage = NostrProtocol.createSubscription(subscriptionId, filter)
        broadcast(subscriptionMessage)
    }

    /**
     * Get a flow of chat messages for a stream. Call subscribeToStreamEvents() first.
     */
    fun observeChatMessages(streamATag: String): Flow<List<ChatMessage>> {
        val streamKey = streamATag.hashCode().toString()

        return _chatMessages.asStateFlow().map { messagesMap ->
            val messages = messagesMap[streamKey] ?: emptyList()
            // Enrich messages with updated profile info
            messages.map { msg ->
                val profile = _profiles.value[msg.pubkey]
                if (profile != null && (msg.authorName != profile.displayNameOrName || msg.authorPicture != profile.picture)) {
                    msg.copy(
                        authorName = profile.displayNameOrName,
                        authorPicture = profile.picture
                    )
                } else {
                    msg
                }
            }
        }
    }

    /**
     * Get a flow of zap receipts for a stream. Call subscribeToStreamEvents() first.
     */
    fun observeZapReceipts(streamATag: String): Flow<List<ZapReceipt>> {
        val streamKey = streamATag.hashCode().toString()

        return _zapReceipts.asStateFlow().map { zapsMap ->
            val zaps = zapsMap[streamKey] ?: emptyList()
            // Enrich zaps with updated profile info
            zaps.map { zap ->
                val profile = zap.senderPubkey?.let { _profiles.value[it] }
                if (profile != null && (zap.senderName != profile.displayNameOrName || zap.senderPicture != profile.picture)) {
                    zap.copy(
                        senderName = profile.displayNameOrName,
                        senderPicture = profile.picture
                    )
                } else {
                    zap
                }
            }
        }
    }

    fun getProfile(pubkey: String): Profile? {
        return _profiles.value[pubkey]
    }

    fun observeProfile(pubkey: String): Flow<Profile?> {
        return _profiles.asStateFlow().let { flow ->
            flow.map { profiles -> profiles[pubkey] }
        }
    }

    fun observeProfiles(): Flow<Map<String, Profile>> {
        return _profiles.asStateFlow()
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

    /**
     * Publish a signed event to all connected relays.
     * @param signedEventJson The signed event JSON (just the event object, not the ["EVENT", ...] wrapper)
     */
    fun publishEvent(signedEventJson: String) {
        val eventMessage = """["EVENT",$signedEventJson]"""
        Log.d(TAG, "Publishing event: ${signedEventJson.take(100)}...")
        broadcast(eventMessage)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from all relays")
        // Cancel all running coroutines to prevent leaks
        scope.cancel()
        // Recreate scope for potential reconnection
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
