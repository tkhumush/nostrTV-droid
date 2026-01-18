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

### NIP-46 Pubkey Problem (Critical)
The bunker authentication flow works but returns the **wrong pubkey**. When the bunker sends the `connect` response, it returns a SECRET as the result instead of the user's actual pubkey.

**Attempted fix:** Call `get_public_key` to verify the correct pubkey after connect, but the bunker returns "Failed to decrypt content" - this suggests a NIP-44 encryption compatibility issue between our implementation and the bunker.

**Current workaround:** Using the bunker's pubkey as a temporary user identifier. Login completes but profile fetching retrieves the bunker's profile instead of the user's profile.

**Files to investigate:**
- `BunkerAuthManager.kt` - lines ~280-350 (handleConnectAck, verifyAndCorrectPubkey)
- `NostrCrypto.kt` - NIP-44 encryption implementation

**Symptoms:**
- User logs in successfully (QR code scanned, bunker connected)
- Profile page shows "Loading..." or wrong profile
- Debug logs show profile fetch for wrong pubkey

**Potential causes:**
1. NIP-44 encryption mismatch (ChaCha20 implementation differs from bunker)
2. Conversation key derivation issue
3. Nonce handling difference

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
│       ├── BunkerAuthManager.kt
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
| 3 | Admin Curated Streams | `feature/curated-streams` | None | Pending |
| 4 | Following Section | `feature/following` | #1, #3 | Pending |
| 5 | Stream Card Thumbnails | `feature/stream-thumbnails` | None | **Done** |
| 6 | Chat Manager (Read) | `feature/chat-read` | None | Pending |
| 7 | Chat Send | `feature/chat-send` | #1, #6 | Pending |
| 8 | Presence Events | `feature/presence` | #1 | Pending |
| 9 | Zap Chyron | `feature/zap-chyron` | None | Pending |
| 10 | Streamer Profile + Zap Flow | `feature/zap-flow` | #1 | Pending |

### Feature Details

#### 1. NIP-46 Remote Sign-in Flow
- QR code display with `nostr+connect://` or `bunker://` URI
- WebSocket connection to bunker relay (wss://relay.nsec.app)
- Handle NIP-46 request/response protocol
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
- Subscribe to kind 1 events with `a` tag matching stream
- Real-time message display with author profiles
- Auto-scroll with manual scroll detection
- Message deduplication

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
// Admin pubkey for curated streams (to be configured)
const val ADMIN_PUBKEY = "YOUR_ADMIN_PUBKEY_HERE"
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

### Last Session (Jan 2025)
**Branch:** `feature/stream-thumbnails`
**PR:** #1 (pending merge to main)

**Completed:**
- NIP-46 remote signing with QR code flow
- NIP-44 encryption (ChaCha20 + HMAC-SHA256)
- User profile page with two-column TV layout
- Profile metadata fetching (kind 0)
- Stream card thumbnails with Coil
- Gradient overlay, LIVE badge, viewer count
- Streamer profile pictures on cards
- Stream deduplication (one per pubkey)
- Shared NostrClient singleton

**Next session should:**
1. Merge PR #1 to main
2. Fix NIP-46 pubkey issue (see Known Issues above)
3. Continue with Admin Curated Streams or Chat features

## Build Commands

```bash
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install on connected device
```

## Remote Repository

https://github.com/tkhumush/nostrTV-droid.git
