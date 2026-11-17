# Kotlin binding

`chat.dumber.sdk.ChatApi` is the Android-facing API for the Rust core. It exposes
blocking methods and immutable models, including enum errors and sealed message
content. Call it from a worker thread. JSON is private to the JNI implementation;
applications do not construct operations, parse responses, or import `org.json`
to use this API.

```kotlin
val registration = ChatClient(ChatConfig(serviceUrl))
registration.requestCode(phone)
val session = registration.verify(phone, code)
// Save this opaque value in the host's encrypted account storage.
val savedSession = ChatStorage.encodeSession(session)

val client: ChatApi = ChatClient(ChatConfig(
    serviceUrl,
    ChatStorage.decodeSession(savedSession),
    CryptoStore(privateStorePath, persistentStorePassword),
))
val conversation = client.openChat(recipientPhone)
val sent = client.sendText(conversation.id, "Hello", stableTransactionId)
for (update in client.sync().conversations) {
    for (message in update.messages) {
        when (val content = message.content) {
            is MessageContent.Text -> println(content.body)
            MessageContent.UnableToDecrypt -> { /* Show a pending-decryption placeholder. */ }
            MessageContent.Unsupported -> { /* Hide or show an unsupported-message placeholder. */ }
        }
    }
}
```

`ChatStorage` also encodes/decodes conversations and messages as opaque strings.
The host chooses where to store them, encrypts sensitive local data, owns history
and deletion rules, and keeps pending-send transaction IDs stable across retries.
Session credentials and store passwords must never be inspected or logged.
`Session` and `CryptoStore` redact secrets from their string representations.
No Android context, SMS provider, database, lifecycle, UI, or retry scheduler is
part of this binding. Discovery, rate limiting, identity resolution, and protocol
interpretation remain in Rust.

## Build integration

The binding ships as source with the Rust core so the JNI library and Kotlin API
can be updated together without another binary release or protocol version.
Compile `src/main/kotlin` into the host and package the matching
`libdumber_chat_core.so` in `jniLibs/<abi>`. Android provides `org.json`; Kotlin
requires only its standard library. No serialization compiler plugin is needed.
Include `consumer-rules.pro` in builds that run R8/ProGuard.

DPAD includes this directory from its vendored Rust snapshot. The demo includes
the original source directly. For AGP 9's built-in Kotlin, configure
`android.sourceSets.main.kotlin.srcDir`; older Kotlin Android plugin builds can
include the directory in their existing Kotlin/Java source set.

The internal JNI entry point is `chat.dumber.sdk.NativeBridge.invoke`. The former
app-owned `chat.dumber.app.NativeCore` and JSON request facade have been removed.
Do not mix old binaries with this binding. No local-data migration is supplied.

`src/test/kotlin` contains Robolectric/JUnit binding tests. DPAD runs these together
with its chat integration tests. Application tests should inject `ChatApi` fakes;
only binding tests need knowledge of the serialized envelope.


`ChatApi` also provides `setCallCapability`, `callCapability`, `turnConfig`,
`sendCall`, and `acknowledgeCalls`. `SyncUpdate.calls` is separate from chat
messages. Process and acknowledge these typed events from a worker thread;
`CallSignal`/`CallKind` and `IceCandidate` keep wire event names out of the host.
Account credentials stay in the SDK owner. A media host only needs the call
signals and temporary `TurnConfig`; its `toString` redacts credentials.
