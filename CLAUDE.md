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
