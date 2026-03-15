# Livestream Filtering Rewrite — Matching zap.stream's Approach

## Goal

Rewrite nostrTV's livestream filtering pipeline to match the battle-tested approach used by **zap.stream** ([github.com/v0l/zap.stream](https://github.com/v0l/zap.stream)). The new filtering must apply uniformly to **both** the Curated (admin) and Following (user) tabs — treating the admin follow list and user follow list as two equivalent feed sources that both benefit from the same clean filtering pipeline.

## Context: What zap.stream Does

zap.stream's filtering lives in two hooks:
- `src/hooks/live-streams.ts` — relay subscription
- `src/hooks/useLiveStreams.ts` — client-side filtering pipeline (`useSortedStreams`)

Their pipeline, in order:

```
Raw relay events (kind 30311, open subscription)
  → Age filter: discard events older than 7 days (we'll use 24 hours)
  → Playability filter: must have valid .m3u8 or moq: streaming URL
  → Host whitelist filter (we skip this — we use admin curation instead)
  → Status bucketing:
      - "live" → live list (sorted by `starts` tag)
      - "planned" → planned list (sorted by `starts` tag)
      - "ended" + has recording URL → ended list (sorted by created_at)
      - "ended" without recording → DROPPED
  → Muted hosts: filter out streams from user's mute list
  → Deletion events: subscribe to kind 5 events referencing live streams
```

## Current nostrTV Code to Modify

### Files to change:

1. **`app/src/main/java/com/nostrtv/android/data/nostr/NostrClient.kt`**
   - `subscribeToLiveStreams()` (line 416) — relay subscription filter
   - `handleLiveStreamEvent()` (line 172) — event processing/dedup
   - `parseLiveStreamEvent()` (line 200) — stream data extraction

2. **`app/src/main/java/com/nostrtv/android/viewmodel/HomeViewModel.kt`**
   - `connectAndSubscribe()` (line 103) — stream collection and filtering
   - `updateFilteredStreams()` (line 81) — curated/following split

3. **`app/src/main/java/com/nostrtv/android/data/nostr/NostrModels.kt`**
   - `LiveStream` data class — may need new fields

4. **`app/src/main/java/com/nostrtv/android/data/nostr/NostrProtocol.kt`**
   - Kind constants (already has KIND_DELETE = 5)

## Implementation Plan

### Step 1: Add StreamState enum and update LiveStream model

In `NostrModels.kt`:

```kotlin
enum class StreamState(val value: String) {
    Live("live"),
    Ended("ended"),
    Planned("planned");

    companion object {
        fun fromString(s: String?): StreamState? = entries.find { it.value == s }
    }
}
```

Update `LiveStream` to add a `recording` field (needed to decide if ended streams should display):

```kotlin
data class LiveStream(
    // ... existing fields ...
    val recording: String = "",    // ADD: recording URL from "recording" tag
    val startsAt: Long? = null,    // ALREADY EXISTS but not populated — fix in parser
)
```

### Step 2: Fix `parseLiveStreamEvent()` in NostrClient.kt

Update the parser to extract the `recording` and `starts` tags:

```kotlin
val recording = event.getTagValue("recording") ?: ""
val startsAt = event.getTagValue("starts")?.toLongOrNull()
```

Pass these into the `LiveStream` constructor.

### Step 3: Add `canPlayStream()` validation in NostrClient.kt

Add a utility function matching zap.stream's `canPlayUrl` + `canPlayEvent`:

```kotlin
companion object {
    // ... existing constants ...

    /** Maximum age for stream events (24 hours — more aggressive than zap.stream's 7 days) */
    private const val MAX_STREAM_AGE_SECONDS = 24 * 60 * 60L  // 24 hours

    /**
     * Check if a URL is a playable stream URL.
     * Matches zap.stream's canPlayUrl(): must be .m3u8 or moq: protocol.
     */
    fun canPlayUrl(url: String): Boolean {
        if (url.isBlank()) return false
        // Block localhost URLs in production
        if (url.contains("localhost") || url.contains("127.0.0.1")) return false
        return try {
            val uri = java.net.URI(url)
            uri.path?.contains(".m3u8") == true || uri.scheme == "moq"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if a stream event has a playable URL.
     * Matches zap.stream's canPlayEvent().
     */
    fun canPlayEvent(stream: LiveStream): Boolean {
        return canPlayUrl(stream.streamingUrl) || canPlayUrl(stream.recording)
    }

    /**
     * Check if a stream event is within the acceptable age window.
     * Matches zap.stream's 7-day cutoff.
     */
    fun isWithinAgeWindow(stream: LiveStream): Boolean {
        val now = System.currentTimeMillis() / 1000
        return stream.createdAt > (now - MAX_STREAM_AGE_SECONDS)
    }
}
```

### Step 4: Rewrite the filtering pipeline in HomeViewModel.kt

Replace the current filtering in `connectAndSubscribe()` (lines 140-167) and `updateFilteredStreams()` (lines 81-101).

The key change: **apply the full zap.stream filtering pipeline BEFORE splitting into curated/following.** Both tabs get the same clean base list.

```kotlin
// In connectAndSubscribe(), replace the .collect { streamList -> ... } block:

nostrClient.subscribeToLiveStreams()
    .collect { streamList ->
        Log.d(TAG, "Received ${streamList.size} total stream events from relays")

        // === ZAP.STREAM FILTERING PIPELINE ===

        // Step 1: Age filter — discard events older than 24 hours
        val now = System.currentTimeMillis() / 1000
        val ageFiltered = streamList.filter {
            NostrClient.isWithinAgeWindow(it)
        }
        Log.d(TAG, "After age filter (24h): ${ageFiltered.size} streams (dropped ${streamList.size - ageFiltered.size})")

        // Step 2: Playability filter — must have valid .m3u8 streaming URL
        val playable = ageFiltered.filter {
            NostrClient.canPlayEvent(it)
        }
        Log.d(TAG, "After playability filter: ${playable.size} streams (dropped ${ageFiltered.size - playable.size})")

        // Step 3: NIP-33 deduplication (pubkey + d-tag, keep newest)
        val deduplicated = playable
            .sortedByDescending { it.createdAt }
            .distinctBy { "${it.pubkey}:${it.dTag}" }

        // Step 4: Bucket by status
        val live = deduplicated
            .filter { it.status == "live" }
            .sortedByDescending { it.startsAt ?: it.createdAt }  // Sort by starts tag like zap.stream

        val ended = deduplicated
            .filter { it.status == "ended" && it.recording.isNotBlank() }
            .sortedByDescending { it.createdAt }

        val planned = deduplicated
            .filter { it.status == "planned" }
            .sortedByDescending { it.startsAt ?: it.createdAt }

        Log.d(TAG, "Bucketed: ${live.size} live, ${ended.size} ended (with recording), ${planned.size} planned")

        // Store the filtered live streams as the base for curated/following
        _allStreams.value = live
        updateFilteredStreams()

        // Fetch profiles for all live streamers
        val pubkeys = live.mapNotNull { it.streamerPubkey }.distinct()
        nostrClient.fetchProfiles(pubkeys)
    }
```

The `updateFilteredStreams()` function stays the same — it already correctly applies admin follow list and user follow list filters on top of `_allStreams`. Both curated and following now receive pre-cleaned streams.

### Step 5: Subscribe to deletion events (kind 5)

In `NostrClient.kt`, add a method to track deletion events for live streams. This handles the case where a streamer explicitly deletes their stream event.

```kotlin
private val _deletedStreamAddresses = MutableStateFlow<Set<String>>(emptySet())

/**
 * Subscribe to deletion events (kind 5) that reference live stream events.
 * Matches zap.stream's deletion event handling.
 */
fun subscribeToDeletions() {
    val filter = NostrFilter(
        kinds = listOf(NostrProtocol.KIND_DELETE),
        tags = mapOf("k" to listOf(NostrProtocol.KIND_LIVE_EVENT.toString())),
        limit = 100
    )
    val subscriptionMessage = NostrProtocol.createSubscription("stream_deletions", filter)
    broadcast(subscriptionMessage)
}
```

In `handleEvent()`, add handling for kind 5:

```kotlin
NostrProtocol.KIND_DELETE -> handleDeletionEvent(event)
```

```kotlin
private fun handleDeletionEvent(event: NostrEvent) {
    // Check for "a" tags referencing live streams (30311:pubkey:d-tag)
    val deletedAddresses = event.getTagValues("a")
        .filter { it.startsWith("30311:") && it.contains(event.pubkey) }
        .toSet()

    if (deletedAddresses.isNotEmpty()) {
        Log.d(TAG, "Stream deletion event from ${event.pubkey.take(16)}: $deletedAddresses")
        _deletedStreamAddresses.update { it + deletedAddresses }

        // Remove deleted streams from active streams
        _streams.update { currentList ->
            currentList.filter { stream -> stream.aTag !in deletedAddresses }
        }
    }
}
```

Also call `subscribeToDeletions()` in `HomeViewModel.connectAndSubscribe()` after subscribing to live streams.

### Step 6: Add deletion check to stream deduplication

In `handleLiveStreamEvent()`, before adding/updating a stream, check if it's been deleted:

```kotlin
private fun handleLiveStreamEvent(event: NostrEvent) {
    val stream = parseLiveStreamEvent(event) ?: return

    // Skip if this stream has been deleted
    if (stream.aTag in _deletedStreamAddresses.value) {
        Log.d(TAG, "Skipping deleted stream: ${stream.aTag}")
        return
    }

    _streams.update { currentList ->
        // ... existing NIP-33 dedup logic ...
    }
}
```

## Summary of Changes

| What | Where | Change |
|------|-------|--------|
| `StreamState` enum | `NostrModels.kt` | Add enum |
| `LiveStream.recording` field | `NostrModels.kt` | Add field |
| `LiveStream.startsAt` population | `NostrClient.kt` parseLiveStreamEvent | Parse `starts` tag |
| `canPlayUrl()` | `NostrClient.kt` companion | New function |
| `canPlayEvent()` | `NostrClient.kt` companion | New function |
| `isWithinAgeWindow()` | `NostrClient.kt` companion | New function |
| Age + playability + status filtering | `HomeViewModel.kt` connectAndSubscribe | Rewrite collect block |
| Sort by `starts` tag | `HomeViewModel.kt` | Change sort order |
| Deletion event subscription | `NostrClient.kt` | New subscription + handler |
| Deletion event handling | `NostrClient.kt` handleEvent | New kind 5 handler |
| Deletion check in stream processing | `NostrClient.kt` handleLiveStreamEvent | Skip deleted streams |

## What We're NOT Doing (and why)

- **No env-var whitelist** — We already have admin curation via follow lists, which is more flexible
- **No muted hosts** — Not yet implemented in nostrTV; can be added later when we have a mute list feature
- **No dead link HTTP HEAD probes** — Too aggressive for a TV app (network/battery cost). The `.m3u8` URL validation catches most bad streams
- **No N94 (kind 1053) support** — zap.stream supports this newer kind but it's not widely adopted yet

## Testing Checklist

After implementing:

1. Verify stale "ghost" streams (events older than 24h with status=live but streamer is offline) no longer appear
2. Verify streams without `.m3u8` URLs are filtered out
3. Verify both Curated and Following tabs show only clean, playable live streams
4. Verify ended streams without recordings don't appear anywhere
5. Verify stream ordering uses `starts` tag when available
6. Verify deleted streams (kind 5) are removed
7. Test with relay that has many old events (nostr.wine) to confirm age filter works
8. Verify no regressions in chat, zaps, or presence features
