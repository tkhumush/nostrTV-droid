package com.nostrtv.android.data.zap

import android.util.Log
import com.nostrtv.android.data.auth.RemoteSignerManager
import com.nostrtv.android.data.nostr.NostrClient
import com.nostrtv.android.data.nostr.NostrProtocol
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request

/**
 * ZapManager handles Lightning zaps as per NIP-57.
 * Reference: https://github.com/nostr-protocol/nips/blob/master/57.md
 */
class ZapManager(
    private val remoteSignerManager: RemoteSignerManager,
    private val nostrClient: NostrClient
) {
    companion object {
        private const val TAG = "ZapManager"

        val PRESET_AMOUNTS = listOf(
            ZapAmount(21, "21", "\u2764\uFE0F"),      // ❤️ Heart
            ZapAmount(100, "100", "\uD83C\uDFC6"),    // 🏆 Trophy
            ZapAmount(420, "420", "\uD83C\uDF40"),    // 🍀 Clover
            ZapAmount(1000, "1K", "\u2615"),          // ☕ Coffee
            ZapAmount(1500, "1.5K", "\uD83E\uDDC1"), // 🧁 Muffin
            ZapAmount(2100, "2.1K", "\u221E")         // ∞ Infinity
        )

        // Default relays for zap receipts
        private val ZAP_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.nostr.band",
            "wss://relay.primal.net"
        )
    }

    private val client = com.nostrtv.android.data.network.NetworkModule.httpClient

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get Lightning invoice for a zap with full NIP-57 compliance.
     * 1. Fetch LNURL from recipient's profile (lud16 or lud06)
     * 2. Create and sign zap request event (kind 9734)
     * 3. Request invoice from LNURL endpoint with signed nostr event
     */
    suspend fun requestZapInvoice(
        recipientProfile: Profile,
        amountSats: Long,
        comment: String = "",
        aTag: String? = null
    ): ZapInvoiceResult {
        return withContext(Dispatchers.IO) {
            try {
                // Verify user is authenticated
                if (!remoteSignerManager.isAuthenticated()) {
                    return@withContext ZapInvoiceResult.Error("Not authenticated")
                }

                // Get LNURL endpoint
                val lnurlEndpoint = getLnurlEndpoint(recipientProfile)
                    ?: return@withContext ZapInvoiceResult.Error("No Lightning address found")

                Log.d(TAG, "LNURL endpoint: $lnurlEndpoint")

                // Fetch LNURL pay info
                val payInfo = fetchLnurlPayInfo(lnurlEndpoint)
                    ?: return@withContext ZapInvoiceResult.Error("Failed to fetch LNURL info")

                Log.d(TAG, "LNURL pay info: allowsNostr=${payInfo.allowsNostr}, nostrPubkey=${payInfo.nostrPubkey?.take(16)}")

                // Validate amount
                val amountMilliSats = amountSats * 1000
                if (amountMilliSats < payInfo.minSendable || amountMilliSats > payInfo.maxSendable) {
                    return@withContext ZapInvoiceResult.Error(
                        "Amount out of range: ${payInfo.minSendable / 1000}-${payInfo.maxSendable / 1000} sats"
                    )
                }

                // Build and sign the zap request event (kind 9734)
                val signedZapRequest = if (payInfo.allowsNostr && payInfo.nostrPubkey != null) {
                    buildAndSignZapRequest(
                        recipientPubkey = recipientProfile.pubkey,
                        amountMilliSats = amountMilliSats,
                        lnurlEndpoint = lnurlEndpoint,
                        comment = comment,
                        aTag = aTag
                    )
                } else {
                    Log.d(TAG, "LNURL doesn't support Nostr zaps, proceeding without zap request")
                    null
                }

                // Build callback URL
                val callbackUrl = buildCallbackUrl(
                    payInfo.callback,
                    amountMilliSats,
                    comment,
                    signedZapRequest,
                    lnurlEndpoint
                )

                Log.d(TAG, "Requesting invoice from: ${callbackUrl.take(200)}...")

                // Request invoice
                val invoice = fetchInvoice(callbackUrl)
                    ?: return@withContext ZapInvoiceResult.Error("Failed to get invoice")

                ZapInvoiceResult.Success(
                    invoice = invoice,
                    amountSats = amountSats,
                    recipientPubkey = recipientProfile.pubkey
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request zap invoice", e)
                ZapInvoiceResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Build and sign a kind 9734 zap request event per NIP-57.
     * The event is signed by the user via NIP-46 remote signer.
     */
    private suspend fun buildAndSignZapRequest(
        recipientPubkey: String,
        amountMilliSats: Long,
        lnurlEndpoint: String,
        comment: String,
        aTag: String?
    ): String? {
        val createdAt = System.currentTimeMillis() / 1000

        // Build tags per NIP-57:
        // - relays: where receipt should be published
        // - amount: in millisats
        // - lnurl: bech32 encoded (we use raw URL for simplicity)
        // - p: recipient pubkey
        // - a: (optional) stream event coordinate
        val tags = mutableListOf<List<String>>()

        // Relays tag (required)
        tags.add(listOf("relays") + ZAP_RELAYS)

        // Amount in millisats (required)
        tags.add(listOf("amount", amountMilliSats.toString()))

        // Recipient pubkey (required)
        tags.add(listOf("p", recipientPubkey))

        // LNURL (optional but recommended)
        tags.add(listOf("lnurl", lnurlEndpoint))

        // Stream a-tag if zapping a stream (optional)
        if (!aTag.isNullOrEmpty()) {
            tags.add(listOf("a", aTag))
        }

        // Build unsigned event JSON (kind 9734)
        val unsignedEvent = buildUnsignedEventForSigning(
            createdAt = createdAt,
            kind = 9734,
            tags = tags,
            content = comment
        )

        Log.d(TAG, "Unsigned zap request: $unsignedEvent")

        // Sign via NIP-46 remote signer
        val signedEvent = remoteSignerManager.signEvent(unsignedEvent)
        if (signedEvent == null) {
            Log.e(TAG, "Failed to sign zap request event")
            return null
        }

        Log.d(TAG, "Signed zap request: ${signedEvent.take(200)}...")
        return signedEvent
    }

    /**
     * Build an unsigned event JSON for signing via NIP-46.
     * Per NIP-46, the unsigned event should only contain: kind, content, tags, created_at
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

    private fun getLnurlEndpoint(profile: Profile): String? {
        // Try lud16 first (lightning address like user@domain.com)
        profile.lud16?.let { lud16 ->
            if (lud16.contains("@")) {
                val parts = lud16.split("@")
                if (parts.size == 2) {
                    return "https://${parts[1]}/.well-known/lnurlp/${parts[0]}"
                }
            }
        }

        // Try lud06 (raw LNURL)
        profile.lud06?.let { lud06 ->
            // Decode LNURL (bech32)
            // For simplicity, we assume it's already a URL or skip decoding
            if (lud06.startsWith("http")) {
                return lud06
            }
        }

        return null
    }

    private fun fetchLnurlPayInfo(endpoint: String): LnurlPayInfo? {
        return try {
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            val jsonObj = json.parseToJsonElement(body).jsonObject

            LnurlPayInfo(
                callback = jsonObj["callback"]?.jsonPrimitive?.content ?: return null,
                minSendable = jsonObj["minSendable"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000,
                maxSendable = jsonObj["maxSendable"]?.jsonPrimitive?.content?.toLongOrNull() ?: 100000000000,
                allowsNostr = jsonObj["allowsNostr"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                nostrPubkey = jsonObj["nostrPubkey"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch LNURL pay info", e)
            null
        }
    }

    private fun buildCallbackUrl(
        callback: String,
        amountMilliSats: Long,
        comment: String,
        signedZapRequest: String?,
        lnurl: String
    ): String {
        val url = StringBuilder(callback)

        // Add query separator
        url.append(if (callback.contains("?")) "&" else "?")

        // Add amount (required)
        url.append("amount=$amountMilliSats")

        // Add comment if present
        if (comment.isNotEmpty()) {
            url.append("&comment=${java.net.URLEncoder.encode(comment, "UTF-8")}")
        }

        // Add signed zap request event as nostr parameter (NIP-57)
        if (signedZapRequest != null) {
            url.append("&nostr=${java.net.URLEncoder.encode(signedZapRequest, "UTF-8")}")
        }

        // Add lnurl parameter
        url.append("&lnurl=${java.net.URLEncoder.encode(lnurl, "UTF-8")}")

        return url.toString()
    }

    private fun fetchInvoice(callbackUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url(callbackUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            val jsonObj = json.parseToJsonElement(body).jsonObject
            jsonObj["pr"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch invoice", e)
            null
        }
    }
}

data class ZapAmount(
    val sats: Long,
    val label: String,
    val emoji: String
)

data class LnurlPayInfo(
    val callback: String,
    val minSendable: Long,
    val maxSendable: Long,
    val allowsNostr: Boolean,
    val nostrPubkey: String?
)

sealed class ZapInvoiceResult {
    data class Success(
        val invoice: String,
        val amountSats: Long,
        val recipientPubkey: String
    ) : ZapInvoiceResult()

    data class Error(val message: String) : ZapInvoiceResult()
}
