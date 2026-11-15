# dumber-chat-core

Portable Rust messaging library for dumber.chat. Provides phone-number login,
contact lookup, private one-to-one Matrix rooms, and end-to-end encrypted text
messaging using the Matrix Rust SDK. Builds as an `rlib` and `cdylib`; Android
uses the shared typed Kotlin binding in `bindings/kotlin`.

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

## Android binding

Use the shared [Kotlin binding](bindings/kotlin/README.md). `ChatApi` exposes typed
methods such as `verify`, `openChat`, `sendText`, `sync`, and `createGroup`.
Results are Kotlin models; failures throw `ChatException` with an `ErrorKind` and
optional retry delay. DPAD and the Android demo compile the same binding source.
There is no application-facing JSON command API.

`ChatStorage` encodes sessions, conversations, and messages as opaque strings for
host-owned persistence. The library does not own the application's message database.

### Internal JNI wire contract

`chat.dumber.sdk.NativeBridge` privately serializes requests for `src/bridge.rs`,
which delegates to public Rust `Client` methods. This is a library implementation
detail. Requests carry `api_version:2`, client configuration, session metadata,
and a tagged operation. The old app-owned JNI class and Matrix-shaped commands
are removed. Ship the binding and native library from the same source snapshot.

`verify` returns `SessionInfo`; persist it securely. Its `credentials` string is
opaque: never parse, display, or log it. `Session::info()` and `SessionInfo::restore()`
provide the same conversion for Rust callers. `request_code` and `verify` do not
require a session. Messaging requires the persistent encrypted store.

Successful responses are `{"ok":true,"data":...}`; unit results use `null` and
`conversations` returns an array. Failures contain `ok:false`, a safe `error`
message, `error_kind`, and optional `retry_after_seconds`. Use `Failure::from_error`
for the same classification from Rust. Kinds include `invalid_input`,
`authentication_required`, `recipient_unavailable`, `rate_limited`,
`transport_failure`, `permission_denied`, `not_found`, `upgrade_required`, and
`internal`. No Matrix error codes or raw server diagnostics cross this boundary.

The operation names and typed arguments are defined in `src/bridge.rs`.
`recipient_status(phone)` reads cached availability; `check_recipient(phone, force)`
checks availability without creating a conversation. Negative results last 24 hours;
a failed refresh preserves a negative decision. Transient errors back off for five
minutes; server rate limits apply across numbers for the account. Force bypasses TTL,
not server rate limits. `open_chat_cached` (or `cached_only:true` in the bridge)
never performs directory discovery. Sends retain caller-supplied transaction IDs.
Received messages may include `transaction_id` for reconciling outgoing echoes.

`sync` owns identity resolution, membership interpretation, catch-up, and decryption
retries. Applications consume `SyncUpdate` and keep their histories and pending-send
databases. No raw room events, user-ID lookup, history pagination, or decryption-retry
payloads are accepted by the normal bridge operations.

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

Test sessions must include their verified `phone` fields.

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
code displayed by the browser. `Failure::from_error` exposes a protocol-independent `kind`,
`message`, and `retry_after_seconds`; HTTP 401 means the session must be replaced
through phone verification. Do not silently create a new session or auto-approve.

DPAD uses the Kotlin methods `sessionStatus()`, `reviewPairing(code)`, and
`approvePairing(code)` through the shared binding, alongside verification,
discovery, logout, and encrypted messaging. No app-side HTTP
implementation is required. On verification, adopt the returned session and use
a new device crypto store; retain the application's local message history.

## Encrypted groups

`create_group(&session, name, &[], operation_id)` creates a private,
non-federated encrypted group with only the creator. Add participants afterward
with `invite_member`; an initial list of phone numbers is also supported.
Persist a unique operation ID before calling and
reuse it with the same inputs after errors. Creation uses a stable room alias,
so retrying an ambiguous response does not create a second group. Phone lookups
are cached; every chosen recipient must resolve before room creation.

`Conversation` now includes `kind` (`direct`, `group`, or `unknown`), `name`,
`members` (contact, membership enum, admin), and `capabilities` (manage/send).
Partial invitations may have unknown kind: do not guess a direct recipient from an inviter alone.

Operations: `conversation_info`, `invite_member`, `remove_member`, `rename_group`,
`grant_admin`, and `leave`. Removal/admin operations take opaque `ContactId`s;
creation/invitation take E.164 numbers. Existing `join`, `send_text`, and `sync`
work with groups. Sync emits name, membership, and permission updates even without
text messages. Sync again after management operations to update cached typed
conversations, or use `conversation_info` for a fresh snapshot.

The JSON bridge uses the same operations with `conversation_id` and `contact_id`
arguments. `conversation_info` fetches current metadata; `conversations` reads
cached typed metadata. Fetching current state may fail for invitations or departed
members.

Groups use immutable creation type `chat.dumber.group`, including when only two
members remain. New members receive messages from joining onward. Admins control
name, invitations, removal, and admin grants. Leaving does not require a handover;
to hand over, confirm `grant_admin` succeeds before calling `leave`. Matrix may
retain creator privileges, so this is an admin grant, not creator replacement.
Names and membership are ordinary Matrix state visible to the server; text is
end-to-end encrypted. Local deletion and leaving do not delete others' history.

The opt-in `groups` example requires `CHAT_SERVICE`, `CHAT_TEST_SESSIONS` (three
reserved sessions with phone fields), and `CHAT_TEST_STORE`. It checks delivery,
ciphertext, creation retries, admin handover, and exports the recipient's keys to
test that pre-join and post-removal events cannot be decrypted. Run it only on a
private test service with dedicated sessions; revoke sessions after testing.


## Typed encrypted call signaling

`set_call_capability`, `call_capability`, `turn_config`, `send_call`, and
`acknowledge_calls` expose call support without giving a host raw Matrix events.
`SyncUpdate.calls` contains `CallEvent` values separately from chat messages.
`CallSignal` carries a `CallKind`, call/party IDs, timestamp, and bounded typed SDP
or candidate fields. The bridge exposes equivalent `set_call_capability`,
`call_capability`, `turn_config`, `send_call`, `acknowledge_calls` operations;
this is an additive API v2 extension.

Only decrypted events from the other member of a joined direct conversation are
accepted. Plaintext, groups, wrong senders, expired signals and replayed event IDs
are rejected. SDP must contain one DTLS-SRTP audio section and a SHA-256 certificate
fingerprint. An invite lives at most 45 seconds and renegotiation 15 seconds;
other events live 60 seconds. The bounded durable inbox remains in the encrypted
store until the host acknowledges delivery or events expire, allowing a Binder
consumer to reconnect. It must not be displayed as a message or counted unread.
The host owns ringing, timeouts, answer selection, deduplication across process
restarts, block policy and media. TURN passwords must never be logged.

A host sends all signals through `send_call` with a stable transaction ID across
retries. The core requires an encrypted room with exactly two joined members and
uses the SDK's encrypted raw-event send path. This uses the existing message
trust policy; it does not add a separate identity verification workflow.
