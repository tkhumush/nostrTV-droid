package com.nostrtv.android.data.auth

import android.util.Log
import com.nostrtv.android.data.nostr.NostrEvent
import com.nostrtv.android.data.nostr.NostrProtocol
import com.nostrtv.android.data.nostr.RelayConnectionFactory
import com.nostrtv.android.data.nostr.RelayMessage
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * NIP-46 Nostr Connect (Bunker) authentication manager.
 * Handles remote signing via bunker protocol.
 *
 * Reference: https://github.com/nostr-protocol/nips/blob/master/46.md
 */
class BunkerAuthManager(
    private val sessionStore: SessionStore
) {
    companion object {
        private const val TAG = "BunkerAuthManager"
        private const val BUNKER_RELAY = "wss://relay.nsec.app"
        private const val CONNECTION_TIMEOUT_MS = 60000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _connectionString = MutableStateFlow<String?>(null)
    val connectionString: StateFlow<String?> = _connectionString.asStateFlow()

    private var pendingSignatures = mutableMapOf<String, (String?) -> Unit>()
    private var bunkerPubkey: String? = null

    /**
     * Generate a connection string for NIP-46 bunker login.
     * User scans this as a QR code with their bunker app.
     */
    fun generateConnectionString(): String {
        val secret = generateSecret()
        val localPubkey = generateTempPubkey() // In production, this should be a proper keypair

        // nostrconnect://bunker-pubkey?relay=wss://relay.nsec.app&secret=xxx
        // For initial connection, we generate a connection request
        val connectionUri = "nostr+connect://$localPubkey?relay=$BUNKER_RELAY&secret=$secret"

        _connectionString.value = connectionUri
        _authState.value = AuthState.WaitingForConnection(connectionUri)

        Log.d(TAG, "Generated connection string: $connectionUri")
        return connectionUri
    }

    /**
     * Connect using an existing bunker URI (bunker://pubkey?relay=xxx&secret=xxx)
     */
    suspend fun connectWithBunkerUri(bunkerUri: String): Boolean {
        Log.d(TAG, "Connecting with bunker URI: ${bunkerUri.take(50)}...")
        _authState.value = AuthState.Connecting

        return try {
            // Parse bunker URI
            val parsed = parseBunkerUri(bunkerUri)
            if (parsed == null) {
                _authState.value = AuthState.Error("Invalid bunker URI")
                return false
            }

            bunkerPubkey = parsed.pubkey
            val relay = parsed.relay
            val secret = parsed.secret

            // Connect to bunker relay
            val connection = RelayConnectionFactory.create(relay)
            connection.connect()

            // Wait for connection and send connect request
            withTimeout(CONNECTION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    scope.launch {
                        connection.messages.collect { message ->
                            when (message) {
                                is RelayMessage.Connected -> {
                                    // Send connect request to bunker
                                    Log.d(TAG, "Connected to bunker relay, requesting auth")
                                    // In a full implementation, we'd exchange NIP-46 messages here
                                    sessionStore.saveSession(parsed.pubkey, bunkerUri, secret)
                                    _authState.value = AuthState.Authenticated(parsed.pubkey)
                                    if (continuation.isActive) {
                                        continuation.resume(true)
                                    }
                                }
                                is RelayMessage.Error -> {
                                    Log.e(TAG, "Bunker relay error: ${message.error}")
                                    _authState.value = AuthState.Error(message.error)
                                    if (continuation.isActive) {
                                        continuation.resume(false)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to bunker", e)
            _authState.value = AuthState.Error(e.message ?: "Connection failed")
            false
        }
    }

    /**
     * Request signature for an event from the bunker.
     */
    suspend fun signEvent(event: UnsignedEvent): NostrEvent? {
        if (_authState.value !is AuthState.Authenticated) {
            Log.w(TAG, "Cannot sign event: not authenticated")
            return null
        }

        // In a full implementation, this would:
        // 1. Send a NIP-46 "sign_event" request to the bunker
        // 2. Wait for the signed event response
        // 3. Return the signed event

        Log.d(TAG, "Sign event requested (placeholder - bunker signing not fully implemented)")

        // For now, return null since we don't have full bunker integration
        // This would need a proper NIP-46 implementation with the bunker protocol
        return null
    }

    fun logout() {
        Log.d(TAG, "Logging out")
        sessionStore.clearSession()
        _authState.value = AuthState.NotAuthenticated
        _connectionString.value = null
        bunkerPubkey = null
    }

    fun restoreSession(): Boolean {
        if (sessionStore.hasValidSession()) {
            val pubkey = sessionStore.userPubkey.value
            if (pubkey != null) {
                Log.d(TAG, "Restoring session for pubkey: ${pubkey.take(8)}...")
                _authState.value = AuthState.Authenticated(pubkey)
                return true
            }
        }
        return false
    }

    private fun parseBunkerUri(uri: String): BunkerParams? {
        return try {
            // bunker://pubkey?relay=wss://...&secret=xxx
            val withoutScheme = uri.removePrefix("bunker://").removePrefix("nostr+connect://")
            val parts = withoutScheme.split("?")
            val pubkey = parts[0]

            val params = parts.getOrNull(1)?.split("&")?.associate {
                val (key, value) = it.split("=", limit = 2)
                key to java.net.URLDecoder.decode(value, "UTF-8")
            } ?: emptyMap()

            BunkerParams(
                pubkey = pubkey,
                relay = params["relay"] ?: BUNKER_RELAY,
                secret = params["secret"] ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bunker URI", e)
            null
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateTempPubkey(): String {
        // In production, this should generate a real keypair
        // For demo purposes, we generate a random hex string
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class BunkerParams(
    val pubkey: String,
    val relay: String,
    val secret: String
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
