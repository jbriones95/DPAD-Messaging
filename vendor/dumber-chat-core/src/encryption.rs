//! Matrix SDK owns device keys, Olm sessions, Megolm rotation and persistent crypto state.
use crate::Session;
use anyhow::{bail, Context, Result};
use matrix_sdk::{
    authentication::matrix::MatrixSession,
    config::SyncSettings,
    deserialized_responses::{TimelineEvent, TimelineEventKind},
    room::MessagesOptions,
    ruma::{
        events::room::{encryption::RoomEncryptionEventContent, message::RoomMessageEventContent},
        RoomId, UserId,
    },
    sync::State,
    Client as MatrixClient,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

pub(crate) fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Matrix runtime")
    })
}
static CLIENTS: OnceLock<Mutex<HashMap<PathBuf, Arc<EncryptedClient>>>> = OnceLock::new();

fn send_error(error: matrix_sdk::Error) -> anyhow::Error {
    if error.as_client_api_error().is_some_and(|response| {
        response.status_code.as_u16() == 403
            && response
                .error_kind()
                .is_some_and(|kind| kind.errcode().as_str() == crate::RECIPIENT_UNAVAILABLE)
    }) {
        crate::RecipientUnavailable.into()
    } else {
        error.into()
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub(crate) struct EncryptedClient {
    client: MatrixClient,
    sync_lock: tokio::sync::Mutex<()>,
    pending: Mutex<Vec<Value>>,
    pub(crate) address_lock: Mutex<()>,
    pub(crate) typed_sync_lock: Mutex<()>,
    address_cipher: matrix_sdk_store_encryption::StoreCipher,
    password_hash: [u8; 32],
}

pub(crate) fn client(
    session: &Session,
    root: &Path,
    password: &str,
) -> Result<Arc<EncryptedClient>> {
    // SQLite pool cleanup also requires a runtime if initialization fails.
    let _runtime_guard = runtime().enter();
    if password.len() < 32 {
        bail!("A persistent crypto-store password of at least 32 characters is required");
    }
    let name = format!(
        "{:x}",
        Sha256::digest(format!(
            "{}\0{}\0{}",
            session.homeserver, session.user_id, session.device_id
        ))
    );
    let path = root.join(name);
    let mut clients = CLIENTS.get_or_init(Default::default).lock().unwrap();
    if let Some(client) = clients.get(&path) {
        if client.password_hash != <[u8; 32]>::from(Sha256::digest(password.as_bytes())) {
            bail!("Incorrect crypto-store password");
        }
        return Ok(client.clone());
    }
    std::fs::create_dir_all(&path)?;
    let sdk = runtime().block_on(async {
        // Use Mozilla roots consistently, including Android without a Java TLS bridge.
        let roots =
            rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::aws_lc_rs::default_provider(),
        ))
        .with_safe_default_protocol_versions()?
        .with_root_certificates(roots)
        .with_no_client_auth();
        let http = reqwest_sdk::Client::builder()
            .tls_backend_preconfigured(tls)
            .build()?;
        let sdk = MatrixClient::builder()
            .http_client(http)
            .homeserver_url(&session.homeserver)
            .sqlite_store(&path, Some(password))
            .build()
            .await?;
        let login: MatrixSession = serde_json::from_value(serde_json::to_value(session)?)?;
        sdk.matrix_auth()
            .restore_session(login, Default::default())
            .await?;
        anyhow::Ok(sdk)
    })?;
    // SDK state-store custom values are not encrypted automatically.
    // Wrap our address book with the SDK's authenticated store cipher.
    let address_cipher = address_cipher(&sdk, password)?;
    let client = Arc::new(EncryptedClient {
        client: sdk,
        sync_lock: Default::default(),
        pending: Default::default(),
        address_lock: Default::default(),
        typed_sync_lock: Default::default(),
        address_cipher,
        password_hash: Sha256::digest(password.as_bytes()).into(),
    });
    runtime().block_on(client.refresh(true, 0))?;
    clients.insert(path, client.clone());
    Ok(client)
}

fn address_cipher(
    sdk: &MatrixClient,
    password: &str,
) -> Result<matrix_sdk_store_encryption::StoreCipher> {
    runtime().block_on(async {
        let key = b"dumber.address-cipher.v1";
        if let Some(bytes) = sdk.state_store().get_custom_value(key).await? {
            anyhow::Ok(matrix_sdk_store_encryption::StoreCipher::import(
                password, &bytes,
            )?)
        } else {
            let cipher = matrix_sdk_store_encryption::StoreCipher::new()?;
            sdk.state_store()
                .set_custom_value(key, cipher.export(password)?)
                .await?;
            Ok(cipher)
        }
    })
}

/// Install a real SQLite-backed client without making an initial network sync.
/// Tests use a closed localhost port to detect accidental directory requests.
#[cfg(test)]
pub(crate) fn offline_test_client(
    session: &Session,
    root: &Path,
    password: &str,
) -> Result<Arc<EncryptedClient>> {
    let name = format!(
        "{:x}",
        Sha256::digest(format!(
            "{}\0{}\0{}",
            session.homeserver, session.user_id, session.device_id
        ))
    );
    let path = root.join(name);
    std::fs::create_dir_all(&path)?;
    let sdk = runtime().block_on(async {
        MatrixClient::builder()
            .homeserver_url(&session.homeserver)
            .sqlite_store(&path, Some(password))
            .build()
            .await
    })?;
    let address_cipher = address_cipher(&sdk, password)?;
    let client = Arc::new(EncryptedClient {
        client: sdk,
        sync_lock: Default::default(),
        pending: Default::default(),
        address_lock: Default::default(),
        typed_sync_lock: Default::default(),
        address_cipher,
        password_hash: Sha256::digest(password.as_bytes()).into(),
    });
    CLIENTS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .insert(path, client.clone());
    Ok(client)
}

fn event_json(event: &TimelineEvent) -> Result<Value> {
    let mut value: Value = serde_json::from_str(event.raw().json().get())?;
    match &event.kind {
        TimelineEventKind::Decrypted(_) => {
            value["e2ee"] = json!(true);
        }
        TimelineEventKind::UnableToDecrypt { .. } => {
            let encrypted = value.clone();
            value["type"] = json!("m.room.message");
            value["content"] = json!({"msgtype":"m.text", "body":"Encrypted message — key not available on this device. Restore your message keys or wait for the sender's key."});
            value["encrypted_event"] = encrypted;
            value["unable_to_decrypt"] = json!(true);
        }
        _ => {}
    }
    Ok(value)
}

impl EncryptedClient {
    /// Callers hold address_lock across read-modify-write operations.
    pub(crate) fn load_addresses(&self) -> Result<crate::api::AddressBook> {
        let bytes = runtime().block_on(
            self.client
                .state_store()
                .get_custom_value(b"dumber.addresses.v1"),
        )?;
        match bytes {
            Some(bytes) => Ok(self.address_cipher.decrypt_value(&bytes)?),
            None => Ok(Default::default()),
        }
    }

    pub(crate) fn save_addresses(&self, book: &crate::api::AddressBook) -> Result<()> {
        let bytes = self.address_cipher.encrypt_value(book)?;
        runtime().block_on(
            self.client
                .state_store()
                .set_custom_value(b"dumber.addresses.v1", bytes),
        )?;
        Ok(())
    }

    pub(crate) fn requeue(&self, batch: Value) {
        self.pending.lock().unwrap().insert(0, batch);
    }

    async fn load_catchup(&self) -> Result<crate::catchup::CatchUp> {
        match self
            .client
            .state_store()
            .get_custom_value(b"dumber.catchup.v1")
            .await?
        {
            Some(bytes) => Ok(self.address_cipher.decrypt_value(&bytes)?),
            None => Ok(Default::default()),
        }
    }

    async fn save_catchup(&self, state: &crate::catchup::CatchUp) -> Result<()> {
        self.client
            .state_store()
            .set_custom_value(
                b"dumber.catchup.v1",
                self.address_cipher.encrypt_value(state)?,
            )
            .await?;
        Ok(())
    }

    async fn refresh(&self, initial: bool, timeout: u64) -> Result<()> {
        let _guard = self.sync_lock.lock().await;
        let mut catchup = self.load_catchup().await?;
        let mut settings = SyncSettings::default().timeout(Duration::from_secs(timeout));
        if initial {
            // Refresh room membership while retaining the SDK's delivery cursor.
            // A restart must not deliberately request an initial history replay.
            settings = settings.full_state(true);
        }
        let sync = self.client.sync_once(settings).await?;
        let mut result =
            json!({"next_batch":sync.next_batch,"rooms":{"join":{},"invite":{},"leave":{}}});
        for (id, update) in sync.rooms.joined {
            let state = match update.state {
                State::Before(events) | State::After(events) => events,
            };
            let events: Vec<_> = update
                .timeline
                .events
                .iter()
                .map(event_json)
                .collect::<Result<_>>()?;
            catchup.observe(
                id.as_str(),
                update.timeline.limited,
                update.timeline.prev_batch.as_deref(),
                &events,
            );
            result["rooms"]["join"][id.as_str()] = json!({"state":{"events":state},"timeline":{"events":events,"limited":update.timeline.limited}});
        }
        for (id, update) in sync.rooms.invited {
            result["rooms"]["invite"][id.as_str()] = serde_json::to_value(update)?;
        }
        for id in sync.rooms.left.keys() {
            catchup.leave(id.as_str());
            result["rooms"]["leave"][id.as_str()] = json!({});
        }
        self.pending.lock().unwrap().push(result);
        self.save_catchup(&catchup).await?;
        Ok(())
    }

    /// Fetch at most one page per sync so live messages remain responsive.
    /// A failed room rotates to the back of the durable queue; newer deliveries
    /// and other rooms can still be processed.
    async fn catch_up(&self) -> Result<Option<(String, Vec<Value>)>> {
        let _guard = self.sync_lock.lock().await;
        let mut state = self.load_catchup().await?;
        if state.retry_after_ms > now_ms() {
            return Ok(None);
        }
        let Some(gap) = state.jobs.pop_front() else {
            return Ok(None);
        };
        let id = RoomId::parse(&gap.room)?;
        let result = if let Some(room) = self.client.get_room(&id) {
            if room.state() != matrix_sdk::RoomState::Joined {
                self.save_catchup(&state).await?;
                return Ok(None);
            }
            let mut options = MessagesOptions::backward();
            options.from = Some(gap.from.clone());
            options.limit = 100u32.into();
            match room.messages(options).await {
                Ok(response) => {
                    state.retry_after_ms = 0;
                    let events = response
                        .chunk
                        .iter()
                        .map(event_json)
                        .collect::<Result<Vec<_>>>()?;
                    let (events, next) = gap.page(events, response.end);
                    if let Some(next) = next {
                        state.jobs.push_back(next);
                    }
                    Some((gap.room, events))
                }
                Err(_) => {
                    state.retry_after_ms = now_ms() + 30_000;
                    state.jobs.push_back(gap);
                    None
                }
            }
        } else {
            state.retry_after_ms = now_ms() + 30_000;
            state.jobs.push_back(gap);
            None
        };
        self.save_catchup(&state).await?;
        Ok(result)
    }

    async fn room(&self, id: &str) -> Result<matrix_sdk::Room> {
        let id = RoomId::parse(id)?;
        if self.client.get_room(&id).is_none() {
            self.refresh(false, 0).await?;
        }
        self.client
            .get_room(&id)
            .context("Room not synced yet; please try again")
    }

    async fn ensure_encrypted(&self, id: &str) -> Result<matrix_sdk::Room> {
        let room = self.room(id).await?;
        if !room.latest_encryption_state().await?.is_encrypted() {
            // Do not send any message until the SDK has observed the encryption state.
            room.send_state_event(RoomEncryptionEventContent::with_recommended_defaults()).await
                .context("Cannot enable encryption in this room. Ask its creator to update the app and send first.")?;
            self.refresh(false, 0).await?;
        }
        if !room.encryption_state().is_encrypted() {
            bail!("Encryption is not ready; no message was sent");
        }
        Ok(room)
    }

    pub fn send(&self, id: &str, body: &str, txn: &str) -> Result<Value> {
        runtime().block_on(async {
            let room = self.ensure_encrypted(id).await?;
            let txn: matrix_sdk::ruma::OwnedTransactionId = txn.into();
            let response = room
                .send(RoomMessageEventContent::text_plain(body))
                .with_transaction_id(txn)
                .await
                .map_err(send_error)?;
            Ok(json!({"event_id":response.response.event_id,"e2ee":true}))
        })
    }

    pub fn sync(&self, retry: Option<&Value>) -> Result<Value> {
        runtime().block_on(async {
            if self.pending.lock().unwrap().is_empty() {
                let catchup = self.load_catchup().await?;
                let timeout = if catchup.jobs.is_empty() || catchup.retry_after_ms > now_ms() {
                    20
                } else {
                    0
                };
                self.refresh(false, timeout).await?;
            }
            let recovered = self.catch_up().await?;
            let pending = std::mem::take(&mut *self.pending.lock().unwrap());
            let mut out = json!({"next_batch":"", "rooms":{"join":{},"invite":{},"leave":{}}});
            for batch in pending {
                out["next_batch"] = batch["next_batch"].clone();
                for membership in ["join", "invite", "leave"] {
                    if let Some(rooms) = batch["rooms"][membership].as_object() {
                        for (id, update) in rooms {
                            for other in ["join", "invite", "leave"] {
                                if other != membership {
                                    out["rooms"][other].as_object_mut().unwrap().remove(id);
                                }
                            }
                            if membership == "join" && out["rooms"]["join"][id].is_object() {
                                for part in ["state", "timeline"] {
                                    let existing = &mut out["rooms"]["join"][id][part]["events"];
                                    if let (Some(old), Some(new)) =
                                        (existing.as_array_mut(), update[part]["events"].as_array())
                                    {
                                        old.extend(new.iter().cloned());
                                    }
                                }
                                if update["timeline"]["limited"] == true {
                                    out["rooms"]["join"][id]["timeline"]["limited"] = json!(true);
                                }
                            } else {
                                out["rooms"][membership][id] = update.clone();
                            }
                        }
                    }
                }
            }
            if let Some((id, events)) = recovered {
                if !out["rooms"]["join"][&id].is_object() {
                    out["rooms"]["join"][&id] = json!({"timeline":{"events":[]}});
                }
                out["rooms"]["join"][&id]["timeline"]["events"]
                    .as_array_mut()
                    .unwrap()
                    .extend(events);
            }
            if let Some(retries) = retry.and_then(Value::as_array) {
                for retry in retries {
                    if let (Some(id), Some(raw)) = (retry["room_id"].as_str(), retry.get("event")) {
                        if let Ok(room) = self.room(id).await {
                            if room.state() != matrix_sdk::RoomState::Joined {
                                continue;
                            }
                            if let Ok(event) = room
                                .decrypt_event(&serde_json::from_value(raw.clone())?, None)
                                .await
                            {
                                let event = event_json(&event)?;
                                if event["e2ee"] == true {
                                    if !out["rooms"]["join"][id].is_object() {
                                        out["rooms"]["join"][id] =
                                            json!({"timeline":{"events":[]}});
                                    }
                                    out["rooms"]["join"][id]["timeline"]["events"]
                                        .as_array_mut()
                                        .unwrap()
                                        .push(event);
                                }
                            }
                        }
                    }
                }
            }
            Ok(out)
        })
    }

    pub fn enable(&self, id: &str) -> Result<()> {
        runtime().block_on(self.ensure_encrypted(id))?;
        Ok(())
    }

    pub fn export_keys(&self, path: PathBuf, passphrase: &str) -> Result<Value> {
        if passphrase.len() < 12 {
            bail!("Use a backup passphrase of at least 12 characters");
        }
        runtime().block_on(
            self.client
                .encryption()
                .export_room_keys(path, passphrase, |_| true),
        )?;
        Ok(json!({"status":"exported"}))
    }
    pub fn import_keys(&self, path: PathBuf, passphrase: &str) -> Result<Value> {
        let result =
            runtime().block_on(self.client.encryption().import_room_keys(path, passphrase))?;
        Ok(json!({"imported":result.imported_count,"total":result.total_count}))
    }
    pub fn devices(&self, user: &str) -> Result<Value> {
        runtime().block_on(async {
            let user = UserId::parse(user)?;
            let devices = self.client.encryption().get_user_devices(&user).await?;
            let devices: Vec<_> = devices.devices().map(|device| json!({
                "device_id":device.device_id(),"name":device.display_name(),"verified":device.is_verified(),
                "fingerprint":device.get_key(matrix_sdk::ruma::DeviceKeyAlgorithm::Ed25519).map(|k| k.to_base64())
            })).collect();
            Ok(json!({"user_id":user,"device_id":self.client.device_id(),"fingerprint":self.client.encryption().ed25519_key().await,"devices":devices}))
        })
    }
}

#[cfg(test)]
mod recipient_error_tests {
    use super::*;
    use matrix_sdk::ruma::api::{
        client::uiaa::UiaaResponse,
        error::{Error, ErrorBody, FromHttpResponseError, StandardErrorBody},
    };

    fn sdk_error(status: u16, code: &str) -> matrix_sdk::Error {
        let body: StandardErrorBody =
            serde_json::from_value(json!({"errcode": code, "error": "any language"})).unwrap();
        let raw = Error::new(
            reqwest_sdk::StatusCode::from_u16(status).unwrap(),
            ErrorBody::Standard(body),
        );
        matrix_sdk::HttpError::Api(Box::new(FromHttpResponseError::Server(
            UiaaResponse::MatrixError(raw),
        )))
        .into()
    }

    #[test]
    fn only_explicit_server_rejection_is_recipient_unavailable() {
        let error = send_error(sdk_error(403, crate::RECIPIENT_UNAVAILABLE));
        assert!(error.is::<crate::RecipientUnavailable>());
        let json = crate::error_response(&error);
        assert_eq!(json["http_status"], 403);
        assert_eq!(json["errcode"], crate::RECIPIENT_UNAVAILABLE);
        for (status, code) in [
            (403, "M_FORBIDDEN"),
            (401, "M_UNKNOWN_TOKEN"),
            (500, crate::RECIPIENT_UNAVAILABLE),
            (429, "M_LIMIT_EXCEEDED"),
        ] {
            let error = send_error(sdk_error(status, code));
            assert!(!error.is::<crate::RecipientUnavailable>());
            assert!(crate::error_response(&error).get("errcode").is_none());
        }
    }
}
