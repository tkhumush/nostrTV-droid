package com.nostrtv.android.data.nostr

import android.util.Log
import com.nostrtv.android.data.auth.RemoteSignerManager
import kotlinx.serialization.json.Json

/**
 * Manages NIP-53 presence events (kind 10312) for live streams.
 *
 * Presence events announce that a user is watching a stream.
 * - Join: Publish kind 10312 with `a` tag pointing to stream
 * - Leave: Publish kind 10312 with empty tags to clear presence
 */
class PresenceManager(
    private val remoteSignerManager: RemoteSignerManager,
    private val nostrClient: NostrClient
) {
    companion object {
        private const val TAG = "PresenceManager"
        private const val KIND_PRESENCE = 10312
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var currentStreamATag: String? = null

    /**
     * Announce joining a stream.
     * Publishes a kind 10312 event with the stream's `a` tag.
     */
    suspend fun announceJoin(streamATag: String): Boolean {
        Log.w("presence", "PresenceManager.announceJoin called with aTag: $streamATag")
        Log.w("presence", "Checking remoteSignerManager.isAuthenticated(): ${remoteSignerManager.isAuthenticated()}")

        if (!remoteSignerManager.isAuthenticated()) {
            Log.w("presence", "NOT AUTHENTICATED - skipping presence announcement")
            return false
        }

        val userPubkey = remoteSignerManager.getUserPubkey()
        Log.w("presence", "User pubkey: ${userPubkey?.take(16)}...")

        if (userPubkey == null) {
            Log.w("presence", "User pubkey is NULL - cannot announce")
            return false
        }

        currentStreamATag = streamATag

        // Build unsigned event per NIP-46 spec: {kind, content, tags, created_at}
        // Note: pubkey is NOT included - the signer will add its own
        val createdAt = System.currentTimeMillis() / 1000
        val unsignedEvent = buildUnsignedEventForSigning(
            createdAt = createdAt,
            kind = KIND_PRESENCE,
            tags = listOf(listOf("a", streamATag)),
            content = ""
        )

        Log.w("presence", "Stream aTag being used: $streamATag")
        Log.w("presence", "Built unsigned event (full): $unsignedEvent")

        // Sign via remote signer
        Log.w("presence", "Calling remoteSignerManager.signEvent()...")
        val signedEvent = remoteSignerManager.signEvent(unsignedEvent)

        if (signedEvent == null) {
            Log.w("presence", "FAILED to sign presence event - signEvent returned null")
            return false
        }

        Log.w("presence", "Signed presence event: ${signedEvent.take(200)}...")

        // Publish to relays
        Log.w("presence", "Publishing signed event to relays...")
        nostrClient.publishEvent(signedEvent)
        Log.w("presence", "Published presence join event successfully")

        return true
    }

    /**
     * Announce leaving a stream.
     * Publishes a kind 10312 event with empty tags to clear presence.
     */
    suspend fun announceLeave(): Boolean {
        if (!remoteSignerManager.isAuthenticated()) {
            Log.d(TAG, "Not authenticated, skipping presence clear")
            return false
        }

        Log.d(TAG, "Announcing presence leave (clearing presence)")

        // Build unsigned event with empty tags per NIP-46 spec
        val createdAt = System.currentTimeMillis() / 1000
        val unsignedEvent = buildUnsignedEventForSigning(
            createdAt = createdAt,
            kind = KIND_PRESENCE,
            tags = emptyList(),
            content = ""
        )

        Log.d(TAG, "Unsigned leave event: $unsignedEvent")

        // Sign via remote signer
        val signedEvent = remoteSignerManager.signEvent(unsignedEvent)
        if (signedEvent == null) {
            Log.e(TAG, "Failed to sign leave event")
            return false
        }

        Log.d(TAG, "Signed leave event: ${signedEvent.take(100)}...")

        // Publish to relays
        nostrClient.publishEvent(signedEvent)
        Log.d(TAG, "Published presence leave event")

        currentStreamATag = null
        return true
    }

    /**
     * Build an unsigned event JSON for NIP-46 signing.
     * Per NIP-46 spec, sign_event params should be: {kind, content, tags, created_at}
     * The signer will add pubkey, id, and sig.
     */
    private fun buildUnsignedEventForSigning(
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val tagsJson = tags.joinToString(",") { tag ->
            "[" + tag.joinToString(",") { "\"${escapeJson(it)}\"" } + "]"
        }

        return """{"kind":$kind,"content":"${escapeJson(content)}","tags":[$tagsJson],"created_at":$createdAt}"""
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
