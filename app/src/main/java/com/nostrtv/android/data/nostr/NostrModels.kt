package com.nostrtv.android.data.nostr

data class LiveStream(
    val id: String,
    val pubkey: String,
    val title: String,
    val summary: String = "",
    val streamingUrl: String = "",
    val thumbnailUrl: String = "",
    val status: String = "live",
    val streamerName: String? = null,
    val streamerPubkey: String? = null,
    val viewerCount: Int = 0,
    val tags: List<String> = emptyList(),
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    val relays: List<String> = emptyList(),
    val dTag: String = "",
    val createdAt: Long = 0
) {
    val aTag: String
        get() = "30311:$pubkey:$dTag"
}

data class ChatMessage(
    val id: String,
    val pubkey: String,
    val content: String,
    val createdAt: Long,
    val authorName: String? = null,
    val authorPicture: String? = null
)

data class Profile(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val picture: String? = null,
    val about: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
    val lud06: String? = null
) {
    val displayNameOrName: String
        get() = displayName ?: name ?: pubkey.take(8) + "..."
}

data class ZapReceipt(
    val id: String,
    val bolt11: String,
    val preimage: String?,
    val description: String,
    val senderPubkey: String?,
    val recipientPubkey: String,
    val amountMilliSats: Long,
    val createdAt: Long,
    val senderName: String? = null
)
