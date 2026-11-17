package chat.dumber.sdk

/** Credentials are opaque. Persist through ChatStorage in encrypted storage; never log them. */
data class Session(val credentials: String, val accountId: String, val deviceId: String, val phone: String?) {
    override fun toString() = "Session(accountId=$accountId, deviceId=$deviceId)"
}
data class CryptoStore(val path: String, val password: String) {
    override fun toString() = "CryptoStore(path=$path)"
}
data class ChatConfig(val service: String, val session: Session? = null, val store: CryptoStore? = null)
enum class ErrorKind { INVALID_INPUT, AUTHENTICATION_REQUIRED, RECIPIENT_UNAVAILABLE, RATE_LIMITED, TRANSPORT_FAILURE, PERMISSION_DENIED, NOT_FOUND, UPGRADE_REQUIRED, INTERNAL }
class ChatException(message: String, val kind: ErrorKind = ErrorKind.INTERNAL, val retryAfterSeconds: Long? = null) : RuntimeException(message) {
    val recipientUnavailable: Boolean get() = kind == ErrorKind.RECIPIENT_UNAVAILABLE
}
enum class Membership { JOINED, INVITED, LEFT, BANNED, REQUESTED, UNKNOWN }
enum class ConversationKind { DIRECT, GROUP, UNKNOWN }
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
enum class SessionRole { MAIN, SECONDARY }
data class Contact(val id: String, val phone: String? = null)
data class GroupCapabilities(val manage: Boolean = false, val send: Boolean = false)
data class GroupMember(val contact: Contact, val membership: Membership, val admin: Boolean = false)
data class Conversation(
    val id: String,
    val kind: ConversationKind = ConversationKind.UNKNOWN,
    val name: String? = null,
    val members: List<GroupMember> = emptyList(),
    val capabilities: GroupCapabilities = GroupCapabilities(),
    val participants: List<Contact> = emptyList(),
    val membership: Membership = Membership.UNKNOWN,
)
sealed class MessageContent {
    data class Text(val body: String) : MessageContent()
    object UnableToDecrypt : MessageContent()
    object Unsupported : MessageContent()
}
data class Message(val eventId: String, val sender: Contact, val timestampMs: Long, val content: MessageContent, val encrypted: Boolean, val transactionId: String? = null)
data class ConversationUpdate(val conversation: Conversation, val messages: List<Message>, val limited: Boolean = false)
data class SyncUpdate(val conversations: List<ConversationUpdate>, val contactsPending: Boolean = false, val calls: List<CallEvent> = emptyList())
data class SentMessage(val eventId: String, val encrypted: Boolean)
data class RecipientStatus(val availability: Availability, val contact: Contact? = null, val retryAtMs: Long = 0)
data class CodeRequest(val status: String, val development: Boolean)
data class SessionStatus(val role: SessionRole, val mainActive: Boolean)
data class PairingReview(val description: String, val expiresAt: Long, val approved: Boolean)
data class Device(val deviceId: String, val name: String?, val verified: Boolean, val fingerprint: String?)
data class DeviceList(val contact: Contact, val currentDeviceId: String, val currentDeviceFingerprint: String?, val devices: List<Device>)
data class KeyImport(val importedCount: Long, val totalCount: Long)

enum class CallKind { INVITE, ANSWER, CANDIDATES, SELECT_ANSWER, REJECT, HANGUP, NEGOTIATE }
data class IceCandidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int)
data class CallSignal(val kind: CallKind, val callId: String, val partyId: String, val sentAtMs: Long,
    val sdp: String? = null, val descriptionType: String? = null, val candidates: List<IceCandidate> = emptyList(),
    val selectedPartyId: String? = null, val reason: String? = null)
data class CallEvent(val eventId: String, val conversationId: String, val sender: Contact, val expiresAtMs: Long, val signal: CallSignal)
class TurnConfig(val uris: List<String>, val username: String, val password: String, val ttl: Long) {
    override fun toString() = "TurnConfig(redacted)"
}
