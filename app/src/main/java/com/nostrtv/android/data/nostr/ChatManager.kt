package com.nostrtv.android.data.nostr

import android.util.Log
import com.nostrtv.android.data.auth.RemoteSignerManager

/**
 * Manages sending chat messages (kind 1311) for live streams.
 *
 * Chat messages are tied to a stream via the `a` tag referencing the stream's
 * addressable event (kind 30311).
 */
class ChatManager(
    private val remoteSignerManager: RemoteSignerManager,
    private val nostrClient: NostrClient
) {
    companion object {
        private const val TAG = "ChatManager"
        private const val KIND_LIVE_CHAT = 1311
    }

    /**
     * Send a chat message to a stream.
     * Returns true if the message was signed and published successfully.
     */
    suspend fun sendMessage(streamATag: String, content: String): Boolean {
        Log.d(TAG, "sendMessage called for stream: ${streamATag.take(50)}...")
        Log.d(TAG, "Message content: ${content.take(100)}")

        if (!remoteSignerManager.isAuthenticated()) {
            Log.w(TAG, "Not authenticated, cannot send chat message")
            return false
        }

        if (content.isBlank()) {
            Log.w(TAG, "Empty message content, skipping")
            return false
        }

        // Build unsigned event per NIP-46 spec: {kind, content, tags, created_at}
        val createdAt = System.currentTimeMillis() / 1000
        val unsignedEvent = buildUnsignedEventForSigning(
            createdAt = createdAt,
            kind = KIND_LIVE_CHAT,
            tags = listOf(listOf("a", streamATag)),
            content = content
        )

        Log.d(TAG, "Built unsigned chat event: ${unsignedEvent.take(200)}...")

        // Sign via remote signer
        val signedEvent = remoteSignerManager.signEvent(unsignedEvent)

        if (signedEvent == null) {
            Log.e(TAG, "Failed to sign chat message")
            return false
        }

        Log.d(TAG, "Signed chat event: ${signedEvent.take(200)}...")

        // Publish to relays
        nostrClient.publishEvent(signedEvent)
        Log.d(TAG, "Published chat message successfully")

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
