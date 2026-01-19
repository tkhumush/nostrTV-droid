package com.nostrtv.android.data.auth

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Remote signer manager using Quartz's NIP-44 for encryption.
 *
 * Flow:
 * 1. Generate ephemeral keypair and connection URI
 * 2. Display QR code for user to scan
 * 3. Connect to relay and listen for bunker responses
 * 4. On connect ack, call get_public_key
 * 5. Store session and mark authenticated
 */
class RemoteSignerManager(
    private val sessionStore: SessionStore
) {
    companion object {
        private const val TAG = "RemoteSignerManager"
        private const val DEFAULT_RELAY = "wss://relay.primal.net"
        private const val KIND_NIP46 = 24133
        private const val TIMEOUT_MS = 90_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val okHttpClient = OkHttpClient()
    private val secp256k1 = Secp256k1.get()
    private val secureRandom = SecureRandom()

    // Ephemeral client keypair for this session
    private var clientKeyPair: KeyPair? = null
    private var clientSigner: NostrSignerInternal? = null
    private var clientPrivateKey: ByteArray? = null
    private var clientPublicKey: String? = null
    private var secret: String? = null
    private var bunkerPubkey: String? = null
    private var relayUrl: String = DEFAULT_RELAY
    private var webSocket: WebSocket? = null

    // Pending requests waiting for response
    private val pendingRequests = ConcurrentHashMap<String, (BunkerResponseJson) -> Unit>()

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _connectionUri = MutableStateFlow<String?>(null)
    val connectionUri: StateFlow<String?> = _connectionUri.asStateFlow()

    private val _userPubkey = MutableStateFlow<String?>(null)
    val userPubkey: StateFlow<String?> = _userPubkey.asStateFlow()

    init {
        // Try to restore session on init
        restoreSession()
    }

    /**
     * Start the login flow by generating a connection URI.
     */
    fun startLogin(relay: String = DEFAULT_RELAY): String {
        Log.d(TAG, "Starting NIP-46 login flow")

        // Generate ephemeral keypair using Quartz's KeyPair
        clientKeyPair = KeyPair()  // Generates new random keypair
        clientSigner = NostrSignerInternal(clientKeyPair!!)

        // Extract the keys for our use
        clientPrivateKey = clientKeyPair!!.privKey
        clientPublicKey = clientKeyPair!!.pubKey.toHex()

        secret = UUID.randomUUID().toString()
        relayUrl = relay

        // Build nostrconnect:// URI per NIP-46
        val uri = buildString {
            append("nostrconnect://")
            append(clientPublicKey)
            append("?relay=")
            append(java.net.URLEncoder.encode(relayUrl, "UTF-8"))
            append("&secret=")
            append(secret)
            append("&name=")
            append(java.net.URLEncoder.encode("nostrTV", "UTF-8"))
        }

        _connectionUri.value = uri
        _authState.value = AuthState.WaitingForScan(uri)

        Log.d(TAG, "Generated connection URI")
        Log.d(TAG, "Client pubkey: ${clientPublicKey?.take(16)}...")
        Log.d(TAG, "Relay: $relayUrl")

        // Connect to relay and start listening
        connectToRelay()

        return uri
    }

    /**
     * Connect to relay via WebSocket.
     */
    private fun connectToRelay() {
        val request = Request.Builder()
            .url(relayUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to relay: $relayUrl")
                subscribeToNip46Events()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                _authState.value = AuthState.Error("Connection failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
            }
        })
    }

    /**
     * Subscribe to NIP-46 events addressed to our client pubkey.
     */
    private fun subscribeToNip46Events() {
        val pubkey = clientPublicKey ?: return
        val since = System.currentTimeMillis() / 1000 - 60

        val subMessage = """["REQ","nip46",{"kinds":[$KIND_NIP46],"#p":["$pubkey"],"since":$since}]"""
        webSocket?.send(subMessage)
        Log.d(TAG, "Subscribed to NIP-46 events")
    }

    /**
     * Handle incoming relay messages.
     */
    private fun handleRelayMessage(text: String) {
        try {
            Log.d(TAG, "Relay message: ${text.take(200)}")
            val parsed = json.parseToJsonElement(text)
            if (parsed !is JsonArray) return

            val type = parsed[0].toString().trim('"')

            when (type) {
                "EVENT" -> {
                    Log.d(TAG, "Received EVENT message")
                    if (parsed.size >= 3) {
                        handleNip46Event(parsed[2] as JsonObject)
                    }
                }
                "EOSE" -> Log.d(TAG, "End of stored events")
                "OK" -> {
                    val eventId = if (parsed.size >= 2) parsed[1].toString().trim('"').take(16) else "?"
                    val success = if (parsed.size >= 3) parsed[2].toString() == "true" else false
                    val message = if (parsed.size >= 4) parsed[3].toString().trim('"') else ""
                    Log.d(TAG, "Event published: id=$eventId... success=$success $message")

                    if (!success && message.isNotEmpty()) {
                        Log.e(TAG, "Relay rejected event: $message")
                        // If the relay rejects our event, show error to user
                        if (message.contains("time") || message.contains("timestamp")) {
                            _authState.value = AuthState.Error("Relay rejected: Check device clock is correct")
                        }
                    }
                }
                "NOTICE" -> Log.w(TAG, "Relay notice: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing relay message", e)
        }
    }

    /**
     * Handle NIP-46 event.
     */
    private fun handleNip46Event(event: JsonObject) {
        scope.launch {
            try {
                val senderPubkey = event["pubkey"]?.toString()?.trim('"') ?: return@launch
                val rawContent = event["content"]?.toString() ?: return@launch
                // Remove surrounding quotes and unescape JSON string
                val content = rawContent.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")

                Log.d(TAG, "Received NIP-46 event from: ${senderPubkey.take(16)}...")
                Log.d(TAG, "Raw content length: ${rawContent.length}, unescaped: ${content.length}")
                Log.d(TAG, "Content prefix: ${content.take(50)}")

                // Decrypt using Quartz's NostrSignerInternal
                val decrypted = try {
                    decryptNip44(content, senderPubkey)
                } catch (e: Exception) {
                    Log.e(TAG, "Decryption failed: ${e.message}")
                    Log.e(TAG, "Sender pubkey: $senderPubkey")
                    Log.e(TAG, "Client pubkey: $clientPublicKey")
                    Log.e(TAG, "Content (first 100): ${content.take(100)}")
                    throw e
                }

                Log.d(TAG, "Decrypted message: ${decrypted.take(100)}...")

                // Parse as BunkerResponse
                val response = json.decodeFromString<BunkerResponseJson>(decrypted)

                // Check if this is a response to a pending request
                val requestId = response.id
                Log.d(TAG, "Response id: $requestId, pending requests: ${pendingRequests.keys}")
                if (requestId != null && pendingRequests.containsKey(requestId)) {
                    Log.d(TAG, "Found matching pending request for id: $requestId")
                    val callback = pendingRequests.remove(requestId)
                    callback?.invoke(response)
                    return@launch
                } else {
                    Log.d(TAG, "No matching pending request for id: $requestId")
                }

                // Check if this is a connect acknowledgment
                val result = response.result
                if (result == "ack" || result == secret) {
                    Log.d(TAG, "Received connect acknowledgment")
                    bunkerPubkey = senderPubkey
                    _authState.value = AuthState.Connecting

                    // Now call get_public_key to get the actual user pubkey
                    try {
                        val userPubkey = callGetPublicKey()
                        if (userPubkey != null) {
                            completeLogin(userPubkey)
                        } else {
                            _authState.value = AuthState.Error("Failed to get user public key")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting public key", e)
                        _authState.value = AuthState.Error("Failed to get public key: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling NIP-46 event", e)
            }
        }
    }

    /**
     * Call get_public_key to retrieve the user's actual pubkey.
     */
    private suspend fun callGetPublicKey(): String? {
        val requestId = UUID.randomUUID().toString()
        Log.d(TAG, "Calling get_public_key with request id: $requestId")

        return withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    pendingRequests.remove(requestId)
                }

                pendingRequests[requestId] = { response ->
                    if (response.error != null) {
                        continuation.resumeWithException(Exception(response.error))
                    } else {
                        continuation.resume(response.result)
                    }
                }

                // Send the request (we're already in a coroutine scope)
                scope.launch {
                    try {
                        sendNip46Request(requestId, "get_public_key", emptyList())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send get_public_key request", e)
                        pendingRequests.remove(requestId)
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }

    /**
     * Send a NIP-46 request to the bunker.
     */
    private suspend fun sendNip46Request(requestId: String, method: String, params: List<String>) {
        val bunkerPubkey = this.bunkerPubkey ?: return
        val privKey = this.clientPrivateKey ?: return
        val pubkey = this.clientPublicKey ?: return

        val paramsJson = params.joinToString(",") { "\"$it\"" }
        val request = """{"id":"$requestId","method":"$method","params":[$paramsJson]}"""

        Log.d(TAG, "Sending NIP-46 request: $method")

        // Encrypt with NIP-44 using Quartz's NostrSignerInternal
        val encrypted = encryptNip44(request, bunkerPubkey)

        // Create event
        val createdAt = System.currentTimeMillis() / 1000
        Log.d(TAG, "Event created_at timestamp: $createdAt (${java.util.Date(createdAt * 1000)})")
        val tags = listOf(listOf("p", bunkerPubkey))

        // Compute event ID
        val eventId = computeEventId(pubkey, createdAt, KIND_NIP46, tags, encrypted)

        // Sign the event
        val signature = signSchnorr(eventId.hexToByteArray(), privKey)

        // Build the event JSON
        val eventJson = """["EVENT",{"id":"$eventId","pubkey":"$pubkey","created_at":$createdAt,"kind":$KIND_NIP46,"tags":[["p","$bunkerPubkey"]],"content":"${escapeJson(encrypted)}","sig":"$signature"}]"""

        Log.d(TAG, "Sending event to relay, id: ${eventId.take(16)}...")
        Log.d(TAG, "Event JSON length: ${eventJson.length}")
        val sent = webSocket?.send(eventJson)
        Log.d(TAG, "Sent NIP-46 request: $method, websocket.send returned: $sent")
    }

    /**
     * Encrypt using Quartz's NostrSignerInternal.
     */
    private suspend fun encryptNip44(plaintext: String, recipientPubkeyHex: String): String {
        val signer = clientSigner ?: throw IllegalStateException("Client signer not initialized")
        Log.d(TAG, "Encrypting with signer, recipient: ${recipientPubkeyHex.take(16)}...")
        return signer.nip44Encrypt(plaintext, recipientPubkeyHex)
    }

    /**
     * Decrypt using Quartz's NostrSignerInternal.
     */
    private suspend fun decryptNip44(ciphertext: String, senderPubkeyHex: String): String {
        val signer = clientSigner ?: throw IllegalStateException("Client signer not initialized")
        Log.d(TAG, "Decrypting with signer, sender: ${senderPubkeyHex.take(16)}...")
        Log.d(TAG, "Ciphertext length: ${ciphertext.length}, prefix: ${ciphertext.take(20)}")
        return signer.nip44Decrypt(ciphertext, senderPubkeyHex)
    }

    /**
     * Complete the login process.
     */
    private fun completeLogin(userPubkey: String) {
        Log.d(TAG, "Login complete! User pubkey: ${userPubkey.take(16)}...")

        _userPubkey.value = userPubkey
        _authState.value = AuthState.Authenticated(userPubkey)

        // Save session
        val privKey = clientPrivateKey ?: return
        sessionStore.saveSession(
            userPubkey = userPubkey,
            bunkerPubkey = bunkerPubkey!!,
            clientPrivateKey = privKey.toHex(),
            relay = relayUrl,
            secret = secret!!
        )
    }

    /**
     * Restore session from storage.
     */
    fun restoreSession(): Boolean {
        val session = sessionStore.getSavedSession() ?: return false

        Log.d(TAG, "Restoring session for user: ${session.userPubkey.take(16)}...")

        // Restore state - recreate KeyPair and Signer
        clientPrivateKey = session.clientPrivateKey.hexToByteArray()
        val pubKeyBytes = secp256k1.pubkeyCreate(clientPrivateKey!!)
        clientPublicKey = pubKeyBytes.toHex()

        // Create KeyPair and Signer for encryption/decryption
        clientKeyPair = KeyPair(clientPrivateKey!!, pubKeyBytes, false)
        clientSigner = NostrSignerInternal(clientKeyPair!!)

        bunkerPubkey = session.bunkerPubkey
        relayUrl = session.relay
        secret = session.secret

        _userPubkey.value = session.userPubkey
        _authState.value = AuthState.Authenticated(session.userPubkey)

        // Reconnect to relay in background
        scope.launch {
            connectToRelay()
        }

        return true
    }

    /**
     * Logout and clear session.
     */
    fun logout() {
        Log.d(TAG, "Logging out")

        webSocket?.close(1000, "Logout")
        webSocket = null

        clientKeyPair = null
        clientSigner = null
        clientPrivateKey = null
        clientPublicKey = null
        bunkerPubkey = null
        secret = null

        _userPubkey.value = null
        _connectionUri.value = null
        _authState.value = AuthState.NotAuthenticated

        sessionStore.clearSession()
    }

    /**
     * Cancel pending login.
     */
    fun cancelLogin() {
        Log.d(TAG, "Canceling login")
        webSocket?.close(1000, "Canceled")
        webSocket = null
        _connectionUri.value = null
        _authState.value = AuthState.NotAuthenticated
    }

    // ==================== Crypto helpers ====================

    private fun computeEventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val tagsJson = tags.joinToString(",") { tag ->
            "[" + tag.joinToString(",") { "\"$it\"" } + "]"
        }
        val serialized = "[0,\"$pubkey\",$createdAt,$kind,[$tagsJson],\"${escapeJson(content)}\"]"
        return sha256(serialized.toByteArray()).toHex()
    }

    private fun signSchnorr(message: ByteArray, privateKey: ByteArray): String {
        val signature = secp256k1.signSchnorr(message, privateKey, null)
        return signature.toHex()
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * JSON structure for bunker responses.
 */
@kotlinx.serialization.Serializable
data class BunkerResponseJson(
    val id: String? = null,
    val result: String? = null,
    val error: String? = null
)

/**
 * Authentication state.
 */
sealed class AuthState {
    object NotAuthenticated : AuthState()
    data class WaitingForScan(val connectionUri: String) : AuthState()
    object Connecting : AuthState()
    data class Authenticated(val pubkey: String) : AuthState()
    data class Error(val message: String) : AuthState()
}
