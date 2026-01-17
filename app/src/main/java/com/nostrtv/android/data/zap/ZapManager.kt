package com.nostrtv.android.data.zap

import android.util.Log
import com.nostrtv.android.data.nostr.NostrProtocol
import com.nostrtv.android.data.nostr.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * ZapManager handles Lightning zaps as per NIP-57.
 * Reference: https://github.com/nostr-protocol/nips/blob/master/57.md
 */
class ZapManager {
    companion object {
        private const val TAG = "ZapManager"

        val PRESET_AMOUNTS = listOf(
            ZapAmount(21, "21 sats"),
            ZapAmount(100, "100 sats"),
            ZapAmount(500, "500 sats"),
            ZapAmount(1000, "1K sats"),
            ZapAmount(5000, "5K sats"),
            ZapAmount(10000, "10K sats")
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get Lightning invoice for a zap.
     * 1. Fetch LNURL from recipient's profile (lud16 or lud06)
     * 2. Create zap request event
     * 3. Request invoice from LNURL endpoint
     */
    suspend fun requestZapInvoice(
        recipientProfile: Profile,
        amountSats: Long,
        comment: String = "",
        eventId: String? = null,
        aTag: String? = null
    ): ZapInvoiceResult {
        return withContext(Dispatchers.IO) {
            try {
                // Get LNURL endpoint
                val lnurlEndpoint = getLnurlEndpoint(recipientProfile)
                    ?: return@withContext ZapInvoiceResult.Error("No Lightning address found")

                Log.d(TAG, "LNURL endpoint: $lnurlEndpoint")

                // Fetch LNURL pay info
                val payInfo = fetchLnurlPayInfo(lnurlEndpoint)
                    ?: return@withContext ZapInvoiceResult.Error("Failed to fetch LNURL info")

                // Validate amount
                val amountMilliSats = amountSats * 1000
                if (amountMilliSats < payInfo.minSendable || amountMilliSats > payInfo.maxSendable) {
                    return@withContext ZapInvoiceResult.Error(
                        "Amount out of range: ${payInfo.minSendable / 1000}-${payInfo.maxSendable / 1000} sats"
                    )
                }

                // Build callback URL with zap request
                val callbackUrl = buildCallbackUrl(
                    payInfo.callback,
                    amountMilliSats,
                    comment,
                    eventId,
                    aTag,
                    recipientProfile.pubkey
                )

                Log.d(TAG, "Requesting invoice from: $callbackUrl")

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
        eventId: String?,
        aTag: String?,
        recipientPubkey: String
    ): String {
        val url = StringBuilder(callback)

        // Add query separator
        url.append(if (callback.contains("?")) "&" else "?")

        // Add amount
        url.append("amount=$amountMilliSats")

        // Add comment if present
        if (comment.isNotEmpty()) {
            url.append("&comment=${java.net.URLEncoder.encode(comment, "UTF-8")}")
        }

        // Note: In a full implementation, we would create and sign a zap request event (kind 9734)
        // and include it in the nostr= parameter. This requires the bunker to sign the event.

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
    val label: String
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
