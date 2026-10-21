//! Phone-oriented public API. Matrix identifiers and events stay inside the client.
use crate::{Client, Session};
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, path::Path};

/// Session ownership is assigned and enforced by the server.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionRole {
    Main,
    Secondary,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SessionStatus {
    pub role: SessionRole,
    pub main_active: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PairingReview {
    pub description: String,
    pub expires_at: u64,
    pub approved: bool,
}

/// Opaque, persistent conversation identity. Serialize it with your message database.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ConversationId(String);

/// Stable opaque account identity, including when a phone number cannot be resolved.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ContactId(String);

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Contact {
    pub id: ContactId,
    /// None for an unknown/unresolvable account. Never substitute an internal ID.
    pub phone: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Membership {
    Joined,
    Invited,
    Left,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Conversation {
    pub id: ConversationId,
    /// Other active participants, excluding the current account.
    pub participants: Vec<Contact>,
    pub membership: Membership,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum MessageContent {
    Text {
        body: String,
    },
    UnableToDecrypt,
    /// A non-text or redacted message. Raw protocol content is deliberately omitted.
    Unsupported,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Message {
    pub event_id: String,
    pub sender: Contact,
    pub timestamp_ms: u64,
    pub content: MessageContent,
    pub encrypted: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ConversationUpdate {
    pub conversation: Conversation,
    pub messages: Vec<Message>,
    /// The transport reported a gap before this batch. There is no server-history
    /// API to backfill it; do not interpret a successful sync as complete delivery.
    pub limited: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SyncUpdate {
    pub conversations: Vec<ConversationUpdate>,
    /// Directory availability does not prevent message delivery. Unknown phones
    /// are retried on subsequent syncs.
    pub contacts_pending: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SentMessage {
    pub event_id: String,
    #[serde(rename = "e2ee")]
    pub encrypted: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CodeRequest {
    pub status: String,
    pub development: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Device {
    pub device_id: String,
    pub name: Option<String>,
    pub verified: bool,
    pub fingerprint: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DeviceList {
    pub contact: Contact,
    pub current_device_id: String,
    pub current_device_fingerprint: Option<String>,
    pub devices: Vec<Device>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct KeyImport {
    #[serde(rename = "imported")]
    pub imported_count: usize,
    #[serde(rename = "total")]
    pub total_count: usize,
}

#[derive(Default, Serialize, Deserialize)]
pub(crate) struct AddressBook {
    phones: BTreeMap<String, String>, // Matrix user ID -> phone
    #[serde(default)]
    by_phone: BTreeMap<String, String>, // Phone -> pinned Matrix identity
    conversations: BTreeMap<ConversationId, StoredConversation>,
    retries: BTreeMap<String, Value>,
}

#[derive(Serialize, Deserialize)]
struct StoredConversation {
    room: String,
    members: BTreeMap<String, String>,
    membership: Membership,
}

fn opaque(kind: &str, value: &str) -> String {
    format!("{kind}_{:x}", Sha256::digest(value.as_bytes()))
}

pub(crate) fn phone(value: &str) -> Result<&str> {
    let digits = value
        .strip_prefix('+')
        .context("Use an E.164 phone number, e.g. +15555550101")?;
    if !(2..=15).contains(&digits.len())
        || digits.starts_with('0')
        || !digits.bytes().all(|b| b.is_ascii_digit())
    {
        bail!("Use an E.164 phone number, e.g. +15555550101");
    }
    Ok(value)
}

impl AddressBook {
    fn remember_phone(&mut self, user: &str, number: &str) {
        self.phones.insert(user.to_owned(), number.to_owned());
        self.by_phone
            .entry(number.to_owned())
            .or_insert_with(|| user.to_owned());
    }

    fn remember_session(&mut self, session: &Session) {
        if let Some(number) = session.phone() {
            self.remember_phone(&session.user_id, number);
        }
    }

    fn contact(&self, user: &str) -> Contact {
        Contact {
            id: ContactId(opaque("contact", user)),
            phone: self.phones.get(user).cloned(),
        }
    }

    fn conversation(&self, id: &ConversationId, own_user: &str) -> Conversation {
        let stored = &self.conversations[id];
        Conversation {
            id: id.clone(),
            membership: stored.membership,
            participants: stored
                .members
                .iter()
                .filter(|(user, membership)| {
                    user.as_str() != own_user && matches!(membership.as_str(), "join" | "invite")
                })
                .map(|(user, _)| self.contact(user))
                .collect(),
        }
    }

    fn room(&self, id: &ConversationId) -> Result<&str> {
        Ok(&self
            .conversations
            .get(id)
            .context("Unknown conversation; open it or sync first")?
            .room)
    }

    fn remember_room(&mut self, room: &str, membership: Membership) -> ConversationId {
        let id = ConversationId(opaque("conversation", room));
        self.conversations
            .entry(id.clone())
            .or_insert_with(|| StoredConversation {
                room: room.to_owned(),
                members: BTreeMap::new(),
                membership,
            })
            .membership = membership;
        id
    }

    fn message(&mut self, room: &str, event: &Value) -> Option<Message> {
        if event["type"] != "m.room.message" {
            return None;
        }
        let event_id = event["event_id"].as_str()?.to_owned();
        let sender = self.contact(event["sender"].as_str()?);
        let unavailable = event["unable_to_decrypt"] == true;
        if unavailable {
            if let Some(raw) = event.get("encrypted_event") {
                self.retries
                    .insert(event_id.clone(), json!({"room_id":room,"event":raw}));
            }
        } else {
            self.retries.remove(&event_id);
        }
        let content = if unavailable {
            MessageContent::UnableToDecrypt
        } else if event["content"]["msgtype"] == "m.text" {
            MessageContent::Text {
                body: event["content"]["body"].as_str().unwrap_or("").to_owned(),
            }
        } else {
            MessageContent::Unsupported
        };
        Some(Message {
            event_id,
            sender,
            timestamp_ms: event["origin_server_ts"].as_u64().unwrap_or(0),
            content,
            encrypted: unavailable || event["e2ee"] == true,
        })
    }
}

impl Client {
    pub fn request_code(&self, number: &str) -> Result<CodeRequest> {
        Ok(serde_json::from_value(
            self.request_code_raw(phone(number)?)?,
        )?)
    }

    /// Resolve once and persist the room and account mapping in encrypted storage.
    /// Reopening a known, active direct conversation does not call the directory.
    pub fn open_chat(&self, session: &Session, number: &str) -> Result<Conversation> {
        let number = phone(number)?;
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let mut book = crypto.load_addresses()?;
        book.remember_session(session);
        let user = match book.by_phone.get(number) {
            Some(user) => user.clone(),
            None => {
                let user = self.lookup_raw(session, number)?;
                book.remember_phone(&user, number);
                // Preserve a successful lookup even if opening the room fails.
                crypto.save_addresses(&book)?;
                user
            }
        };
        if user == session.user_id {
            bail!("Choose another person");
        }
        if let Some((id, _)) = book.conversations.iter().find(|(_, c)| {
            let active: Vec<_> = c
                .members
                .iter()
                .filter(|(_, m)| matches!(m.as_str(), "join" | "invite"))
                .map(|(u, _)| u.as_str())
                .collect();
            c.membership != Membership::Left
                && active.len() == 2
                && active.contains(&user.as_str())
                && active.contains(&session.user_id.as_str())
        }) {
            return Ok(book.conversation(id, &session.user_id));
        }
        let room = self.open_chat_raw(session, &user)?;
        let id = book.remember_room(&room, Membership::Joined);
        book.conversations.get_mut(&id).unwrap().members = BTreeMap::from([
            (session.user_id.clone(), "join".into()),
            (user, "invite".into()),
        ]);
        crypto.save_addresses(&book)?;
        Ok(book.conversation(&id, &session.user_id))
    }

    pub fn join(&self, session: &Session, conversation: &ConversationId) -> Result<()> {
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let mut book = crypto.load_addresses()?;
        self.join_raw(session, book.room(conversation)?)?;
        let stored = book.conversations.get_mut(conversation).unwrap();
        stored.membership = Membership::Joined;
        stored
            .members
            .insert(session.user_id.clone(), "join".into());
        crypto.save_addresses(&book)
    }

    /// Reuse transaction_id when retrying. This operation never queries the directory.
    pub fn send_text(
        &self,
        session: &Session,
        conversation: &ConversationId,
        body: &str,
        transaction_id: &str,
    ) -> Result<SentMessage> {
        let crypto = self.encrypted(session)?;
        let room = {
            let _guard = crypto.address_lock.lock().unwrap();
            crypto.load_addresses()?.room(conversation)?.to_owned()
        };
        Ok(serde_json::from_value(self.send_text_raw(
            session,
            &room,
            body,
            transaction_id,
        )?)?)
    }

    fn resolve_contacts(
        &self,
        session: &Session,
        book: &mut AddressBook,
        users: Vec<String>,
    ) -> bool {
        let mut users: Vec<_> = users
            .into_iter()
            .filter(|u| !book.phones.contains_key(u))
            .collect();
        users.sort();
        users.dedup();
        let mut pending = false;
        for chunk in users.chunks(100) {
            if let Ok(value) = self.resolve_raw(session, chunk) {
                for user in chunk {
                    if let Some(number) = value["phones"][user].as_str() {
                        book.remember_phone(user, number);
                    } else {
                        pending = true;
                    }
                }
            } else {
                pending = true;
            }
        }
        pending
    }

    /// Receive available messages and conversation changes, not a history archive.
    /// Store received messages locally; a new login cannot restore local history.
    /// Missing message keys are retried internally.
    /// Use one sync worker per session; the SDK persists its own cursor.
    pub fn sync(&self, session: &Session) -> Result<SyncUpdate> {
        let crypto = self.encrypted(session)?;
        let _sync_guard = crypto.typed_sync_lock.lock().unwrap();
        let retries = {
            let _guard = crypto.address_lock.lock().unwrap();
            json!(crypto
                .load_addresses()?
                .retries
                .values()
                .collect::<Vec<_>>())
        };
        let raw = crypto.sync(Some(&retries))?;
        let result = (|| {
            let _guard = crypto.address_lock.lock().unwrap();
            let mut book = crypto.load_addresses()?;
            book.remember_session(session);
            let mut batches = Vec::new();
            let mut users = Vec::new();
            for (key, membership) in [
                ("join", Membership::Joined),
                ("invite", Membership::Invited),
                ("leave", Membership::Left),
            ] {
                if let Some(rooms) = raw["rooms"][key].as_object() {
                    for (room, update) in rooms {
                        let id = book.remember_room(room, membership);
                        let stored = book.conversations.get_mut(&id).unwrap();
                        for part in ["state", "invite_state", "timeline"] {
                            if let Some(events) = update[part]["events"].as_array() {
                                for event in events {
                                    if let Some(user) = event["sender"].as_str() {
                                        users.push(user.to_owned());
                                    }
                                    if event["type"] == "m.room.member" {
                                        if let (Some(user), Some(m)) = (
                                            event["state_key"].as_str(),
                                            event["content"]["membership"].as_str(),
                                        ) {
                                            stored.members.insert(user.to_owned(), m.to_owned());
                                        }
                                    }
                                }
                            }
                        }
                        users.extend(stored.members.keys().cloned());
                        batches.push((id, room.clone(), update.clone()));
                    }
                }
            }
            // Refresh contact labels even when this sync has no new room events.
            users.extend(
                book.conversations
                    .values()
                    .flat_map(|c| c.members.keys().cloned()),
            );
            let old_phones = book.phones.clone();
            let contacts_pending = self.resolve_contacts(session, &mut book, users);
            // Emit label-only updates when a previous directory failure recovers.
            for (id, stored) in &book.conversations {
                if !batches.iter().any(|(existing, _, _)| existing == id)
                    && stored
                        .members
                        .keys()
                        .any(|user| old_phones.get(user) != book.phones.get(user))
                {
                    batches.push((id.clone(), stored.room.clone(), json!({})));
                }
            }
            let conversations = batches
                .into_iter()
                .map(|(id, room, update)| {
                    let messages = update["timeline"]["events"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(|e| book.message(&room, e))
                        .collect();
                    ConversationUpdate {
                        conversation: book.conversation(&id, &session.user_id),
                        messages,
                        limited: update["timeline"]["limited"] == true,
                    }
                })
                .collect();
            crypto.save_addresses(&book)?;
            Ok(SyncUpdate {
                conversations,
                contacts_pending,
            })
        })();
        if result.is_err() {
            crypto.requeue(raw);
        }
        result
    }

    /// Read cached conversations without a directory lookup.
    pub fn conversations(&self, session: &Session) -> Result<Vec<Conversation>> {
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let book = crypto.load_addresses()?;
        Ok(book
            .conversations
            .keys()
            .map(|id| book.conversation(id, &session.user_id))
            .collect())
    }

    /// None selects this account; Some selects a contact by phone number.
    pub fn devices(&self, session: &Session, number: Option<&str>) -> Result<DeviceList> {
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let mut book = crypto.load_addresses()?;
        book.remember_session(session);
        let user = if let Some(number) = number {
            let number = phone(number)?;
            match book.by_phone.get(number) {
                Some(user) => user.clone(),
                None => {
                    let user = self.lookup_raw(session, number)?;
                    book.remember_phone(&user, number);
                    crypto.save_addresses(&book)?;
                    user
                }
            }
        } else {
            session.user_id.clone()
        };
        let raw = crypto.devices(&user)?;
        Ok(DeviceList {
            contact: book.contact(&user),
            current_device_id: session.device_id.clone(),
            current_device_fingerprint: raw["fingerprint"].as_str().map(str::to_owned),
            devices: serde_json::from_value(raw["devices"].clone())?,
        })
    }

    pub fn export_keys(
        &self,
        session: &Session,
        path: impl AsRef<Path>,
        passphrase: &str,
    ) -> Result<()> {
        self.encrypted(session)?
            .export_keys(path.as_ref().into(), passphrase)?;
        Ok(())
    }

    pub fn import_keys(
        &self,
        session: &Session,
        path: impl AsRef<Path>,
        passphrase: &str,
    ) -> Result<KeyImport> {
        Ok(serde_json::from_value(
            self.encrypted(session)?
                .import_keys(path.as_ref().into(), passphrase)?,
        )?)
    }

    pub fn logout(&self, session: &Session) -> Result<()> {
        self.logout_raw(session)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> (Session, AddressBook, ConversationId) {
        let session = Session {
            homeserver: "http://127.0.0.1:9".into(),
            user_id: "@alice:example.org".into(),
            access_token: "test".into(),
            device_id: "TEST".into(),
            phone: Some("+15555550101".into()),
        };
        let mut book = AddressBook::default();
        book.remember_phone("@bob:example.org", "+15555550102");
        let id = book.remember_room("!room:example.org", Membership::Joined);
        book.conversations.get_mut(&id).unwrap().members = BTreeMap::from([
            (session.user_id.clone(), "join".into()),
            ("@bob:example.org".into(), "join".into()),
        ]);
        (session, book, id)
    }

    #[test]
    fn typed_events_hide_protocol_and_preserve_decryption_retries() {
        let (session, mut book, id) = fixture();
        let encrypted = json!({"type":"m.room.encrypted","event_id":"$message",
            "sender":"@bob:example.org","origin_server_ts":123,"content":{"ciphertext":"secret"}});
        let unavailable = json!({"type":"m.room.message","event_id":"$message",
            "sender":"@bob:example.org","origin_server_ts":123,"unable_to_decrypt":true,
            "encrypted_event":encrypted});
        let message = book.message("!room:example.org", &unavailable).unwrap();
        assert_eq!(message.content, MessageContent::UnableToDecrypt);
        assert!(message.encrypted);
        assert_eq!(book.retries["$message"]["event"], encrypted);
        // Retry metadata survives process/store reloads.
        let mut book: AddressBook =
            serde_json::from_slice(&serde_json::to_vec(&book).unwrap()).unwrap();
        let decrypted = json!({"type":"m.room.message","event_id":"$message",
            "sender":"@bob:example.org","origin_server_ts":123,"e2ee":true,
            "content":{"msgtype":"m.text","body":"Hello"}});
        let message = book.message("!room:example.org", &decrypted).unwrap();
        assert!(book.retries.is_empty());
        assert_eq!(message.sender.phone.as_deref(), Some("+15555550102"));
        assert_eq!(message.timestamp_ms, 123);
        assert_eq!(
            message.content,
            MessageContent::Text {
                body: "Hello".into()
            }
        );
        let serialized =
            serde_json::to_string(&(book.conversation(&id, &session.user_id), message)).unwrap();
        assert!(!serialized.contains("@bob"));
        assert!(!serialized.contains("!room"));
        assert!(!serialized.contains("ciphertext"));
        assert!(!serialized.contains("m.room"));
    }

    #[test]
    fn unresolved_accounts_remain_distinct_and_non_messages_are_ignored() {
        let mut book = AddressBook::default();
        let unknown = book.contact("@unknown:elsewhere.org");
        assert_eq!(unknown.phone, None);
        assert_ne!(unknown.id, book.contact("@other:elsewhere.org").id);
        assert!(book
            .message("!room:x", &json!({"type":"m.room.member"}))
            .is_none());
        let redacted = book.message("!room:x", &json!({
            "type":"m.room.message", "event_id":"$redacted", "sender":"@unknown:elsewhere.org", "content":{}
        })).unwrap();
        assert_eq!(redacted.content, MessageContent::Unsupported);
        assert!(!redacted.encrypted);
    }

    #[test]
    fn resolving_a_reassigned_number_does_not_retarget_existing_contacts() {
        let (_, mut book, _) = fixture();
        book.remember_phone("@a-new-owner:example.org", "+15555550102");
        assert_eq!(book.by_phone["+15555550102"], "@bob:example.org");
        assert_ne!(
            book.contact("@bob:example.org").id,
            book.contact("@a-new-owner:example.org").id
        );
    }

    #[test]
    fn phone_inputs_are_canonical() {
        for invalid in [
            "@bob:example.org",
            "5551234",
            "+01234",
            "+1 555",
            "+１２３",
            "+1234567890123456",
        ] {
            assert!(phone(invalid).is_err(), "{invalid}");
        }
        assert!(phone("+15555550102").is_ok());
    }

    #[test]
    fn cached_conversation_survives_encrypted_sqlite_reload_without_directory() -> Result<()> {
        let _runtime_guard = crate::encryption::runtime().enter();
        let (session, book, id) = fixture();
        let root = std::env::temp_dir().join(format!("dumber-api-test-{}", uuid::Uuid::new_v4()));
        let password = "test-only-persistent-password-1234567890";
        let encrypted = crate::encryption::offline_test_client(&session, &root, password)?;
        encrypted.save_addresses(&book)?;
        let client = Client::new("http://127.0.0.1:9")?.with_crypto_store(&root, password);
        let first = client.open_chat(&session, "+15555550102")?;
        assert_eq!(first.id, id);
        assert!(client
            .open_chat(&session, "+15555550101")
            .unwrap_err()
            .to_string()
            .contains("Choose another person"));
        // Rebuild the SDK and import the persisted cipher, as a new process would.
        let reopened = crate::encryption::offline_test_client(&session, &root, password)?;
        let restored = client.open_chat(&session, "+15555550102")?;
        assert_eq!(first, restored);
        assert_eq!(client.conversations(&session)?[0], restored);
        reopened.requeue(json!({
            "next_batch":"internal-cursor", "rooms":{
                "join":{"!room:example.org":{
                    "timeline":{"limited":true,"prev_batch":"history-cursor","events":[{
                        "type":"m.room.message","event_id":"$test","sender":"@bob:example.org",
                        "origin_server_ts":42,"e2ee":true,"content":{"msgtype":"m.text","body":"Hello"}
                    }]}
                }},
                "invite":{"!invite:example.org":{"invite_state":{"events":[
                    {"type":"m.room.member","state_key":"@carol:example.org","content":{"membership":"join"}},
                    {"type":"m.room.member","state_key":"@alice:example.org","content":{"membership":"invite"}}
                ]}}},
                "leave":{"!left:example.org":{}}
            }
        }));
        let sync = client.sync(&session)?;
        assert!(
            sync.contacts_pending,
            "An unavailable directory must not discard messages"
        );
        assert_eq!(sync.conversations.len(), 3);
        let joined = sync
            .conversations
            .iter()
            .find(|u| u.conversation.id == id)
            .unwrap();
        assert!(joined.limited);

        assert_eq!(
            joined.messages[0].content,
            MessageContent::Text {
                body: "Hello".into()
            }
        );
        assert!(sync
            .conversations
            .iter()
            .any(|u| u.conversation.membership == Membership::Invited
                && u.conversation.participants[0].phone.is_none()));
        assert!(sync
            .conversations
            .iter()
            .any(|u| u.conversation.membership == Membership::Left));
        let wire = serde_json::to_string(&sync)?;
        assert!(!wire.contains("example.org"));
        assert!(!wire.contains("internal-cursor"));
        assert!(!wire.contains("history-cursor"));
        assert!(!wire.contains("history_cursor"));
        assert!(!wire.contains("prev_batch"));
        // Unknown IDs are rejected locally rather than forwarded to Matrix.
        assert!(client
            .send_text(&session, &ConversationId("unknown".into()), "Hello", "txn")
            .unwrap_err()
            .to_string()
            .contains("Unknown conversation"));
        assert!(Client::new("http://127.0.0.1:9")?
            .with_crypto_store(&root, "wrong-password-at-least-32-characters")
            .conversations(&session)
            .is_err());
        // Plain metadata must not be present even in SQLite WAL files.
        fn inspect(path: &Path) -> Result<()> {
            for entry in std::fs::read_dir(path)? {
                let path = entry?.path();
                if path.is_dir() {
                    inspect(&path)?;
                } else {
                    let bytes = std::fs::read(&path)?;
                    for secret in [
                        b"+15555550102".as_slice(),
                        b"@bob:example.org",
                        b"!room:example.org",
                    ] {
                        assert!(
                            !bytes.windows(secret.len()).any(|s| s == secret),
                            "{path:?}"
                        );
                    }
                }
            }
            Ok(())
        }
        inspect(&root)?;
        std::fs::remove_dir_all(root)?;
        Ok(())
    }
}
