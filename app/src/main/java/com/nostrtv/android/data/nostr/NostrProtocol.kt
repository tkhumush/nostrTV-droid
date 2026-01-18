package com.nostrtv.android.data.nostr

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Nostr protocol implementation for event parsing and message handling.
 * Reference: https://github.com/nostr-protocol/nips
 */
object NostrProtocol {
    private const val TAG = "NostrProtocol"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Event kinds
    const val KIND_METADATA = 0
    const val KIND_TEXT_NOTE = 1
    const val KIND_CONTACTS = 3
    const val KIND_ENCRYPTED_DM = 4
    const val KIND_DELETE = 5
    const val KIND_REACTION = 7
    const val KIND_LIVE_CHAT = 1311      // NIP-53 live chat message
    const val KIND_ZAP_REQUEST = 9734
    const val KIND_ZAP_RECEIPT = 9735
    const val KIND_PRESENCE = 10312       // NIP-53 presence
    const val KIND_LIVE_EVENT = 30311

    /**
     * Create a subscription request (REQ)
     */
    fun createSubscription(subscriptionId: String, filter: NostrFilter): String {
        val filterJson = buildString {
            append("{")
            val parts = mutableListOf<String>()

            if (filter.kinds.isNotEmpty()) {
                parts.add("\"kinds\":[${filter.kinds.joinToString(",")}]")
            }
            if (filter.authors.isNotEmpty()) {
                parts.add("\"authors\":[${filter.authors.joinToString(",") { "\"$it\"" }}]")
            }
            if (filter.ids.isNotEmpty()) {
                parts.add("\"ids\":[${filter.ids.joinToString(",") { "\"$it\"" }}]")
            }
            filter.since?.let { parts.add("\"since\":$it") }
            filter.until?.let { parts.add("\"until\":$it") }
            filter.limit?.let { parts.add("\"limit\":$it") }

            // Handle tags (#e, #p, #a, #t, etc.)
            filter.tags.forEach { (tag, values) ->
                parts.add("\"#$tag\":[${values.joinToString(",") { "\"$it\"" }}]")
            }

            append(parts.joinToString(","))
            append("}")
        }

        return "[\"REQ\",\"$subscriptionId\",$filterJson]"
    }

    /**
     * Create a close subscription request (CLOSE)
     */
    fun createCloseSubscription(subscriptionId: String): String {
        return "[\"CLOSE\",\"$subscriptionId\"]"
    }

    /**
     * Parse an incoming relay message
     */
    fun parseRelayMessage(message: String): NostrRelayMessage? {
        return try {
            val jsonArray = json.parseToJsonElement(message).jsonArray
            val type = jsonArray[0].jsonPrimitive.content

            when (type) {
                "EVENT" -> {
                    val subscriptionId = jsonArray[1].jsonPrimitive.content
                    val eventJson = jsonArray[2].jsonObject
                    val event = parseEvent(eventJson)
                    NostrRelayMessage.EventMessage(subscriptionId, event)
                }
                "EOSE" -> {
                    val subscriptionId = jsonArray[1].jsonPrimitive.content
                    NostrRelayMessage.EndOfStoredEvents(subscriptionId)
                }
                "NOTICE" -> {
                    val message = jsonArray[1].jsonPrimitive.content
                    NostrRelayMessage.Notice(message)
                }
                "OK" -> {
                    val eventId = jsonArray[1].jsonPrimitive.content
                    val accepted = jsonArray[2].jsonPrimitive.content.toBoolean()
                    val message = if (jsonArray.size > 3) jsonArray[3].jsonPrimitive.contentOrNull else null
                    NostrRelayMessage.Ok(eventId, accepted, message)
                }
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse relay message: ${e.message}", e)
            null
        }
    }

    private fun parseEvent(eventJson: kotlinx.serialization.json.JsonObject): NostrEvent {
        val id = eventJson["id"]?.jsonPrimitive?.content ?: ""
        val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: ""
        val createdAt = eventJson["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        val kind = eventJson["kind"]?.jsonPrimitive?.intOrNull ?: 0
        val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
        val sig = eventJson["sig"]?.jsonPrimitive?.content ?: ""

        val tags = eventJson["tags"]?.jsonArray?.map { tagArray ->
            tagArray.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList()

        return NostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = sig
        )
    }
}

data class NostrFilter(
    val kinds: List<Int> = emptyList(),
    val authors: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val tags: Map<String, List<String>> = emptyMap()
)

data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    fun getTagValue(tagName: String): String? {
        return tags.find { it.firstOrNull() == tagName }?.getOrNull(1)
    }

    fun getTagValues(tagName: String): List<String> {
        return tags.filter { it.firstOrNull() == tagName }.mapNotNull { it.getOrNull(1) }
    }

    fun getAllTags(tagName: String): List<List<String>> {
        return tags.filter { it.firstOrNull() == tagName }
    }
}

sealed class NostrRelayMessage {
    data class EventMessage(val subscriptionId: String, val event: NostrEvent) : NostrRelayMessage()
    data class EndOfStoredEvents(val subscriptionId: String) : NostrRelayMessage()
    data class Notice(val message: String) : NostrRelayMessage()
    data class Ok(val eventId: String, val accepted: Boolean, val message: String?) : NostrRelayMessage()
}
