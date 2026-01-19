# nostrTV Android TV Port

Porting **nostrTV** from SwiftUI (tvOS) to Kotlin for Android TV using Jetpack Compose for TV.

## Role & Expectations

You are an expert in nostr development with complete understanding of nostr NIPs. Reference the master repo when needed: https://github.com/nostr-protocol/nips/tree/master

Responsibilities:
- Correctly initialize Android TV project
- Map SwiftUI concepts to Jetpack Compose (TV-optimized)
- Re-implement Nostr protocol interactions
- Preserve user workflows and feature parity
- Work incrementally with clean Git hygiene
- Maintain a persistent checklist across sessions

## App Overview

**nostrTV** is a decentralized live-streaming TV app built on the Nostr protocol.

Core capabilities:
- Discover live streams announced via Nostr events
- Play live video streams fullscreen
- Display live chat tied to the stream
- Support Lightning zaps (micropayments) with QR invoices
- Authenticate users via Nostr Bunker / NIP-46 (remote signing)
- Track viewer presence (NIP-53)
- Show streamer profiles and zap activity overlays

## Known Issues

### Relays
Default relays in `NostrClient.kt`:
- wss://relay.damus.io
- wss://relay.nostr.band
- wss://relay.snort.social
- wss://nostr.wine
- wss://relay.primal.net
- wss://purplepag.es (dedicated kind 0 relay)

## Nostr Protocol Reference

| Feature | Nostr Kind / Spec |
|---------|-------------------|
| Live streams | 30311 |
| Live chat | 30311 (comments tied via `a` tag) |
| Presence (join/leave) | 10312 (NIP-53) |
| Zap request | 9734 (NIP-57) |
| Zap receipt | 9735 |
| Profiles | 0 |
| Follow lists | 3 |
| Remote signing | NIP-46 |

## Android Nostr SDK

**DO NOT re-implement Nostr primitives manually.**

Use: https://github.com/block/nostrino

Wrap in your own repository/service layer, but all relay connections, subscriptions, and event signing must flow through this library.

## NIP-46 Remote Signing Implementation

### Overview

We use NIP-46 (Nostr Connect) for remote signing via external signer apps (like Amber). The implementation uses the **Quartz library** from Amethyst for NIP-44 encryption.

**Key files:**
- `RemoteSignerManager.kt` - Handles NIP-46 communication with bunker/signer
- `SessionStore.kt` - Persists session data in EncryptedSharedPreferences
- `PresenceManager.kt` - Example of signing events (kind 10312)

### Quartz Library Usage

```kotlin
// Dependencies (in build.gradle)
implementation("com.github.ArcadeCity:Quartz:1.10.1")

// Key imports
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
```

#### Creating Client Keypair
```kotlin
// Generate new ephemeral keypair for NIP-46 session
val clientKeyPair = KeyPair()  // Random keypair
val clientSigner = NostrSignerInternal(clientKeyPair)
val clientPublicKey = clientKeyPair.pubKey.toHex()  // 64-char hex (32-byte x-coordinate)
val clientPrivateKey = clientKeyPair.privKey  // ByteArray

// Restore keypair from saved private key
val restoredKeyPair = KeyPair(savedPrivateKeyBytes)
val restoredSigner = NostrSignerInternal(restoredKeyPair)
```

#### NIP-44 Encryption/Decryption
```kotlin
// Encrypt message for recipient
val encrypted: String = clientSigner.nip44Encrypt(plaintext, recipientPubkeyHex)

// Decrypt message from sender
val decrypted: String = clientSigner.nip44Decrypt(ciphertext, senderPubkeyHex)
```

### NIP-46 Request Format

**CRITICAL: All params must be JSON strings (stringified), not raw JSON objects.**

Per the NIP-46 spec and [nostr-tools implementation](https://github.com/nbd-wtf/nostr-tools):

```typescript
// nostr-tools reference
let resp = await this.sendRequest('sign_event', [JSON.stringify(event)])
```

#### Correct Request Structure

```json
{
  "id": "unique-request-id",
  "method": "sign_event",
  "params": ["{\"kind\":10312,\"content\":\"\",\"tags\":[[\"a\",\"30311:pubkey:d-tag\"]],\"created_at\":1234567890}"]
}
```

**Note:** The event object inside `params` array is a **JSON string** (with escaped quotes), NOT a raw JSON object.

#### Incorrect (will fail silently)

```json
{
  "id": "unique-request-id",
  "method": "sign_event",
  "params": [{"kind":10312,"content":"","tags":[["a","..."]],"created_at":1234567890}]
}
```

### Building Unsigned Events for Signing

Per NIP-46, the unsigned event should contain only: `kind`, `content`, `tags`, `created_at`

**Do NOT include:** `pubkey`, `id`, `sig` (signer adds these)

```kotlin
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
```

### Sending NIP-46 Requests

```kotlin
private suspend fun sendNip46Request(requestId: String, method: String, params: List<String>) {
    // All params are JSON strings - escape and quote each one
    val paramsJson = params.joinToString(",") { "\"${escapeJson(it)}\"" }
    val request = """{"id":"$requestId","method":"$method","params":[$paramsJson]}"""

    // Encrypt with NIP-44
    val encrypted = clientSigner.nip44Encrypt(request, bunkerPubkey)

    // Create and send kind 24133 event to relay
    // ... (see RemoteSignerManager.kt for full implementation)
}
```

### Sign Event Flow

```kotlin
suspend fun signEvent(unsignedEventJson: String): String? {
    // 1. Wait for relay to be ready
    if (!isRelayReady) { /* wait */ }

    // 2. Create request with unique ID
    val requestId = UUID.randomUUID().toString()

    // 3. Register callback for response
    pendingRequests[requestId] = { response -> /* handle */ }

    // 4. Send NIP-46 request (event is passed as JSON string)
    sendNip46Request(requestId, "sign_event", listOf(unsignedEventJson))

    // 5. Wait for signer response (with timeout)
    // Response contains signed event JSON string
}
```

### NIP-46 Methods Reference

| Method | Params | Result | Use Case |
|--------|--------|--------|----------|
| `get_public_key` | `[]` | `"<pubkey hex>"` | Get user's pubkey after connect |
| `sign_event` | `["<unsigned event JSON>"]` | `"<signed event JSON>"` | Sign any Nostr event |
| `nip04_encrypt` | `["<pubkey>", "<plaintext>"]` | `"<ciphertext>"` | Encrypt DM (NIP-04) |
| `nip04_decrypt` | `["<pubkey>", "<ciphertext>"]` | `"<plaintext>"` | Decrypt DM (NIP-04) |
| `nip44_encrypt` | `["<pubkey>", "<plaintext>"]` | `"<ciphertext>"` | Encrypt (NIP-44) |
| `nip44_decrypt` | `["<pubkey>", "<ciphertext>"]` | `"<plaintext>"` | Decrypt (NIP-44) |

### Common Pitfalls

1. **Params must be strings**: Always JSON.stringify the event before passing to params
2. **Don't include pubkey in unsigned event**: Signer adds its own pubkey
3. **Wait for relay ready**: WebSocket must be connected before sending requests
4. **Handle reconnection**: On session restore, don't call `get_public_key` if already authenticated
5. **Pubkey format**: Use Quartz's `KeyPair` which correctly produces 64-char hex (32-byte x-coordinate)

### Example: Signing a Presence Event

```kotlin
// In PresenceManager.kt
suspend fun announceJoin(streamATag: String): Boolean {
    if (!remoteSignerManager.isAuthenticated()) return false

    val createdAt = System.currentTimeMillis() / 1000
    val unsignedEvent = buildUnsignedEventForSigning(
        createdAt = createdAt,
        kind = 10312,  // NIP-53 presence
        tags = listOf(listOf("a", streamATag)),
        content = ""
    )

    val signedEvent = remoteSignerManager.signEvent(unsignedEvent) ?: return false

    nostrClient.publishEvent(signedEvent)
    return true
}
```

### References

- [NIP-46 Specification](https://github.com/nostr-protocol/nips/blob/master/46.md)
- [nostr-tools NIP-46](https://github.com/nbd-wtf/nostr-tools) - Reference implementation
- [Quartz Library](https://github.com/ArcadeCity/Quartz) - NIP-44 encryption

## User Workflows

### App Launch
1. Initialize Nostr client
2. Connect to multiple relays
3. Subscribe to live stream events (30311)
4. Display curated/featured streams grid

### Watching a Stream
1. User selects a stream
2. Video player opens fullscreen
3. Presence event (10312) published (if authenticated)
4. Chat subscription begins
5. Zap receipts start polling
6. UI shows: Video, Live chat column, Zap chyron, Streamer profile access

### Chat
- Messages tied to stream via `a` tag
- Real-time message display
- Auto-scroll unless user scrolls manually
- Authenticated users can send messages

### Zaps
1. User opens zap menu
2. Selects preset zap amount
3. App generates zap request (NIP-57)
4. LN invoice returned
5. QR code displayed fullscreen
6. App polls for zap receipt
7. Zap appears in chyron and chat

### Leaving Stream
- Presence clear event (10312 empty)
- Chat subscription closed
- Zap polling stopped

## UI Architecture

- **Framework**: Jetpack Compose for TV
- **Architecture**: Single-Activity
- **Navigation**: NavHost

### Screens

| Screen | Description |
|--------|-------------|
| HomeScreen | Featured/curated live streams |
| PlayerScreen | Video player + chat + zap UI |
| LoginFlowScreen | Bunker login & profile confirmation |
| ProfileScreen | User profile & bunker status |

## SwiftUI to Android Mapping

| SwiftUI | Android |
|---------|---------|
| @StateObject | ViewModel |
| ObservableObject | StateFlow |
| NavigationStack | NavHost |
| fullScreenCover | NavController |
| AVPlayerViewController | ExoPlayer |
| Focus engine | Compose TV focus APIs |

## Project Structure

```
app/
├── data/
│   ├── nostr/
│   │   ├── NostrClient.kt
│   │   ├── NostrSubscriptions.kt
│   │   └── NostrModels.kt
│   ├── zap/
│   │   ├── ZapManager.kt
│   │   └── ZapModels.kt
│   └── auth/
│       ├── RemoteSignerManager.kt
│       └── SessionStore.kt
├── ui/
│   ├── home/
│   ├── player/
│   ├── chat/
│   ├── zap/
│   ├── profile/
│   └── components/
├── viewmodel/
├── navigation/
└── MainActivity.kt
```

## Git Workflow

Work in **small, testable increments**.

### Branch Strategy
- `main` → stable
- `feature/<scope>`

### PR Checkpoints

1. **Project Bootstrap** - Android TV app launches, Compose renders placeholder
2. **Nostr Connectivity** - Relays connect, console logs incoming events
3. **Stream Discovery** - 30311 events parsed, streams displayed
4. **Video Playback** - ExoPlayer plays stream URL
5. **Chat Read** - Messages appear live
6. **Chat Send** - Authenticated user sends message
7. **Bunker Login** - NIP-46 handshake works, profile loaded
8. **Zaps** - QR invoice displayed, receipt detected

Each checkpoint: manually test, commit, include short PR description.

## Progress Checklist

- [x] Project initialized
- [x] Compose TV UI rendering
- [x] Nostrino connected
- [x] Stream subscription active
- [x] Streams rendered
- [x] Video playback working
- [x] Chat receiving messages
- [x] Chat sending messages
- [x] Bunker login complete
- [x] Presence events sent
- [x] Zap request generation
- [x] QR invoice display
- [x] Zap receipt detection

## Phase 2 Feature Roadmap

### Priority Order (with dependencies)

| # | Feature | Branch | Dependencies | Status |
|---|---------|--------|--------------|--------|
| 1 | NIP-46 Remote Sign-in | `feature/nip46-auth` | None | **Done** |
| 2 | User Profile Page | `feature/stream-thumbnails` | #1 | **Done** |
| 3 | Admin Curated Streams | `feature/home-tabs` | None | **Done** |
| 4 | Following Section | `feature/home-tabs` | #1, #3 | **Done** |
| 5 | Stream Card Thumbnails | `feature/stream-thumbnails` | None | **Done** |
| 6 | Chat Manager (Read) | `feature/chat-read` | None | **Done** |
| 7 | Chat Send | `feature/chat-send` | #1, #6 | Pending |
| 8 | Presence Events | `feature/presence` | #1 | **Done** |
| 9 | Zap Chyron | `feature/zap-chyron` | None | **Done** |
| 10 | Streamer Profile + Zap Flow | `feature/zap-flow` | #1 | Pending |

### Feature Details

#### 1. NIP-46 Remote Sign-in Flow
- QR code display with `nostrconnect://` URI
- WebSocket connection to bunker relay (wss://relay.primal.net)
- Handle NIP-46 request/response protocol using Quartz library for NIP-44 encryption
- Two-step auth: connect ack → get_public_key to verify correct user pubkey
- Store session securely (EncryptedSharedPreferences)
- Auto-restore session on app launch

#### 2. User Profile Page
- Display signed-in user's profile (name, picture, npub)
- Logout button to clear session
- Navigate from home screen header

#### 3. Admin Curated Streams
- Hardcoded admin pubkey (configurable)
- Fetch admin's follow list (kind 3)
- Filter streams to only show those from followed pubkeys
- "Curated" tab on home screen for guest users

#### 4. Following Section
- "Following" tab on home screen (requires auth)
- Fetch logged-in user's follow list (kind 3)
- Filter streams to show only followed streamers
- Prompt sign-in if not authenticated

#### 5. Stream Card Thumbnails
- Parse `image` or `thumb` tag from kind 30311
- Display thumbnail in stream cards using Coil
- Fallback placeholder for missing thumbnails

#### 6. Chat Manager (Read)
- Subscribe to kind 1311 (NIP-53 live chat) events with `a` tag matching stream
- Combined subscription with zap receipts (kind 9735) for efficiency
- Real-time message display with author profiles and avatars
- Auto-scroll with manual scroll detection
- Message deduplication and profile enrichment

#### 7. Chat Send
- Text input field below chat
- Sign message via NIP-46 bunker
- Publish to relays
- Optimistic UI update

#### 8. Presence Events
- Send kind 10312 on stream join (NIP-53)
- Include `a` tag referencing stream
- Send empty/leave event on stream exit
- Requires authentication

#### 9. Zap Chyron
- Subscribe to kind 9735 (zap receipts) for stream
- Display scrolling ticker of recent 10 zaps
- Show sender name + amount
- Position at bottom of player screen

#### 10. Streamer Profile + Zap Flow
- Click streamer name → open profile modal
- Display streamer info (name, picture, about, follower count)
- Zap presets with friendly labels:
  - 21 sats - "Nice!"
  - 100 sats - "Great stream!"
  - 500 sats - "Buy a coffee"
  - 1000 sats - "Buy lunch"
  - 2100 sats - "Buy a pizza"
- Generate zap request (kind 9734) via bunker
- Display Lightning invoice QR
- Poll for zap receipt confirmation

### Admin Configuration

```kotlin
// Admin pubkey for curated streams (in NostrClient.kt)
const val ADMIN_PUBKEY = "f67a7093fdd829fae5796250cf0932482b1d7f40900110d0d932b5a7fb37755d"
```

## Non-Goals

- No Firebase
- No centralized backend
- No account system outside Nostr
- No mobile phone UI assumptions
- No custom cryptography

## Guidelines

- Favor correctness over shortcuts
- Prefer readable architecture over cleverness
- Log aggressively during early phases
- Match behavior, not pixel-perfect UI
- Treat Nostr event correctness as critical

Building a **TV-first, decentralized media client**. Proceed incrementally.

## Session Notes

### Last Session (Jan 19, 2025)
**Branch:** `main`
**PRs:** #8 (merged)

**Completed:**
- Fixed NIP-46 remote signing (PR #8)
  - Rewrote auth using Quartz library's `NostrSignerInternal` for battle-tested NIP-44 encryption
  - Replaced `BunkerAuthManager.kt` with `RemoteSignerManager.kt`
  - Changed relay from deprecated `relay.nsec.app` to `relay.primal.net`
  - Implemented two-step auth flow: connect ack → get_public_key to get correct user pubkey
  - Fixed session storage with EncryptedSharedPreferences
- Profile fetching and display
  - ProfileViewModel fetches kind 0 from relays after authentication
  - ProfileScreen displays avatar, name, NIP-05 verification, about text
- HomeScreen user profile display
  - Shows user avatar and name in header when logged in (instead of "Sign In" button)
  - Added `fetchUserProfile()` method to HomeViewModel

**Issues Discovered:**
- None (NIP-46 pubkey issue is now RESOLVED)

**Next session should:**
1. Implement Chat Send via NIP-46 bunker (feature #7)
2. Consider Admin Curated Streams feature (feature #3)
3. Consider Presence Events (feature #8)
4. Consider fullscreen chat toggle (video expands when chat hidden)

## End of Session Routine

When the user says **"end session"**, **"wrap up"**, **"session ending routine"**, or similar, execute this checklist:

### 1. Git Status Check
```bash
git status
git log --oneline -5
```
- Ensure working tree is clean
- If uncommitted changes exist, ask if they should be committed or stashed
- Verify current branch (should typically be `main` after merging feature work)

### 2. Update Progress Checklist
Review the Progress Checklist section and mark any newly completed items:
- Check off completed checkpoints
- Add new checkpoints if scope expanded

### 3. Update Feature Roadmap
Update the Phase 2 Feature Roadmap table:
- Change status from "Pending" to "**Done**" for completed features
- Add any new features discovered during the session
- Note any blockers or dependencies discovered

### 4. Document New Issues
If any bugs, blockers, or issues were discovered:
- Add to the "Known Issues" section with:
  - Clear title
  - Symptoms observed
  - Files involved
  - Attempted fixes (if any)
  - Potential causes

### 5. Update Session Notes
Replace or update the "Session Notes" section with:
```markdown
### Last Session (Date)
**Branch:** current branch or main
**PRs:** list any PRs created/merged

**Completed:**
- Bullet list of features/fixes completed this session

**Issues Discovered:**
- Any new issues found (reference Known Issues section)

**Next session should:**
1. Prioritized list of next steps
2. Any urgent fixes needed
3. Features to continue
```

### 6. Summarize for User
Provide a brief summary:
- What was accomplished this session
- Current state of the codebase
- Recommended next steps
- Any blocking issues to be aware of

### 7. Final Verification
```bash
git status
git branch -a
```
Confirm:
- Working tree is clean
- On expected branch
- Remote is up to date

---

**Trigger phrases:** "end session", "wrap up", "session ending routine", "close out session", "finish up"

## Build Commands

```bash
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install on connected device
```

## Remote Repository

https://github.com/tkhumush/nostrTV-droid.git
