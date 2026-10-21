# dumber-chat-core

Portable Rust messaging library for dumber.chat. Provides phone-number login,
contact lookup, private one-to-one Matrix rooms, and end-to-end encrypted text
messaging using the Matrix Rust SDK. Builds as an `rlib` and `cdylib`; Android
uses the included Java/JNI JSON bridge.

## Build and check

Requires Rust 1.96 or newer, a C/C++ toolchain, and CMake for native dependencies.
SQLite is bundled. From this repository:

```sh
cargo build --locked
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
cargo fmt --all -- --check
```

This repository can be cloned and built independently. In the dumber.chat
application checkout it also remains a member of the parent Cargo workspace,
so the existing Android build script continues to work.

## Use from Rust

Add a path dependency (or a Git dependency once you host this repository):

```toml
[dependencies]
dumber-chat-core = { path = "../chat-core" }
```

```rust,ignore
use dumber_chat_core::Client;

// Generate the store password once (at least 32 characters of random material)
// and persist it using the host platform's secure storage.
let client = Client::new("https://nokings.chat")?
    .with_crypto_store(private_store_directory, persistent_store_password);
client.request_code(phone_number)?;
let session = client.verify(phone_number, sms_code)?;
let conversation = client.open_chat(&session, recipient_phone_number)?;
let sent = client.send_text(&session, &conversation.id, "Hello!", unique_transaction_id)?;
let update = client.sync(&session)?;
```

All calls block: run them on worker threads. Use a separate worker for long-poll
sync. Save the returned session securely and reuse it across launches. The host
stores and renders sent and received messages locally, deduplicating by `event_id`; the SDK persists
its sync cursor and device keys. Reuse transaction IDs when retrying sends.

The intended backend policy is **temporary encrypted delivery queues, with
deletion after delivery or expiry, and no server-side history archive**.
`sync` receives available
messages and conversation changes; it is not a history restore operation.
There is no `history` method, pagination cursor, or history-page type.
Losing the application's message database loses that device's history. Signing
in on a new device or importing encryption keys does not restore message content.

This is an API contract, not an enforced backend retention policy: the current
Matrix/Synapse transport still persists encrypted events. Removing history
fetching does not delete those events. A backend delivery/retention change is
still required to implement the temporary delivery queue and its deletion policy.
The API does not yet offer a delivery acknowledgement that deletes server data.

The phone service supplies `/api/auth/request-code`, `/api/auth/verify`,
`/api/directory/lookup`, `/api/directory/resolve`, `/api/session`, and browser-pairing endpoints. It is a separate component;
Synapse alone does not supply those endpoints. Messages go directly to Matrix.
Phone arguments use canonical E.164 form, such as `+46701234567`. The library
resolves and caches identities internally; there are no public Rust `lookup` or
`resolve` methods. `open_chat` reuses known active conversations without another
directory lookup, and `send_text` never queries the directory. Mappings and room
IDs are stored encrypted alongside the SDK store and survive restarts. Keep
sync running to observe membership changes.

### Typed results

All operations return `anyhow::Result<T>`. The public messaging API uses:

| Operation | Result |
| --- | --- |
| `request_code(phone)` | `CodeRequest { status, development }` |
| `verify(phone, code)` | New main `Session`; the server revokes all previous devices |
| `session_status(&session)` | `SessionStatus { role, main_active }` |
| `review_pairing(&session, code)` | `PairingReview { description, expires_at, approved }` |
| `approve_pairing(&session, code)` | `PairingReview`; requires the current main phone |
| `open_chat(&session, phone)` | `Conversation { id, participants, membership }` |
| `conversations(&session)` | Cached `Vec<Conversation>` |
| `join(&session, &conversation.id)` | `()` |
| `send_text(&session, &conversation.id, body, transaction_id)` | `SentMessage { event_id, encrypted }` |
| `sync(&session)` | `SyncUpdate { conversations, contacts_pending }` |
| `devices(&session, optional_phone)` | `DeviceList` (`None` selects your own account) |
| `export_keys(&session, path, passphrase)` | `()` |
| `import_keys(&session, path, passphrase)` | `KeyImport { imported_count, total_count }` |
| `logout(&session)` | `()` |

`ConversationId` and `ContactId` are opaque serializable types.
`Contact` includes an opaque stable account ID and an optional phone number.
An unknown or unavailable directory entry leaves `phone: None`; it does not
prevent message delivery. Match known phone numbers against local contacts.
Cached mappings stay pinned to the original account rather than silently
retargeting conversations if a phone number is reassigned.

Each `ConversationUpdate` has a `conversation`, `messages`, and a `limited` gap
flag. Membership is `Joined`, `Invited`, or `Left`.
Accept invitations with `join`, then sync again. `limited` means the transport
reported a gap. Subsequent sync calls internally page through missed events still
retained by the server, with encrypted persistent catch-up cursors. There is no
public history-fetch operation, and expired events cannot be recovered. Keep this
signal so applications do not mistake an incomplete batch for complete delivery.

Messages contain `event_id`, `sender: Contact`, `timestamp_ms`, `encrypted`, and
`content: MessageContent`. Content is `Text { body }`, `UnableToDecrypt`, or
`Unsupported` for non-text/redacted messages. State events are not messages.
The library persists unavailable encrypted events and retries them on later
syncs. Replace existing messages with the same `event_id` when a retry succeeds.
Directory failures set `contacts_pending`; later syncs retry resolution and can
emit conversation updates containing only refreshed contact labels.

Session credentials are private Rust fields. Serialize the entire `Session`
for secure persistence and deserialize it to restore it; its serialized form
still contains internal credentials and must not be displayed or logged.
Older sessions without a phone field remain readable (`phone()` returns `None`).

## JSON and Android bindings

`invoke` is the **legacy Matrix-shaped compatibility bridge**, retained for the
existing Android application. It does not use the new typed wire schema.
New Rust integrations should use the methods above.

`invoke` accepts JSON with `command`, `service`, `session`, and command arguments.
Messaging commands require `store_path` and `store_password`. Responses are
`{"ok":true,"data":...}` or `{"ok":false,"error":"..."}`.
HTTP failures additionally provide `http_status` and, when available,
`retry_after_seconds`; the existing error string is preserved. A lookup's 404
means the recipient is not registered. Other failures, including 429, do not.
Retain successful phone-to-Matrix-ID mappings locally and reuse them for
`open_chat` and `send`: neither operation requires another directory lookup.

Commands: `request_code`, `verify`, `lookup`, `resolve`, `open_chat`, `join`,
`send`, `sync`, `devices`, `export_keys`, `import_keys`, and `logout`.
The removed `history` command returns an explicit local error without contacting
the server. Sync responses no longer include `prev_batch` history cursors.
See `src/lib.rs` for legacy argument names and `src/api.rs` for the typed API. Legacy `sync` also accepts
`retry_events`: objects containing `room_id` and the original encrypted `event`
for messages whose keys were not yet available.

The Android JNI entry point is `chat.dumber.app.NativeCore.invoke(String)`.
Cross-compile for `aarch64-linux-android` using Android NDK Clang (API 26+), setting
Cargo's target linker and the target-specific CC, CXX and AR variables. Package
`libdumber_chat_core.so` in the application's ARM64 `jniLibs` directory.

## Encryption and persistence

Sends require persistent encryption storage and fail rather than send plaintext.
New rooms start encrypted. Existing rooms are upgraded before sending when the
current user has permission. Earlier plaintext messages remain plaintext.

Each login creates a separate device. Never share one device session between
independent crypto stores. Preserve the crypto store and its password; a new
login does not automatically recover old history keys. Export password-encrypted
message keys before discarding a device and import them on the replacement.
Key exports contain encryption keys, not messages; retain or transfer the
application's local message database separately if history must be preserved.
Export passphrases must be at least 12 characters. Automatic server key backup
and interactive device verification are not implemented.

## Live integration examples

`examples/e2ee.rs` uses two reserved test-account sessions in a private JSON array
file and a persistent test crypto directory:

```sh
CHAT_TEST_SESSIONS=/private/test-sessions.json \
CHAT_TEST_STORE=/private/test-crypto \
cargo run --locked --example e2ee
```

For legacy session fixtures lacking a `phone` field, also set
`CHAT_TEST_RECIPIENT_PHONE` to the second test account's phone number.

It checks encrypted delivery, ciphertext on Synapse, encrypted key export/import,
and wrong-passphrase rejection. Optional `CHAT_EXPECT_MESSAGE` and
`CHAT_IMPORT_BACKUP` check browser interoperability. It targets nokings.chat and
must only be run with reserved test accounts. Revoke test sessions after testing.

`examples/smoke.rs` requires the application's separately deployed, private
SMS-development service (`CHAT_SERVICE`, default `http://127.0.0.1:18091`) and
`CHAT_TEST_STORE`. Both examples use fixed **test-only** crypto-store passwords;
never use those passwords or test sessions in production.

### Main-phone lifecycle

Phone authentication sends protocol 2. Replacement and cascading logout are
server-enforced: a replacement phone revokes the old phone and browsers; logging
out the main phone revokes linked browsers. Browser-only logout leaves the main
phone intact. `review_pairing` must precede explicit user confirmation and
`approve_pairing`; use the same session for both. The app supplies the eight-digit
code displayed by the browser. `HttpFailure` exposes `status`, `error_code`,
`detail`, and `retry_after_seconds`; HTTP 401 means the session must be replaced
through phone verification. Do not silently create a new session or auto-approve.

DPAD uses the JSON commands `session_status`, `pairing_review`, and
`pairing_approve` (the latter two take `code`) through the same Rust JNI bridge as
verification, directory queries, logout, and encrypted messaging. No app-side HTTP
implementation is required. On verification, adopt the returned session and use
a new device crypto store; retain the application's local message history.
