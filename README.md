<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" alt="DPAD Messaging icon" width="128" />
</p>

<h1 align="center">DPAD Messaging</h1>

<p align="center">
  A focused messaging app for dumbphones, keypad phones, and anyone who prefers physical navigation.
</p>

<p align="center">
  <a href="https://github.com/jbriones95/DPAD-Messaging/releases"><img src="https://img.shields.io/github/v/release/jbriones95/DPAD-Messaging?display_name=tag&label=latest%20release" alt="Latest release" /></a>
  <a href="https://f-droid.org/packages/com.dpadsms/"><img src="https://img.shields.io/f-droid/v/com.dpadsms.svg" alt="Available on F-Droid" /></a>
  <a href="https://github.com/jbriones95/DPAD-Messaging/actions/workflows/android-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/jbriones95/DPAD-Messaging/android-ci.yml?label=build" alt="Build status" /></a>
</p>

<p align="center">
  <a href="https://github.com/jbriones95/DPAD-Messaging/releases">Download</a> ·
  <a href="https://f-droid.org/packages/com.dpadsms/">F-Droid</a> ·
  <a href="https://github.com/jbriones95/DPAD-Messaging/issues">Report an issue</a>
</p>

## Built for Physical Navigation

DPAD Messaging keeps the important actions close, predictable, and usable without a touchscreen. It is designed for small screens, D-pads, hardware keys, and one-handed use.

## Highlights

- SMS/MMS send and receive
- Group messaging with fan-out or group MMS
- D-pad optimized navigation
- D-pad focus and activation for phone numbers and web links in messages
- Dark/light theme with customizable accent colors
- Per-contact colors for avatars and message bubbles
- Pin, archive, mute, and delete conversations
- Unread badges and "mark as unread"
- Recycle bin with recovery
- Keyword- and number-based message blocking
- Delivery reports
- Scheduled messages
- MMS attachment preview/gallery, voice messages, and speech-to-text dictation
- Contact lookup with auto-suggest
- Encrypted backup & restore of app data (device-bound)

## Requirements

- Android 6.0+ (API 23+)
- Default SMS app permission

## Building from Source

### Prerequisites

- Android Studio (2023+)
- Gradle 8.5+
- Java 17

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# General release build
./gradlew assembleRelease

# Signed experimental build
EXPERIMENTAL_VERSION_CODE=1048001 ./gradlew assembleExperimental
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release.apk`, or
`app/build/outputs/apk/experimental/app-experimental.apk`.

General releases are published only from explicit `v*` tags. Pushes to `master`
build a signed experimental prerelease at the GitHub `experimental` tag. Both
channels use the same application ID and signing key, so an experimental APK
can update a general installation. Before a general release, its versionCode
must be higher than the latest experimental versionCode.

## Project Structure

```
app/src/main/
├── kotlin/com/dpad/messaging/
│   ├── activities/       # Activity classes
│   ├── adapters/         # RecyclerView adapters
│   ├── databases/        # Room database
│   ├── helpers/          # Utility classes (ThemeManager, Prefs, MmsSender, etc.)
│   ├── models/           # Data models
│   ├── receivers/        # Broadcast receivers
│   └── services/         # Background services
└── res/
    ├── layout/           # XML layouts
    ├── values/           # Colors, strings, themes
    └── drawable/         # Icons and drawables
```

## Key Components

### ThemeManager
Handles theme mode (system/light/dark) and accent color selection.

### ContactColors
Per-contact color assignment used for avatars and message bubbles.

### Prefs
SharedPreferences wrapper for app settings storage.

### UnifiedMessageSender
Routes outgoing messages through the appropriate SMS/MMS path, including scheduled sends.

### MmsHelper
Composes, sends, downloads, and parses MMS messages (via the vendored mmslib).

### SmsSender
Handles SMS message sending via SmsManager, including long-message (multipart) handling.

### BackupManager
Encrypted, device-bound backup and restore of app-local data.

## Support the Project

If you find this app useful, consider supporting its development:

[![Buy Me a Coffee](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/jbriones95)

## License

MIT

## Optional dumber.chat messaging

Settings → Messaging → **Connect dumber.chat** enables encrypted one-to-one text
messages with registered contacts, with explicit confirmation before carrier SMS fallback. Native
build requirements, behavior and tests are described in
[docs/dumber-chat.md](docs/dumber-chat.md).
