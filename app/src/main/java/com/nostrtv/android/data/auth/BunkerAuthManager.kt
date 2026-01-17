package com.nostrtv.android.data.auth

import android.util.Log
import com.nostrtv.android.data.nostr.KeyPair
import com.nostrtv.android.data.nostr.NostrCrypto
import com.nostrtv.android.data.nostr.NostrCrypto.hexToByteArray
import com.nostrtv.android.data.nostr.NostrCrypto.toHex
import com.nostrtv.android.data.nostr.NostrEvent
import com.nostrtv.android.data.nostr.NostrFilter
import com.nostrtv.android.data.nostr.NostrProtocol
import com.nostrtv.android.data.nostr.NostrRelayMessage
import com.nostrtv.android.data.nostr.RelayConnection
import com.nostrtv.android.data.nostr.RelayConnectionFactory
import com.nostrtv.android.data.nostr.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NIP-46 Nostr Connect (Bunker) authentication manager.
 * Handles remote signing via the bunker protocol.
 *
 * Reference: https://github.com/nostr-protocol/nips/blob/master/46.md
 *
 * Flow:
 * 1. Client generates a keypair for encrypted communication
 * 2. Client displays QR with: nostr+connect://<client-pubkey>?relay=<relay>&secret=<secret>
 * 3. User scans QR with signer app (Amber, nsec.app, etc.)
 * 4. Signer sends "connect" response via kind 24133 event
 * 5. Client can now request signatures via kind 24133 events
 */
class BunkerAuthManager(
    private val sessionStore: SessionStore
) {
    companion object {
        private const val TAG = "BunkerAuthManager"
        private const val DEFAULT_RELAY = "wss://relay.nsec.app"
        private const val CONNECTION_TIMEOUT_MS = 120000L // 2 minutes for user to scan
        private const val REQUEST_TIMEOUT_MS = 30000L
        private const val KIND_NIP46 = 24133
        private const val SUB_ID = "nip46"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    // Client keypair for NIP-46 communication
    private var clientKeyPair: KeyPair? = null
    private var secret: String? = null
    private var bunkerPubkey: String? = null
    private var relayUrl: String = DEFAULT_RELAY
    private var relayConnection: RelayConnection? = null
    private var connectionJob: Job? = null

    // Pending requests waiting for response
    private val pendingRequests = mutableMapOf<String, (Nip46Response) -> Unit>()

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _connectionString = MutableStateFlow<String?>(null)
    val connectionString: StateFlow<String?> = _connectionString.asStateFlow()

    // User's actual pubkey (from bunker)
    private val _userPubkey = MutableStateFlow<String?>(null)
    val userPubkey: StateFlow<String?> = _userPubkey.asStateFlow()

    init {
        // Try to restore session on init
        restoreSession()
    }

    /**
     * Start the login flow by generating a connection string.
     * Returns the URI to display as QR code.
     */
    fun startLogin(relay: String = DEFAULT_RELAY): String {
        Log.d(TAG, "Starting NIP-46 login flow")

        // Generate client keypair
        clientKeyPair = NostrCrypto.generateKeyPair()
        secret = generateSecret()
        relayUrl = relay

        val clientPubkey = clientKeyPair!!.publicKey

        // Build connection URI
        // Format: nostr+connect://<client-pubkey>?relay=<relay>&secret=<secret>
        val connectionUri = buildString {
            append("nostr+connect://")
            append(clientPubkey)
            append("?relay=")
            append(java.net.URLEncoder.encode(relayUrl, "UTF-8"))
            append("&secret=")
            append(secret)
        }

        _connectionString.value = connectionUri
        _authState.value = AuthState.WaitingForConnection(connectionUri)

        Log.d(TAG, "Generated connection URI: $connectionUri")
        Log.d(TAG, "Client pubkey: $clientPubkey")

        // Start listening for connection
        startListening()

        return connectionUri
    }

    /**
     * Connect to relay and listen for NIP-46 messages.
     */
    private fun startListening() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                relayConnection = RelayConnectionFactory.create(relayUrl)
                relayConnection!!.connect()

                // Listen for messages
                relayConnection!!.messages.collect { message ->
                    handleRelayMessage(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in relay connection", e)
                _authState.value = AuthState.Error("Connection failed: ${e.message}")
            }
        }
    }

    private fun handleRelayMessage(message: RelayMessage) {
        when (message) {
            is RelayMessage.Connected -> {
                Log.d(TAG, "Connected to relay: ${message.url}")
                subscribeToNip46Events()
            }
            is RelayMessage.Text -> {
                processRelayText(message.content)
            }
            is RelayMessage.Disconnected -> {
                Log.d(TAG, "Disconnected from relay: ${message.reason}")
            }
            is RelayMessage.Error -> {
                Log.e(TAG, "Relay error: ${message.error}")
            }
        }
    }

    private fun subscribeToNip46Events() {
        val clientPubkey = clientKeyPair?.publicKey ?: return

        // Subscribe to NIP-46 events addressed to our client pubkey
        val filter = NostrFilter(
            kinds = listOf(KIND_NIP46),
            tags = mapOf("p" to listOf(clientPubkey)),
            since = System.currentTimeMillis() / 1000 - 60 // Last minute
        )

        val subMessage = NostrProtocol.createSubscription(SUB_ID, filter)
        relayConnection?.send(subMessage)
        Log.d(TAG, "Subscribed to NIP-46 events for pubkey: $clientPubkey")
    }

    private fun processRelayText(text: String) {
        val relayMessage = NostrProtocol.parseRelayMessage(text) ?: return

        when (relayMessage) {
            is NostrRelayMessage.EventMessage -> {
                if (relayMessage.event.kind == KIND_NIP46) {
                    handleNip46Event(relayMessage.event)
                }
            }
            is NostrRelayMessage.EndOfStoredEvents -> {
                Log.d(TAG, "EOSE received")
            }
            else -> {}
        }
    }

    private fun handleNip46Event(event: NostrEvent) {
        val clientKeyPair = this.clientKeyPair ?: return

        Log.d(TAG, "Received NIP-46 event from: ${event.pubkey}")

        try {
            // Decrypt the content using NIP-04
            val decrypted = NostrCrypto.decryptNip04(
                event.content,
                clientKeyPair.privateKey,
                event.pubkey
            )

            Log.d(TAG, "Decrypted NIP-46 message: $decrypted")

            val response = json.parseToJsonElement(decrypted).jsonObject
            val id = response["id"]?.jsonPrimitive?.content
            val result = response["result"]?.jsonPrimitive?.content
            val error = response["error"]?.jsonPrimitive?.content

            // Check if this is a response to a pending request
            if (id != null && pendingRequests.containsKey(id)) {
                val callback = pendingRequests.remove(id)
                callback?.invoke(Nip46Response(id, result, error))
                return
            }

            // Check if this is a "connect" ack (signer confirming connection)
            if (result == "ack" || result?.startsWith("ack") == true) {
                Log.d(TAG, "Received connect acknowledgment from bunker")
                bunkerPubkey = event.pubkey

                // Request the user's public key
                scope.launch {
                    requestUserPubkey()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process NIP-46 event", e)
        }
    }

    /**
     * Request user's public key from bunker.
     */
    private suspend fun requestUserPubkey() {
        val response = sendRequest("get_public_key", emptyList())
        if (response?.result != null) {
            val userPubkey = response.result
            Log.d(TAG, "Got user pubkey: $userPubkey")

            _userPubkey.value = userPubkey
            _authState.value = AuthState.Authenticated(userPubkey)

            // Save session
            sessionStore.saveSession(
                pubkey = userPubkey,
                bunkerPubkey = bunkerPubkey!!,
                clientPrivateKey = clientKeyPair!!.privateKey,
                relay = relayUrl,
                secret = secret!!
            )
        } else {
            Log.e(TAG, "Failed to get user pubkey: ${response?.error}")
            _authState.value = AuthState.Error(response?.error ?: "Failed to get public key")
        }
    }

    /**
     * Send a NIP-46 request to the bunker.
     */
    private suspend fun sendRequest(method: String, params: List<String>): Nip46Response? {
        val bunkerPubkey = this.bunkerPubkey ?: return null
        val clientKeyPair = this.clientKeyPair ?: return null

        val requestId = UUID.randomUUID().toString()

        // Build request JSON
        val requestJson = buildJsonObject {
            put("id", requestId)
            put("method", method)
            put("params", kotlinx.serialization.json.JsonArray(params.map {
                kotlinx.serialization.json.JsonPrimitive(it)
            }))
        }.toString()

        Log.d(TAG, "Sending NIP-46 request: $requestJson")

        // Encrypt with NIP-04
        val encrypted = NostrCrypto.encryptNip04(
            requestJson,
            clientKeyPair.privateKey,
            bunkerPubkey
        )

        // Build and send event
        val createdAt = System.currentTimeMillis() / 1000
        val tags = listOf(listOf("p", bunkerPubkey))

        val eventId = NostrCrypto.computeEventId(
            clientKeyPair.publicKey,
            createdAt,
            KIND_NIP46,
            tags,
            encrypted
        )

        val signature = NostrCrypto.signEvent(
            eventId.hexToByteArray(),
            clientKeyPair.privateKey
        )

        val eventJson = """["EVENT",{"id":"$eventId","pubkey":"${clientKeyPair.publicKey}","created_at":$createdAt,"kind":$KIND_NIP46,"tags":[["p","$bunkerPubkey"]],"content":"$encrypted","sig":"$signature"}]"""

        relayConnection?.send(eventJson)

        // Wait for response
        return withTimeout(REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pendingRequests[requestId] = { response ->
                    continuation.resume(response)
                }
            }
        }
    }

    /**
     * Request signature for an event from the bunker.
     */
    suspend fun signEvent(unsignedEvent: UnsignedEvent): NostrEvent? {
        if (_authState.value !is AuthState.Authenticated) {
            Log.w(TAG, "Cannot sign: not authenticated")
            return null
        }

        val userPubkey = _userPubkey.value ?: return null

        // Build unsigned event JSON
        val tagsJson = unsignedEvent.tags.joinToString(",") { tag ->
            "[" + tag.joinToString(",") { "\"$it\"" } + "]"
        }
        val unsignedEventJson = """{"pubkey":"$userPubkey","created_at":${unsignedEvent.createdAt},"kind":${unsignedEvent.kind},"tags":[$tagsJson],"content":"${unsignedEvent.content}"}"""

        Log.d(TAG, "Requesting signature for event: $unsignedEventJson")

        val response = sendRequest("sign_event", listOf(unsignedEventJson))

        if (response?.result != null) {
            try {
                // Parse the signed event
                val signedEventJson = json.parseToJsonElement(response.result).jsonObject
                return NostrEvent(
                    id = signedEventJson["id"]?.jsonPrimitive?.content ?: "",
                    pubkey = signedEventJson["pubkey"]?.jsonPrimitive?.content ?: "",
                    createdAt = signedEventJson["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    kind = signedEventJson["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    tags = signedEventJson["tags"]?.jsonArray?.map { tagArray ->
                        tagArray.jsonArray.map { it.jsonPrimitive.content }
                    } ?: emptyList(),
                    content = signedEventJson["content"]?.jsonPrimitive?.content ?: "",
                    sig = signedEventJson["sig"]?.jsonPrimitive?.content ?: ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse signed event", e)
            }
        } else {
            Log.e(TAG, "Sign request failed: ${response?.error}")
        }

        return null
    }

    /**
     * Restore session from storage.
     */
    fun restoreSession(): Boolean {
        val savedSession = sessionStore.getSavedSession() ?: return false

        Log.d(TAG, "Restoring session for user: ${savedSession.userPubkey.take(8)}...")

        clientKeyPair = KeyPair(savedSession.clientPrivateKey, NostrCrypto.getPublicKey(savedSession.clientPrivateKey))
        bunkerPubkey = savedSession.bunkerPubkey
        relayUrl = savedSession.relay
        secret = savedSession.secret
        _userPubkey.value = savedSession.userPubkey
        _authState.value = AuthState.Authenticated(savedSession.userPubkey)

        // Reconnect to relay
        startListening()

        return true
    }

    /**
     * Logout and clear session.
     */
    fun logout() {
        Log.d(TAG, "Logging out")

        connectionJob?.cancel()
        relayConnection?.disconnect()

        clientKeyPair = null
        bunkerPubkey = null
        secret = null
        _userPubkey.value = null
        _connectionString.value = null
        _authState.value = AuthState.NotAuthenticated

        sessionStore.clearSession()
    }

    /**
     * Cancel pending login attempt.
     */
    fun cancelLogin() {
        Log.d(TAG, "Canceling login")
        connectionJob?.cancel()
        relayConnection?.disconnect()
        _connectionString.value = null
        _authState.value = AuthState.NotAuthenticated
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.toHex()
    }
}

data class Nip46Response(
    val id: String,
    val result: String?,
    val error: String?
)

data class UnsignedEvent(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val createdAt: Long = System.currentTimeMillis() / 1000
)

sealed class AuthState {
    object NotAuthenticated : AuthState()
    data class WaitingForConnection(val connectionString: String) : AuthState()
    object Connecting : AuthState()
    data class Authenticated(val pubkey: String) : AuthState()
    data class Error(val message: String) : AuthState()
}
