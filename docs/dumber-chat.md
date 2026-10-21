# dumber.chat transport

Settings → Messaging → Connect dumber.chat lists active SIM subscriptions. Select
one, confirm or enter its international phone number, and verify it by SMS.
Phone-number detection is optional: READ_PHONE_STATE enables SIM listing and
READ_PHONE_NUMBERS enables number hints on supported Android versions. Missing
or outdated carrier numbers can be entered or corrected manually. Only one
verification may be pending at a time. Automatic code submission requires the
SMS to arrive on the selected subscription; a missing subscription ID requires
manual code entry. Verification expires after five minutes.

Each SIM can be linked to a separate dumber.chat account. The conversation's SIM
picker selects both the carrier subscription and the encrypted sender identity;
the encryption status includes the sender's verified number. An unlinked SIM
uses carrier SMS and is labeled accordingly. Bindings use subscription IDs rather
than SIM slot positions. An inactive subscription, changed card ID, or changed
reported number cannot silently inherit an existing binding. Re-verify to link a
replacement SIM. Detection cannot establish number ownership; SMS verification
remains authoritative.

Existing installations retain their original account, encryption store, inbox,
and pending messages. An account without an active SIM binding is disconnected once telephony settles, including legacy accounts that have never been linked. SMS verification is required to connect it again. Linking it preserves its existing
Matrix device and encryption keys. New accounts receive separate sessions, crypto
stores, passwords, inboxes, recipient caches, and lookup retry timers. Account
state is encrypted with an Android Keystore key; WorkManager stores only a request
identifier.

The conversation window shows encrypted availability before the user sends a
message. The main list and contact suggestions do not query the directory or
show prospective encryption status. An unknown single recipient is queried when
the conversation opens; confirmed negative results are rechecked on conversation opening after 24 hours. SMS routing remains in effect during failed or rate-limited rechecks; transient lookup failures back off for five minutes.
Known Matrix IDs are retained in encrypted account storage and reused across
opens and sends. A manual availability recheck is available from the status
button. Only a directory 404 demotes a known recipient. Network, authentication,
and throttling errors leave availability unknown or preserve a known match.
The directory permits 50 lookups per rolling 24 hours per account; retry timing
is respected across conversations. No address-book upload takes place.

Sending to a known recipient creates/reuses a Matrix room and sends an encrypted
message without another phone lookup. The SDK verifies encryption before sending.
Successful messages retain the existing per-message **encrypted** label. Groups
and attachments use the existing carrier implementation and the composer status
reflects that. MDM filtering still applies to every transport.

Failed encrypted sends and sends with unknown availability stay in an app-private
AES-GCM-encrypted SQLite outbox. The conversation's Unsent button provides Retry
encrypted and Send as SMS; SMS always requires explicit confirmation. Cancel
keeps the message pending. Scheduled sends and notification replies never perform
discovery and notify the user if a decision is required. Positive cached matches
are also learned from authenticated reverse resolution of incoming messages.

Retries retain the same Matrix room and transaction ID across process restarts.
If an acknowledgement is lost, the SMS confirmation explains that the recipient
may already have received the encrypted message and SMS may duplicate it.
Confirmed encrypted delivery never falls back because local bookkeeping fails.
Outbox entries retain their original account and SIM even after the composer SIM
selection changes. Confirmation shows that sender identity; SMS fallback is
blocked when the original subscription is unavailable or linked to another
account. Encrypted retries require the original account to remain connected.

An ongoing foreground service runs independent receive loops for all connected
accounts while the app remains the default SMS handler. A linked SIM that remains absent or disabled for five seconds after telephony settles causes its account to be disabled locally. A warning notification and a persistent connection-screen notice identify the disconnected number. Server logout is retried when online; other accounts and local history are preserved. Reconnecting requires SMS verification again. Boot initialization, airplane mode, missing permissions and uncertain telephony state do not count as removal. It reconnects after network failures and restarts when the
app opens or the phone boots. Android force-stop, power management and unavailable
network access can delay reception; this integration does not use push services.
Received texts appear in the normal system SMS database, in the same threads as
carrier messages, with the receiving account’s active subscription ID when available. Their decrypted local bodies have the same local access rules
as other SMS. A reserved `service_center` value records encrypted transport; the
message body never determines the label. An encrypted private pending-event queue
and handled-event tombstones support decryption retries and prevent deleted texts
from reappearing after a full sync. Native `sync` internally catches up timeline
gaps using messages still retained by the server; the app does not call `history`
or keep server pagination cursors. The inbox v2 migration drops only the obsolete
cursor table, preserving pending events and handled-event tombstones.

The client does not request remote history, but the current Matrix/Synapse
backend retains encrypted room events. The imported core does not implement a
verified seven-day server purge or a delete-after-delivery acknowledgement, so
server-side copies may remain beyond seven days. Received messages also stay in
the local SMS database until the user deletes them. Signing in again does not
restore deleted local history.

Keys and account state use app-private no-backup storage. Clearing app data or
uninstalling removes those local keys. This screen does not implement key recovery
or device verification management.

## Build

The Rust source in `vendor/dumber-chat-core` is a local source snapshot dated
2026-10-21 from `dumber.chat/crates/chat-core`. `UPSTREAM.json` records source
checksums. It includes the typed Rust API and uses the supported legacy JSON/JNI
bridge for Android. Login, account state, encrypted stores, and send requests
remain compatible. Update the snapshot deliberately when updating the library.

Requirements: JDK 17, Android SDK 34, Rust 1.96 or later, and Android NDK
30.0.16248370 (or set `ANDROID_NDK_HOME`). Gradle's `preBuild` builds the native
library automatically. The build script supports Linux and macOS hosts using
the NDK's x86-64 host toolchain. GitHub CI installs the pinned Rust toolchain
and NDK, then runs the native-core tests alongside the Android checks.

```sh
rustup target add aarch64-linux-android
./gradlew assembleDebug testDebugUnitTest
```

The default native target is ARM64. Other supported targets can be built with:

```sh
rustup target add armv7-linux-androideabi x86_64-linux-android
./gradlew assembleDebug -PdumberRustTarget=armv7-linux-androideabi
./gradlew assembleDebug -PdumberRustTarget=x86_64-linux-android
```

All previously built libraries under `app/src/main/jniLibs` are packaged, so build
each required ABI before a multi-ABI release. Unsupported devices retain carrier
SMS and the connection screen explains that the encryption library is unavailable.
Native binaries and Cargo build outputs are not checked into Git.

To install a debug test build alongside a differently signed installed version:

```sh
./gradlew assembleDebug assembleDebugAndroidTest -PdebugApplicationIdSuffix=.dumbertest
```

## Validation

`DumberProtocolTest` covers OTP parsing, transport metadata and transport selection.
`DumberStateTest` covers coalesced discovery, cache expiry and account isolation,
error handling, retry timing, and encrypted outbox persistence on Android API 28.
`DumberNativeTest` checks JNI loading, encrypted queue storage and tombstones on a
device. `MigrationTest` verifies existing messages remain unencrypted after the
Room 6→7 migration.

`DumberRoundTripTest` is skipped unless instrumentation receives
`dumberTestService=http://127.0.0.1:PORT`. It requires an SSH-only development service
allowlisting reserved numbers +15555550101 and +15555550102, forwarded to the device
with `adb reverse`. It verifies lookup and reverse lookup, two-way decryption, and
that the Matrix server stores `m.room.encrypted` events. It logs out its test
sessions afterward. It must never be pointed at a production SMS verification
service. Real Twilio SMS receipt and automatic submission require a separate
interactive test with an opted-in phone number.

Validation on 2026-10-19: 38 JVM tests passed; five device tests covering JNI,
queue persistence, migrations and bubble-label recycling passed; the opt-in
round-trip test passed in both directions against Synapse using reserved accounts.
Debug and minified release APKs built successfully. Release lint reports the
pre-existing attachment RecyclerView `InvalidSetHasFixedSize` issue (three layout
occurrences); it is unrelated to encrypted transport. Live Twilio automatic
verification has not been exercised by these tests.

Discovery/rate-limit update validation: all 45 JVM tests passed, including
conversation-only discovery, unavailable-engine handling, durable pending sends,
and stable transaction IDs for scheduled retries. Debug, instrumentation, and
minified release APKs built successfully. The same three existing release lint
errors remain. No device was connected for this update, so its on-device and live
Synapse round-trip checks have not been rerun. Roll out the updated client before
enforcing the server's daily quota; legacy clients look up every send and may
silently fall back to SMS when that request fails.

Dual-SIM update validation: all 56 JVM tests passed, including independent SIM
routing and lookup backoff, legacy account linking, separate crypto stores and
inboxes, verification nonce/subscription/card checks, and pending-send identity
preservation. Debug, instrumentation and minified release APKs built successfully.
The five device checks for JNI, encrypted queue storage, migrations and message
labels passed using the separate `com.dpadsms.dumbertest` application. The two
opt-in provider/network scenarios were skipped. Release lint still reports the
same three pre-existing RecyclerView layout errors. Real two-SIM SMS verification
and simultaneous live reception on both accounts require an interactive check.

Recipient availability update: Synapse rejects a direct message with
`DUMBER_RECIPIENT_UNAVAILABLE` when the recipient has no registered devices. The
client marks that recipient SMS-only and asks whether to send the pending text as
ordinary SMS using its original SIM. Cancelling retains it in the encrypted
outbox. Background sends notify instead of falling back automatically. Ordinary
403/network failures do not demote availability. Historic sync and reverse
resolution cannot override a negative result; a successful lookup can. A prior
ambiguous send still shows the duplicate-delivery warning.
