//! Group metadata is durable and independent of the current participant count.
use crate::{api::AddressBook, segment, Client, Contact, Session};
use anyhow::{bail, Context, Result};
use reqwest::Method;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const GROUP_TYPE: &str = "chat.dumber.group";

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConversationKind {
    Direct,
    Group,
    #[default]
    Unknown,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct GroupCapabilities {
    pub manage: bool,
    pub send: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GroupMember {
    pub contact: Contact,
    pub membership: crate::Membership,
    pub admin: bool,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub(crate) struct RoomMetadata {
    pub membership: String,
    pub members: BTreeMap<String, String>,
    pub name: Option<String>,
    create: Option<Value>,
    power: Value,
    group: bool,
}
impl RoomMetadata {
    pub fn kind(&self) -> ConversationKind {
        if self.group {
            ConversationKind::Group
        } else if self.create.is_some()
            && self
                .members
                .values()
                .filter(|m| matches!(m.as_str(), "join" | "invite"))
                .count()
                == 2
        {
            ConversationKind::Direct
        } else {
            ConversationKind::Unknown
        }
    }
    pub fn is_admin(&self, user: &str) -> bool {
        // Modern room versions give the creator implicit privileges.
        let creator = self.create.as_ref().and_then(|c| c["creator"].as_str());
        let implicit_creator = self
            .create
            .as_ref()
            .and_then(|c| c["room_version"].as_str())
            .and_then(|v| v.parse::<u64>().ok())
            .is_some_and(|v| v >= 12);
        (implicit_creator && creator == Some(user))
            || self.power["users"][user]
                .as_i64()
                .unwrap_or_else(|| self.power["users_default"].as_i64().unwrap_or(0))
                >= 100
    }
    pub fn capabilities(&self, own: &str) -> GroupCapabilities {
        let joined = self.membership == "join";
        GroupCapabilities {
            manage: joined && self.is_admin(own),
            send: joined,
        }
    }
    fn observe(&mut self, event: &Value) {
        let c = &event["content"];
        match event["type"].as_str() {
            Some("m.room.create") => {
                let mut c = c.clone();
                if c.get("creator").is_none() {
                    c["creator"] = event["sender"].clone();
                }
                self.group |= c["type"] == GROUP_TYPE;
                self.create = Some(c);
            }
            Some("m.room.name") => self.name = c["name"].as_str().map(str::to_owned),
            Some("m.room.power_levels") => self.power = c.clone(),
            Some("m.room.member") => {
                if let (Some(user), Some(membership)) =
                    (event["state_key"].as_str(), c["membership"].as_str())
                {
                    self.members.insert(user.into(), membership.into());
                    self.group |= self
                        .members
                        .values()
                        .filter(|m| matches!(m.as_str(), "join" | "invite"))
                        .count()
                        > 2;
                }
            }
            _ => (),
        }
    }
    fn raw(&self, room: &str, own: &str, book: &AddressBook) -> Value {
        json!({"room_id": room, "kind": self.kind(), "name": self.name, "membership": self.membership,
            "capabilities": self.capabilities(own), "members": self.members.iter().map(|(user, membership)| {
                json!({"user_id": user, "membership": membership, "phone": book.phones.get(user), "admin": self.is_admin(user)})
            }).collect::<Vec<_>>()})
    }
}

pub(crate) fn absorb(book: &mut AddressBook, raw: &Value) {
    for membership in ["join", "invite", "leave"] {
        if let Some(rooms) = raw["rooms"][membership].as_object() {
            for (room, update) in rooms {
                let info = book.room_metadata.entry(room.clone()).or_default();
                info.membership = membership.into();
                for part in ["state", "invite_state", "timeline"] {
                    if let Some(events) = update[part]["events"].as_array() {
                        for event in events {
                            info.observe(event);
                        }
                    }
                }
            }
        }
    }
}

/// Restarts can resume a cursor without receiving another full room snapshot.
pub(crate) fn hydrate(
    book: &mut AddressBook,
    crypto: &crate::encryption::EncryptedClient,
) -> Result<()> {
    for (room, info) in &mut book.room_metadata {
        if info.create.is_none() {
            for event in crypto.cached_room_state(room)? {
                info.observe(&event);
            }
        }
    }
    Ok(())
}

impl Client {
    pub(crate) fn room_info_raw(
        &self,
        session: &Session,
        room: &str,
        refresh: bool,
    ) -> Result<Value> {
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let mut book = crypto.load_addresses()?;
        if refresh || !book.room_metadata.contains_key(room) {
            let events = self.matrix(
                session,
                Method::GET,
                &format!("/rooms/{}/state", segment(room)),
                None,
            )?;
            let info = book.room_metadata.entry(room.into()).or_default();
            for event in events.as_array().context("Missing room state")? {
                info.observe(event);
            }
            info.membership = info
                .members
                .get(&session.user_id)
                .cloned()
                .unwrap_or_default();
            crypto.save_addresses(&book)?;
        }
        Ok(book
            .room_metadata
            .get(room)
            .cloned()
            .unwrap_or_default()
            .raw(room, &session.user_id, &book))
    }

    fn group_peer(&self, session: &Session, number: &str) -> Result<String> {
        let number = crate::api::phone(number)?;
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let mut book = crypto.load_addresses()?;
        if let Some(user) = book.by_phone.get(number) {
            return Ok(user.clone());
        }
        let user = self.lookup_raw(session, number).map_err(|error| {
            if error.downcast_ref::<crate::HttpFailure>().is_some_and(|e| e.status == 404) {
                crate::Failure {
                    kind: crate::ErrorKind::RecipientUnavailable,
                    message: format!("{number} is not available on dumber.chat. Ask them to activate dumber.chat in Messaging settings."),
                    retry_after_seconds: None,
                }.into()
            } else {
                error
            }
        })?;
        book.remember_phone(&user, number);
        crypto.save_addresses(&book)?;
        Ok(user)
    }

    pub(crate) fn create_group_raw(
        &self,
        session: &Session,
        name: &str,
        numbers: &[String],
        operation: &str,
    ) -> Result<Value> {
        if name.trim().is_empty() || name.chars().count() > 100 {
            bail!("Use a group name of 1–100 characters");
        }
        if operation.is_empty() || operation.len() > 128 {
            bail!("A persistent operation ID is required");
        }
        // Resolve all chosen people before any room mutation. Never silently drop failures.
        let mut users = BTreeSet::new();
        for number in numbers {
            users.insert(self.group_peer(session, number)?);
        }
        users.remove(&session.user_id);
        let input = json!({"name": name.trim(), "users": users});
        let crypto = self.encrypted(session)?;
        // Serialize creation retries, across all bindings.
        let room = {
            let _guard = crypto.address_lock.lock().unwrap();
            let mut book = crypto.load_addresses()?;
            if let Some(previous) = book.creations.get(operation) {
                if previous["input"] != input {
                    bail!("This operation ID belongs to a different group");
                }
            }
            let domain = session
                .user_id
                .split_once(':')
                .context("Invalid account ID")?
                .1;
            let alias_local = format!(
                "dc_group_{:x}",
                Sha256::digest(format!("{}\0{operation}", session.user_id))
            );
            let alias = format!("#{alias_local}:{domain}");
            let lookup = || {
                self.matrix(
                    session,
                    Method::GET,
                    &format!("/directory/room/{}", segment(&alias)),
                    None,
                )
            };
            let existing = if book.creations.contains_key(operation) {
                match lookup() {
                    Ok(result) => Some(result),
                    Err(e)
                        if e.downcast_ref::<crate::HttpFailure>()
                            .is_some_and(|e| e.status == 404) =>
                    {
                        None
                    }
                    Err(e) => return Err(e),
                }
            } else {
                None
            };
            book.creations
                .insert(operation.into(), json!({"input": input}));
            crypto.save_addresses(&book)?;
            let result = match existing {
                Some(result) => result,
                None => match self.matrix(session, Method::POST, "/createRoom", Some(json!({
                    "preset":"private_chat", "visibility":"private", "is_direct":false,
                    "room_alias_name":alias_local, "name":name.trim(), "invite":users,
                    "creation_content":{"m.federate":false,"type":GROUP_TYPE},
                    "power_level_content_override":{"invite":100,"kick":100,"ban":100,"state_default":100,"events_default":0},
                    "initial_state":[
                        {"type":"m.room.encryption","state_key":"","content":{"algorithm":"m.megolm.v1.aes-sha2"}},
                        {"type":"m.room.history_visibility","state_key":"","content":{"history_visibility":"joined"}},
                        {"type":"m.room.guest_access","state_key":"","content":{"guest_access":"forbidden"}}
                    ]
                }))) {
                    Ok(result) => result,
                    Err(error) => { if let Ok(found) = lookup() { found } else { return Err(error); } }
                }
            };
            result["room_id"]
                .as_str()
                .context("Missing room ID")?
                .to_owned()
        };
        self.room_info_raw(session, &room, true)
    }

    pub(crate) fn group_invite_raw(
        &self,
        session: &Session,
        room: &str,
        number: &str,
    ) -> Result<Value> {
        self.require_group_admin(session, room)?;
        let user = self.group_peer(session, number)?;
        self.matrix(
            session,
            Method::POST,
            &format!("/rooms/{}/invite", segment(room)),
            Some(json!({"user_id":user})),
        )?;
        self.room_info_raw(session, room, true)
    }

    fn require_group_admin(&self, session: &Session, room: &str) -> Result<Value> {
        let info = self.room_info_raw(session, room, true)?;
        if info["kind"] != "group" || info["capabilities"]["manage"] != true {
            bail!("Group admin permission required");
        }
        Ok(info)
    }

    pub(crate) fn group_action_raw(
        &self,
        session: &Session,
        room: &str,
        action: &str,
        value: &str,
    ) -> Result<Value> {
        if action == "leave" {
            self.matrix(
                session,
                Method::POST,
                &format!("/rooms/{}/leave", segment(room)),
                Some(json!({})),
            )?;
            let crypto = self.encrypted(session)?;
            let _guard = crypto.address_lock.lock().unwrap();
            let mut book = crypto.load_addresses()?;
            let info = book.room_metadata.entry(room.into()).or_default();
            info.membership = "leave".into();
            info.members.insert(session.user_id.clone(), "leave".into());
            crypto.save_addresses(&book)?;
            return Ok(info_value(&book, room, &session.user_id));
        }
        let info = self.require_group_admin(session, room)?;
        match action {
            "group_rename" => {
                if value.trim().is_empty() || value.chars().count() > 100 {
                    bail!("Use a group name of 1–100 characters");
                }
                self.matrix(
                    session,
                    Method::PUT,
                    &format!("/rooms/{}/state/m.room.name/", segment(room)),
                    Some(json!({"name":value.trim()})),
                )?;
            }
            "group_remove" => {
                if value == session.user_id {
                    bail!("Use leave to leave the group");
                }
                self.matrix(
                    session,
                    Method::POST,
                    &format!("/rooms/{}/kick", segment(room)),
                    Some(json!({"user_id":value})),
                )?;
            }
            "group_admin" => {
                if !info["members"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|m| m["user_id"] == value && m["membership"] == "join")
                {
                    bail!("Choose a joined member");
                }
                let path = format!("/rooms/{}/state/m.room.power_levels/", segment(room));
                let mut power = self.matrix(session, Method::GET, &path, None)?;
                if !power["users"].is_object() {
                    power["users"] = json!({});
                }
                power["users"][value] = json!(100);
                self.matrix(session, Method::PUT, &path, Some(power))?;
            }
            _ => bail!("Unknown group action"),
        }
        self.room_info_raw(session, room, true)
    }
}
fn info_value(book: &AddressBook, room: &str, own: &str) -> Value {
    book.room_metadata[room].raw(room, own, book)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn creation_retry_management_and_direct_chat_isolation_cross_http() -> Result<()> {
        creation_roundtrip(false)
    }

    #[test]
    fn creator_only_group_retries_and_remains_a_group() -> Result<()> {
        creation_roundtrip(true)
    }

    fn creation_roundtrip(solo: bool) -> Result<()> {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0")?;
        let base = format!("http://{}", listener.local_addr()?);
        let server = std::thread::spawn(move || {
            let mut name = "Friends".to_owned();
            let mut group_creations = 0;
            for _ in 0..11 {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = Vec::new();
                let mut buffer = [0; 4096];
                loop {
                    let count = socket.read(&mut buffer).unwrap();
                    assert!(count > 0);
                    request.extend_from_slice(&buffer[..count]);
                    if let Some(end) = request.windows(4).position(|v| v == b"\r\n\r\n") {
                        let headers = String::from_utf8_lossy(&request[..end]);
                        let length: usize = headers
                            .lines()
                            .find_map(|l| {
                                l.to_lowercase()
                                    .strip_prefix("content-length:")
                                    .map(|v| v.trim().parse().unwrap())
                            })
                            .unwrap_or(0);
                        if request.len() >= end + 4 + length {
                            break;
                        }
                    }
                }
                let request = String::from_utf8(request).unwrap();
                let line = request.lines().next().unwrap();
                let body = request.split_once("\r\n\r\n").unwrap().1;
                let response = if line.contains("POST /_matrix/client/v3/createRoom") {
                    let body: Value = serde_json::from_str(body).unwrap();
                    if body["creation_content"]["type"] == GROUP_TYPE {
                        group_creations += 1;
                        assert_eq!(
                            body["invite"].as_array().unwrap().len(),
                            if solo { 0 } else { 2 }
                        );
                        assert_eq!(body["power_level_content_override"]["invite"], 100);
                        assert!(body["initial_state"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .any(|e| e["type"] == "m.room.history_visibility"
                                && e["content"]["history_visibility"] == "joined"));
                        json!({"room_id":"!group:x"})
                    } else {
                        assert_eq!(body["is_direct"], true);
                        json!({"room_id":"!direct:x"})
                    }
                } else if line.contains("/directory/room/") {
                    json!({"room_id":"!group:x"})
                } else if line.contains("/joined_rooms") {
                    json!({"joined_rooms":["!group:x"]})
                } else if line.starts_with("PUT ") {
                    name = serde_json::from_str::<Value>(body).unwrap()["name"]
                        .as_str()
                        .unwrap()
                        .to_owned();
                    json!({})
                } else if line.contains("/leave ") {
                    json!({})
                } else {
                    assert!(line.contains("/state "));
                    let mut events = json!([
                        {"type":"m.room.create","sender":"@a:x","content":{"type":GROUP_TYPE,"m.federate":false,"room_version":"12"}},
                        {"type":"m.room.name","content":{"name":name}},
                        {"type":"m.room.member","state_key":"@a:x","content":{"membership":"join"}},
                        {"type":"m.room.member","state_key":"@b:x","content":{"membership":"join"}},
                        {"type":"m.room.member","state_key":"@c:x","content":{"membership":"leave"}}
                    ]);
                    if solo {
                        events.as_array_mut().unwrap().truncate(3);
                    }
                    events
                };
                let body = response.to_string();
                write!(socket,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
            }
            assert_eq!(group_creations, 1);
        });
        let session: Session = serde_json::from_value(
            json!({"homeserver":base,"user_id":"@a:x","access_token":"test","device_id":"group-test"}),
        )?;
        let root = std::env::temp_dir().join(format!("group-api-{}", uuid::Uuid::new_v4()));
        let password = "group-test-password-at-least-32-characters";
        let crypto = crate::encryption::offline_test_client(&session, &root, password)?;
        let mut book = AddressBook::default();
        book.remember_phone("@b:x", "+15555550102");
        book.remember_phone("@c:x", "+15555550103");
        crypto.save_addresses(&book)?;
        let client = Client::new(&base)?.with_crypto_store(&root, password);
        let numbers = if solo {
            vec![]
        } else {
            vec!["+15555550102".into(), "+15555550103".into()]
        };
        let group = client.create_group(&session, "Friends", &numbers, "stable-operation")?;
        assert_eq!(group.kind, ConversationKind::Group);
        assert!(group.capabilities.manage);
        assert!(group.capabilities.send);
        if solo {
            assert!(group.participants.is_empty());
            assert_eq!(group.members.len(), 1);
        }
        assert_eq!(
            group,
            client.create_group(&session, "Friends", &numbers, "stable-operation")?
        );
        assert_eq!(client.open_chat_raw(&session, "@b:x")?, "!direct:x");
        client.rename_group(&session, &group.id, "Family")?;
        client.leave(&session, &group.id)?;
        assert_eq!(
            client.conversations(&session)?[0].membership,
            crate::Membership::Left
        );
        server.join().unwrap();
        std::fs::remove_dir_all(root)?;
        Ok(())
    }

    #[test]
    fn participant_lookup_errors_are_actionable_without_exposing_server_details() -> Result<()> {
        use std::io::{BufRead, BufReader, Write};
        for (status, expected) in [
            (404, crate::ErrorKind::RecipientUnavailable),
            (429, crate::ErrorKind::RateLimited),
        ] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0")?;
            let base = format!("http://{}", listener.local_addr()?);
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut reader = BufReader::new(&mut socket);
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                assert!(line.starts_with("POST /api/directory/lookup "));
                loop {
                    line.clear();
                    assert!(reader.read_line(&mut line).unwrap() > 0);
                    if line == "\r\n" {
                        break;
                    }
                }
                let body = json!({"detail":"private server diagnostics"}).to_string();
                write!(socket, "HTTP/1.1 {status} Error\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            });
            let session: Session = serde_json::from_value(json!({
                "homeserver":base,"user_id":"@a:x","access_token":"test","device_id":"lookup-test"
            }))?;
            let root = std::env::temp_dir().join(format!("group-lookup-{}", uuid::Uuid::new_v4()));
            let password = "group-test-password-at-least-32-characters";
            let _crypto = crate::encryption::offline_test_client(&session, &root, password)?;
            let client = Client::new(&base)?.with_crypto_store(&root, password);
            let error = client.group_peer(&session, "+15555550102").unwrap_err();
            let failure = crate::Failure::from_error(&error);
            assert_eq!(failure.kind, expected);
            assert!(!failure.message.contains("private server diagnostics"));
            if status == 404 {
                assert!(failure.message.contains("+15555550102"));
                assert!(failure.message.contains("activate dumber.chat"));
            }
            server.join().unwrap();
            std::fs::remove_dir_all(root)?;
        }
        Ok(())
    }

    #[test]
    fn group_identity_and_permissions_survive_leaves_and_reload() {
        let mut room = RoomMetadata {
            membership: "join".into(),
            ..Default::default()
        };
        room.observe(
            &json!({"type":"m.room.create","sender":"@a:x","content":{"type":GROUP_TYPE,"room_version":"12"}}),
        );
        room.observe(
            &json!({"type":"m.room.member","state_key":"@b:x","content":{"membership":"join"}}),
        );
        room.observe(
            &json!({"type":"m.room.member","state_key":"@b:x","content":{"membership":"leave"}}),
        );
        let room: RoomMetadata =
            serde_json::from_str(&serde_json::to_string(&room).unwrap()).unwrap();
        assert_eq!(room.kind(), ConversationKind::Group);
        assert!(room.capabilities("@a:x").manage);
        assert!(!room.capabilities("@b:x").manage);
        assert_eq!(RoomMetadata::default().kind(), ConversationKind::Unknown);
    }
}
