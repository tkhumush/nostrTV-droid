# nostrTV for Android TV

**nostrTV** is a native Android TV application enabling users to watch decentralized live streams from the Nostr protocol while supporting streamers through Lightning Network payments.

> Looking for the Apple TV version? Check out [nostrTV for tvOS](https://github.com/tkhumush/nostrTV)

## Screenshots

<!-- TODO: Add screenshots -->
*Screenshots coming soon*

## Key Features

- **Live Stream Discovery** - Automatically discovers live streams from multiple Nostr relays
- **Lightning Zaps** - Send sats to streamers via Lightning Network with QR code support
- **Real-time Chat** - Live chat messaging tied to streams
- **Zap Chyron** - Scrolling display of recent zaps at the bottom of the player
- **Nostr Authentication** - Sign in with your Nostr identity via NIP-46 remote signing (Amber, etc.)
- **Presence Events** - Announce your presence when watching streams (NIP-53)
- **TV-Optimized UI** - Interface designed specifically for television viewing with D-pad navigation

## Technical Foundation

### Platform Requirements
- Android TV / Google TV
- Minimum SDK: Android 8.0 (API 26)
- Target SDK: Android 14 (API 34)

### Tech Stack
- **Kotlin** - Primary development language
- **Jetpack Compose for TV** - Modern declarative UI framework optimized for TV
- **ExoPlayer** - Video playback
- **Quartz Library** - NIP-44 encryption for remote signing
- **Coil** - Image loading
- **OkHttp** - Network requests for LNURL

### Nostr Protocol Support

| Feature | NIP / Kind |
|---------|------------|
| Live Streams | Kind 30311 |
| Live Chat | Kind 1311 (NIP-53) |
| Presence | Kind 10312 (NIP-53) |
| Zap Request | Kind 9734 (NIP-57) |
| Zap Receipt | Kind 9735 (NIP-57) |
| Profiles | Kind 0 |
| Follow Lists | Kind 3 |
| Remote Signing | NIP-46 |

### Connected Relays
- wss://relay.damus.io
- wss://relay.nostr.band
- wss://relay.snort.social
- wss://nostr.wine
- wss://relay.primal.net
- wss://purplepag.es

## Installation

### From Source

1. Clone the repository:
   ```bash
   git clone https://github.com/tkhumush/nostrTV-droid.git
   ```

2. Open in Android Studio (Hedgehog or later recommended)

3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

4. Install on your Android TV device:
   ```bash
   adb connect <device-ip>:5555
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Project Structure

```
app/
├── data/
│   ├── nostr/          # Nostr client, subscriptions, models
│   ├── zap/            # Lightning zap management
│   └── auth/           # NIP-46 remote signing
├── ui/
│   ├── home/           # Home screen with stream discovery
│   ├── player/         # Video player with chat and zaps
│   ├── profile/        # User profile and authentication
│   ├── zap/            # Zap flow overlays
│   └── auth/           # Sign-in prompts
├── viewmodel/          # ViewModels for state management
├── navigation/         # Navigation setup
└── MainActivity.kt
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Nostr Protocol](https://github.com/nostr-protocol/nips) - The decentralized social protocol
- [Quartz Library](https://github.com/ArcadeCity/Quartz) - NIP-44 encryption from Amethyst
- [Jetpack Compose for TV](https://developer.android.com/tv/compose) - Google's TV UI framework
