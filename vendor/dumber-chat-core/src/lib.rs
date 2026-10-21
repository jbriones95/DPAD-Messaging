//! Synchronous phone-number messaging with typed, serializable results.
//! Run calls on worker threads. Persist sessions securely and retain the crypto store.
//! The application owns sent and received message history; there is no server
//! history-fetch API. This API contract does not itself enforce backend retention.
//!
//! ```no_run
//! use dumber_chat_core::{Client, MessageContent};
//! # fn example(store_password: &str, sms_code: &str) -> anyhow::Result<()> {
//! let client = Client::new("https://nokings.chat")?
//!     .with_crypto_store("/private/chat", store_password);
//! client.request_code("+46701234567")?;
//! let session = client.verify("+46701234567", sms_code)?;
//! let conversation = client.open_chat(&session, "+46707654321")?;
//! client.send_text(&session, &conversation.id, "Hello!", "unique-transaction-id")?;
//! for update in client.sync(&session)?.conversations {
//!     for message in update.messages {
//!         if let MessageContent::Text { body } = message.content {
//!             // Store/display body and deduplicate using message.event_id.
//!         }
//!     }
//! }
//! # Ok(())
//! # }
//! ```
use anyhow::{bail, Context, Result};
use reqwest::{blocking::Client as Http, Method};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
mod api;
mod catchup;
mod encryption;
pub use api::*;

/// The server rejected this message because the recipient has no registered devices.
/// This does not confirm the outcome of an earlier, ambiguous send attempt.
#[derive(Debug)]
pub struct RecipientUnavailable;
impl std::fmt::Display for RecipientUnavailable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Recipient no longer available on dumber.chat")
    }
}
impl std::error::Error for RecipientUnavailable {}
pub const RECIPIENT_UNAVAILABLE: &str = "DUMBER_RECIPIENT_UNAVAILABLE";

#[derive(Debug)]
pub struct HttpFailure {
    pub status: u16,
    pub detail: Value,
    pub retry_after_seconds: Option<u64>,
    pub error_code: Option<String>,
}

impl std::fmt::Display for HttpFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.status, self.detail)
    }
}
impl std::error::Error for HttpFailure {}

fn error_response(error: &anyhow::Error) -> Value {
    let mut response = json!({"ok":false,"error":error.to_string()});
    if let Some(http) = error.downcast_ref::<HttpFailure>() {
        response["http_status"] = json!(http.status);
        if let Some(code) = &http.error_code {
            response["errcode"] = json!(code);
        }
        if let Some(seconds) = http.retry_after_seconds {
            response["retry_after_seconds"] = json!(seconds);
        }
    }
    if error.is::<RecipientUnavailable>() {
        response["http_status"] = json!(403);
        response["errcode"] = json!(RECIPIENT_UNAVAILABLE);
    }
    response
}

/// Opaque login credentials. Serialize for secure storage; never log the result.
/// Deserialize a previously saved session to restore it.
#[derive(Clone, Serialize, Deserialize)]
pub struct Session {
    homeserver: String,
    user_id: String,
    access_token: String,
    device_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    phone: Option<String>,
}

impl Session {
    /// The login phone number. Older serialized sessions may not contain it.
    pub fn phone(&self) -> Option<&str> {
        self.phone.as_deref()
    }
}

pub struct Client {
    service: String,
    http: Http,
    crypto_store: Option<(PathBuf, String)>,
}

fn segment(value: &str) -> String {
    let mut url = reqwest::Url::parse("https://localhost/").unwrap();
    url.path_segments_mut().unwrap().push(value);
    url.path().trim_start_matches('/').to_owned()
}

impl Client {
    pub fn new(service: &str) -> Result<Self> {
        let url = reqwest::Url::parse(service)?;
        if url.scheme() != "https"
            && !(url.scheme() == "http"
                && matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "10.0.2.2")))
        {
            bail!("Use HTTPS (HTTP is permitted only for local development)");
        }
        Ok(Self {
            service: service.trim_end_matches('/').to_owned(),
            crypto_store: None,
            http: Http::builder().timeout(Duration::from_secs(40)).build()?,
        })
    }

    /// Configure persistent, encrypted device storage. Keep its password across launches.
    pub fn with_crypto_store(mut self, path: impl AsRef<Path>, password: &str) -> Self {
        self.crypto_store = Some((path.as_ref().to_owned(), password.to_owned()));
        self
    }
    fn encrypted(&self, session: &Session) -> Result<Arc<encryption::EncryptedClient>> {
        let (path, password) = self
            .crypto_store
            .as_ref()
            .context("Persistent encryption storage is required; refusing plaintext messaging")?;
        encryption::client(session, path, password)
    }

    fn request(
        &self,
        method: Method,
        url: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> Result<Value> {
        let mut req = self.http.request(method, url);
        if let Some(token) = token {
            req = req.bearer_auth(token);
        }
        if let Some(body) = body {
            req = req.json(&body);
        }
        let response = req.send()?;
        let status = response.status();
        let retry_after = response
            .headers()
            .get("Retry-After")
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.parse::<u64>().ok());
        let data: Value = response.json().context("Invalid server response")?;
        if !status.is_success() {
            return Err(HttpFailure {
                status: status.as_u16(),
                error_code: data["errcode"].as_str().map(str::to_owned),
                detail: data
                    .get("detail")
                    .or(data.get("error"))
                    .cloned()
                    .unwrap_or(json!("Request failed")),
                retry_after_seconds: retry_after
                    .or_else(|| data["retry_after_seconds"].as_u64())
                    .or_else(|| data["retry_after_ms"].as_u64().map(|ms| ms.div_ceil(1000))),
            }
            .into());
        }
        Ok(data)
    }

    fn matrix(
        &self,
        session: &Session,
        method: Method,
        path: &str,
        body: Option<Value>,
    ) -> Result<Value> {
        self.request(
            method,
            &format!(
                "{}/_matrix/client/v3{}",
                session.homeserver.trim_end_matches('/'),
                path
            ),
            Some(&session.access_token),
            body,
        )
    }

    fn request_code_raw(&self, phone: &str) -> Result<Value> {
        self.request(
            Method::POST,
            &format!("{}/api/auth/request-code", self.service),
            None,
            Some(json!({"phone":phone,"protocol":2})),
        )
    }

    pub fn verify(&self, phone: &str, code: &str) -> Result<Session> {
        let phone = api::phone(phone)?;
        let mut session: Session = serde_json::from_value(self.request(
            Method::POST,
            &format!("{}/api/auth/verify", self.service),
            None,
            Some(json!({"phone":phone,"code":code,"protocol":2})),
        )?)?;
        session.phone = Some(phone.to_owned());
        Ok(session)
    }

    fn lookup_raw(&self, session: &Session, phone: &str) -> Result<String> {
        let result = self.request(
            Method::POST,
            &format!("{}/api/directory/lookup", self.service),
            Some(&session.access_token),
            Some(json!({"phone":phone})),
        )?;
        Ok(result["user_id"]
            .as_str()
            .context("Missing user ID")?
            .to_owned())
    }

    fn resolve_raw(&self, session: &Session, user_ids: &[String]) -> Result<Value> {
        self.request(
            Method::POST,
            &format!("{}/api/directory/resolve", self.service),
            Some(&session.access_token),
            Some(json!({"user_ids":user_ids})),
        )
    }

    fn open_chat_raw(&self, session: &Session, user_id: &str) -> Result<String> {
        let crypto = self.encrypted(session)?;
        if user_id == session.user_id {
            bail!("Choose another person");
        }
        // Do not use initial /sync for discovery: Synapse can cache it briefly,
        // so it may omit a room created immediately before this request.
        let joined = self.matrix(session, Method::GET, "/joined_rooms", None)?;
        if let Some(rooms) = joined["joined_rooms"].as_array() {
            for id in rooms.iter().filter_map(Value::as_str) {
                let state = self.matrix(
                    session,
                    Method::GET,
                    &format!("/rooms/{}/state", segment(id)),
                    None,
                )?;
                let Some(events) = state.as_array() else {
                    continue;
                };
                let mut members = std::collections::BTreeMap::new();

                for event in events {
                    if event["type"] == "m.room.member" {
                        if let Some(key) = event["state_key"].as_str() {
                            members
                                .insert(key, event["content"]["membership"].as_str().unwrap_or(""));
                        }
                    }
                }
                let active: Vec<_> = members
                    .iter()
                    .filter(|(_, m)| **m == "join" || **m == "invite")
                    .map(|(u, _)| *u)
                    .collect();
                if active.len() == 2
                    && active.contains(&user_id)
                    && active.contains(&session.user_id.as_str())
                {
                    crypto.enable(id)?;
                    return Ok(id.to_owned());
                }
            }
        }
        let result = self.matrix(session, Method::POST, "/createRoom", Some(json!({
            "preset":"private_chat", "is_direct":true, "invite":[user_id],
            "creation_content":{"m.federate":false},
            "initial_state":[{"type":"m.room.encryption","state_key":"","content":{"algorithm":"m.megolm.v1.aes-sha2"}}, {"type":"m.room.history_visibility","state_key":"","content":{"history_visibility":"invited"}}]
        })))?;
        Ok(result["room_id"]
            .as_str()
            .context("Missing room ID")?
            .to_owned())
    }

    fn join_raw(&self, session: &Session, room: &str) -> Result<Value> {
        self.matrix(
            session,
            Method::POST,
            &format!("/join/{}", segment(room)),
            Some(json!({})),
        )
    }

    /// Keep transaction_id unchanged when retrying a failed send to avoid duplicates.
    fn send_text_raw(
        &self,
        session: &Session,
        room: &str,
        body: &str,
        transaction_id: &str,
    ) -> Result<Value> {
        if body.trim().is_empty() {
            bail!("Message is empty");
        }
        if transaction_id.is_empty() {
            bail!("Transaction ID is empty");
        }
        self.encrypted(session)?.send(room, body, transaction_id)
    }

    /// Current server-enforced role. A revoked session returns HTTP 401.
    pub fn session_status(&self, session: &Session) -> Result<SessionStatus> {
        Ok(serde_json::from_value(self.request(
            Method::GET,
            &format!("{}/api/session", self.service),
            Some(&session.access_token),
            None,
        )?)?)
    }

    fn pairing(&self, session: &Session, code: &str, approve: bool) -> Result<PairingReview> {
        if code.len() != 8 || !code.bytes().all(|b| b.is_ascii_digit()) {
            bail!("Enter the eight-digit code shown in your browser");
        }
        Ok(serde_json::from_value(self.request(
            Method::POST,
            &format!(
                "{}/api/pairing/{}",
                self.service,
                if approve { "approve" } else { "review" }
            ),
            Some(&session.access_token),
            Some(json!({"code":code})),
        )?)?)
    }

    /// Read pairing details without approving. Requires the current main phone.
    pub fn review_pairing(&self, session: &Session, code: &str) -> Result<PairingReview> {
        self.pairing(session, code, false)
    }

    /// Explicitly authorize this browser for the current main-phone generation.
    pub fn approve_pairing(&self, session: &Session, code: &str) -> Result<PairingReview> {
        self.pairing(session, code, true)
    }

    fn logout_raw(&self, session: &Session) -> Result<Value> {
        self.matrix(session, Method::POST, "/logout", Some(json!({})))
    }
}

/// Legacy Matrix-shaped JSON interface retained for the existing Android app.
/// New Rust integrations should use the typed, phone-oriented methods on Client.
pub fn invoke(input: &str) -> String {
    fn run(input: &str) -> Result<Value> {
        let args: Value = serde_json::from_str(input)?;
        let string = |key: &str| -> Result<&str> {
            args[key].as_str().with_context(|| format!("Missing {key}"))
        };
        let mut client = Client::new(string("service")?)?;
        if let (Some(path), Some(password)) =
            (args["store_path"].as_str(), args["store_password"].as_str())
        {
            client = client.with_crypto_store(path, password);
        }
        match string("command")? {
            "request_code" => return client.request_code_raw(string("phone")?),
            "verify" => {
                return Ok(serde_json::to_value(
                    client.verify(string("phone")?, string("code")?)?,
                )?)
            }
            _ => {}
        }
        let session: Session = serde_json::from_value(args["session"].clone())?;
        match string("command")? {
            "lookup" => Ok(json!({"user_id":client.lookup_raw(&session, string("phone")?)?})),
            "open_chat" => {
                Ok(json!({"room_id":client.open_chat_raw(&session, string("user_id")?)?}))
            }
            "join" => client.join_raw(&session, string("room_id")?),
            "send" => client.send_text_raw(
                &session,
                string("room_id")?,
                string("body")?,
                string("transaction_id")?,
            ),
            "resolve" => client.resolve_raw(
                &session,
                &serde_json::from_value::<Vec<String>>(args["user_ids"].clone())?,
            ),
            "sync" => client.encrypted(&session)?.sync(args.get("retry_events")),
            "devices" => client
                .encrypted(&session)?
                .devices(args["user_id"].as_str().unwrap_or(&session.user_id)),
            "export_keys" => client
                .encrypted(&session)?
                .export_keys(string("path")?.into(), string("passphrase")?),
            "import_keys" => client
                .encrypted(&session)?
                .import_keys(string("path")?.into(), string("passphrase")?),
            "history" => bail!("Server history is not available; read messages from local storage"),
            "session_status" => Ok(serde_json::to_value(client.session_status(&session)?)?),
            "pairing_review" => Ok(serde_json::to_value(
                client.review_pairing(&session, string("code")?)?,
            )?),
            "pairing_approve" => Ok(serde_json::to_value(
                client.approve_pairing(&session, string("code")?)?,
            )?),
            "logout" => client.logout_raw(&session),
            _ => bail!("Unknown command"),
        }
    }
    match run(input) {
        Ok(data) => json!({"ok":true,"data":data}).to_string(),
        Err(error) => error_response(&error).to_string(),
    }
}

#[cfg(target_os = "android")]
mod android {
    use jni::{
        objects::{JClass, JString},
        sys::jstring,
        JNIEnv,
    };
    #[no_mangle]
    pub extern "system" fn Java_chat_dumber_app_NativeCore_invoke(
        mut env: JNIEnv,
        _: JClass,
        input: JString,
    ) -> jstring {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let input: String = env.get_string(&input)?.into();
            env.new_string(super::invoke(&input))
        }));
        match result {
            Ok(Ok(value)) => value.into_raw(),
            _ => {
                let _ = env.throw_new("java/lang/RuntimeException", "Native client failure");
                std::ptr::null_mut()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn structured_http_failure_preserves_legacy_error_text() {
        let error: anyhow::Error = HttpFailure {
            status: 429,
            detail: json!("Daily phone lookup limit reached"),
            retry_after_seconds: Some(123),
            error_code: None,
        }
        .into();
        let response = error_response(&error);
        assert_eq!(response["http_status"], 429);
        assert_eq!(response["retry_after_seconds"], 123);
        assert!(response["error"].as_str().unwrap().starts_with("429:"));
        assert_eq!(response["ok"], false);
        let generic = error_response(&anyhow::anyhow!("offline"));
        assert_eq!(generic["error"], "offline");
        assert!(generic.get("http_status").is_none());
    }
    #[test]
    fn rejects_nonlocal_cleartext() {
        assert!(Client::new("http://nokings.chat").is_err());
        assert!(Client::new("http://127.0.0.1:8080").is_ok());
    }
    #[test]
    fn malformed_json_returns_error() {
        assert_eq!(
            serde_json::from_str::<Value>(&invoke("nope")).unwrap()["ok"],
            false
        );
    }
    #[test]
    fn removed_history_command_fails_locally_without_a_crypto_store() {
        let response: Value = serde_json::from_str(&invoke(
            &json!({
                "command":"history", "service":"http://127.0.0.1:9",
                "session":{"homeserver":"http://127.0.0.1:9","user_id":"@test:example.org",
                    "access_token":"test","device_id":"TEST"},
                "room_id":"!room:example.org", "from":"obsolete-cursor"
            })
            .to_string(),
        ))
        .unwrap();
        assert_eq!(response["ok"], false);
        assert_eq!(
            response["error"],
            "Server history is not available; read messages from local storage"
        );
        assert!(response.get("http_status").is_none());
    }
    #[test]
    fn refuses_plaintext_when_store_is_missing() {
        let client = Client::new("https://nokings.chat").unwrap();
        let session = Session {
            homeserver: "https://nokings.chat".into(),
            user_id: "@test:nokings.chat".into(),
            device_id: "TEST".into(),
            access_token: "test".into(),
            phone: None,
        };
        assert!(client
            .send_text_raw(&session, "!room:nokings.chat", "secret", "txn")
            .unwrap_err()
            .to_string()
            .contains("refusing plaintext"));
        let conversation = serde_json::from_value(json!("opaque-conversation")).unwrap();
        assert!(client
            .send_text(&session, &conversation, "secret", "txn")
            .unwrap_err()
            .to_string()
            .contains("refusing plaintext"));
    }

    #[test]
    fn legacy_sessions_remain_readable_and_credentials_round_trip() {
        let legacy = json!({"homeserver":"https://example.org","user_id":"@alice:example.org",
            "access_token":"secret","device_id":"TEST"});
        let mut session: Session = serde_json::from_value(legacy.clone()).unwrap();
        assert_eq!(session.phone(), None);
        assert_eq!(serde_json::to_value(&session).unwrap(), legacy);
        session.phone = Some("+15555550101".into());
        let restored: Session =
            serde_json::from_slice(&serde_json::to_vec(&session).unwrap()).unwrap();
        assert_eq!(restored.phone(), Some("+15555550101"));
        assert_eq!(restored.access_token, "secret");
    }

    #[test]
    fn encodes_path_segments() {
        assert_eq!(segment("a/b?#"), "a%2Fb%3F%23");
    }
    #[test]
    fn main_and_pairing_protocol_crosses_http_and_jni() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server_base = base.clone();
        let server = std::thread::spawn(move || {
            for operation in ["verify", "status", "review", "approve", "logout"] {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = Vec::new();
                let mut buffer = [0u8; 4096];
                loop {
                    let count = socket.read(&mut buffer).unwrap();
                    assert!(count > 0);
                    bytes.extend_from_slice(&buffer[..count]);
                    let text = String::from_utf8_lossy(&bytes);
                    if let Some(end) = text.find("\r\n\r\n") {
                        let length = text[..end]
                            .lines()
                            .find_map(|line| {
                                line.to_lowercase()
                                    .strip_prefix("content-length: ")
                                    .and_then(|n| n.parse::<usize>().ok())
                            })
                            .unwrap_or(0);
                        if bytes.len() >= end + 4 + length {
                            break;
                        }
                    }
                }
                let request = String::from_utf8(bytes).unwrap();
                let json = match operation {
                    "verify" => {
                        assert!(request.starts_with("POST /api/auth/verify "));
                        let body: Value =
                            serde_json::from_str(request.split("\r\n\r\n").nth(1).unwrap())
                                .unwrap();
                        assert_eq!(body["protocol"], 2);
                        json!({"homeserver":server_base,"user_id":"@test:example.org","device_id":"main","access_token":"secret"})
                    }
                    "status" => {
                        assert!(request.starts_with("GET /api/session "));
                        json!({"role":"main","main_active":true})
                    }
                    "review" | "approve" => {
                        assert!(request.starts_with(&format!("POST /api/pairing/{operation} ")));
                        assert!(request.contains("12345678"));
                        json!({"description":"Firefox","expires_at":123,"approved":operation=="approve"})
                    }
                    _ => {
                        assert!(request.starts_with("POST /_matrix/client/v3/logout "));
                        json!({})
                    }
                };
                if operation != "verify" {
                    assert!(request
                        .to_lowercase()
                        .contains("authorization: bearer secret"));
                }
                let body = json.to_string();
                write!(socket,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
            }
        });
        let client = Client::new(&base).unwrap();
        let session = client.verify("+15555550101", "123456").unwrap();
        assert_eq!(
            client.session_status(&session).unwrap().role,
            SessionRole::Main
        );
        assert!(
            !client
                .review_pairing(&session, "12345678")
                .unwrap()
                .approved
        );
        let response: Value = serde_json::from_str(&invoke(&json!({"command":"pairing_approve","service":base,"session":session,"code":"12345678"}).to_string())).unwrap();
        assert_eq!(response["data"]["approved"], true);
        client.logout(&session).unwrap();
        server.join().unwrap();
    }
    #[test]
    fn pairing_validation_and_revocation_errors_are_typed() {
        let failure = HttpFailure {
            status: 401,
            detail: json!("Revoked"),
            retry_after_seconds: None,
            error_code: Some("M_UNKNOWN_TOKEN".into()),
        };
        let response = error_response(&failure.into());
        assert_eq!(response["errcode"], "M_UNKNOWN_TOKEN");
        let session:Session=serde_json::from_value(json!({"homeserver":"http://127.0.0.1:9","user_id":"@test:example.org","device_id":"main","access_token":"secret"})).unwrap();
        assert!(Client::new("http://127.0.0.1:9")
            .unwrap()
            .review_pairing(&session, "bad")
            .unwrap_err()
            .to_string()
            .contains("eight-digit"));
    }
}
