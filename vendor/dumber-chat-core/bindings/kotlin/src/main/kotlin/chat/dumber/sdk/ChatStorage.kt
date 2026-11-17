package chat.dumber.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Opaque persistence codecs. The host owns storage, encryption, retention, and history. */
object ChatStorage {
    @JvmStatic fun encodeSession(value: Session): String = Wire.session(value).toString()
    @JvmStatic fun decodeSession(value: String): Session = Wire.session(JSONObject(value))
    @JvmStatic fun encodeConversation(value: Conversation): String = Wire.conversation(value).toString()
    @JvmStatic fun decodeConversation(value: String): Conversation = Wire.conversation(JSONObject(value))
    @JvmStatic fun encodeMessage(value: Message): String = Wire.message(value).toString()
    @JvmStatic fun decodeMessage(value: String): Message = Wire.message(JSONObject(value))
}
internal fun JSONObject.nullableString(key: String): String? = if(isNull(key)) null else getString(key)
internal fun JSONObject.nullableLong(key: String): Long? = if(isNull(key)) null else getLong(key)
internal fun <T> JSONArray.objects(decode: (JSONObject) -> T): List<T> = (0 until length()).map { decode(getJSONObject(it)) }
internal object Wire {
    inline fun <reified T : Enum<T>> enum(value: String, fallback: T? = null): T = enumValues<T>().firstOrNull { it.name.lowercase(Locale.ROOT) == value }
        ?: fallback ?: throw ChatException("Unsupported value from messaging core")
    fun Enum<*>.wire() = name.lowercase(Locale.ROOT)
    fun session(v: JSONObject) = Session(v.getString("credentials"), v.getString("account_id"), v.getString("device_id"), v.nullableString("phone"))
    fun session(v: Session) = JSONObject().put("credentials", v.credentials).put("account_id", v.accountId).put("device_id", v.deviceId).put("phone", v.phone)
    fun contact(v: JSONObject) = Contact(v.getString("id"), v.nullableString("phone"))
    fun contact(v: Contact) = JSONObject().put("id", v.id).put("phone", v.phone)
    fun pairing(v: JSONObject) = PairingReview(v.getString("description"), v.getLong("expires_at"), v.getBoolean("approved"))
    fun recipient(v: JSONObject) = RecipientStatus(enum(v.getString("availability"), Availability.UNKNOWN), v.optJSONObject("contact")?.let(::contact), v.getLong("retry_at_ms"))
    fun conversation(v: JSONObject) = Conversation(v.getString("id"), enum(v.getString("kind"), ConversationKind.UNKNOWN), v.nullableString("name"),
        v.getJSONArray("members").objects { GroupMember(contact(it.getJSONObject("contact")), enum(it.getString("membership"), Membership.UNKNOWN), it.getBoolean("admin")) },
        v.getJSONObject("capabilities").let { GroupCapabilities(it.getBoolean("manage"), it.getBoolean("send")) },
        v.getJSONArray("participants").objects(::contact), enum(v.getString("membership"), Membership.UNKNOWN))
    fun conversation(v: Conversation) = JSONObject().put("id", v.id).put("kind", v.kind.wire()).put("name", v.name)
        .put("members", JSONArray(v.members.map { JSONObject().put("contact", contact(it.contact)).put("membership", it.membership.wire()).put("admin", it.admin) }))
        .put("capabilities", JSONObject().put("manage", v.capabilities.manage).put("send", v.capabilities.send))
        .put("participants", JSONArray(v.participants.map(::contact))).put("membership", v.membership.wire())
    fun message(v: JSONObject) = Message(v.getString("event_id"), contact(v.getJSONObject("sender")), v.getLong("timestamp_ms"),
        v.getJSONObject("content").let { when(it.getString("kind")) {
            "text" -> MessageContent.Text(it.getString("body"))
            "unable_to_decrypt" -> MessageContent.UnableToDecrypt
            else -> MessageContent.Unsupported
        } }, v.getBoolean("encrypted"), v.nullableString("transaction_id"))
    fun message(v: Message) = JSONObject().put("event_id", v.eventId).put("sender", contact(v.sender)).put("timestamp_ms", v.timestampMs)
        .put("content", when(val content = v.content) {
            is MessageContent.Text -> JSONObject().put("kind", "text").put("body", content.body)
            MessageContent.UnableToDecrypt -> JSONObject().put("kind", "unable_to_decrypt")
            MessageContent.Unsupported -> JSONObject().put("kind", "unsupported")
        }).put("encrypted", v.encrypted).put("transaction_id", v.transactionId)
}
