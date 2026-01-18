package com.nostrtv.android.data.nostr

/**
 * Singleton provider for NostrClient.
 * Ensures all ViewModels share the same NostrClient instance,
 * avoiding duplicate connections and ensuring profile data is shared.
 */
object NostrClientProvider {
    val instance: NostrClient by lazy { NostrClient() }
}
