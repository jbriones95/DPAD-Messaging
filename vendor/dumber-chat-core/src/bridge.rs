//! Android serialization boundary. Every network operation delegates to the public API.
use crate::*;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

pub const API_VERSION: u32 = 2;

/// Treat credentials as an opaque secure-storage value, never inspect or log it.
#[derive(Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub credentials: String,
    pub account_id: ContactId,
    pub device_id: String,
    pub phone: Option<String>,
}
impl Session {
    pub fn info(&self) -> Result<SessionInfo> {
        Ok(SessionInfo {
            credentials: serde_json::to_string(self)?,
            account_id: serde_json::from_value(json!(api::opaque("contact", &self.user_id)))?,
            device_id: api::opaque("device", &format!("{}\0{}", self.user_id, self.device_id)),
            phone: self.phone.clone(),
        })
    }
}
impl SessionInfo {
    pub fn restore(&self) -> Result<Session> {
        Ok(serde_json::from_str(&self.credentials)?)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    InvalidInput,
    AuthenticationRequired,
    RecipientUnavailable,
    RateLimited,
    TransportFailure,
    PermissionDenied,
    NotFound,
    UpgradeRequired,
    Internal,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Failure {
    pub kind: ErrorKind,
    pub message: String,
    pub retry_after_seconds: Option<u64>,
}
impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for Failure {}
impl Failure {
    pub(crate) fn invalid(message: &str) -> anyhow::Error {
        Self {
            kind: ErrorKind::InvalidInput,
            message: message.into(),
            retry_after_seconds: None,
        }
        .into()
    }
    pub fn from_error(error: &anyhow::Error) -> Self {
        if let Some(failure) = error.downcast_ref::<Failure>() {
            return failure.clone();
        }
        if let Some(sdk) = error.downcast_ref::<matrix_sdk::Error>() {
            if let Some(response) = sdk.as_client_api_error() {
                return Self::from_error(
                    &HttpFailure {
                        status: response.status_code.as_u16(),
                        detail: Value::Null,
                        retry_after_seconds: None,
                        error_code: None,
                    }
                    .into(),
                );
            }
            return Self {
                kind: ErrorKind::TransportFailure,
                message: "Messaging operation failed".into(),
                retry_after_seconds: None,
            };
        }
        let (kind, message, retry) = if error.is::<RecipientUnavailable>() {
            (
                ErrorKind::RecipientUnavailable,
                "Recipient no longer available",
                None,
            )
        } else if let Some(http) = error.downcast_ref::<HttpFailure>() {
            let (kind, message) = match http.status {
                401 => (ErrorKind::AuthenticationRequired, "Reconnect your account"),
                403 if http.error_code.as_deref() == Some(RECIPIENT_UNAVAILABLE) => (
                    ErrorKind::RecipientUnavailable,
                    "Recipient no longer available",
                ),
                403 => (ErrorKind::PermissionDenied, "Operation not permitted"),
                404 => (ErrorKind::NotFound, "Not found"),
                426 => (
                    ErrorKind::UpgradeRequired,
                    "Update this app before connecting",
                ),
                429 => (ErrorKind::RateLimited, "Try again later"),
                400 | 422 => (ErrorKind::InvalidInput, "Invalid request"),
                _ => (ErrorKind::TransportFailure, "Service unavailable"),
            };
            (kind, message, http.retry_after_seconds)
        } else if error.is::<serde_json::Error>() {
            (ErrorKind::InvalidInput, "Invalid request", None)
        } else if error.is::<reqwest::Error>() || error.is::<reqwest_sdk::Error>() {
            (ErrorKind::TransportFailure, "Connection failed", None)
        } else {
            // Internal diagnostics can contain transport IDs or credentials. Do not export them.
            (ErrorKind::Internal, "Operation failed", None)
        };
        Self {
            kind,
            message: message.into(),
            retry_after_seconds: retry,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    api_version: u32,
    service: String,
    #[serde(default)]
    session: Option<SessionInfo>,
    #[serde(default)]
    store_path: Option<String>,
    #[serde(default)]
    store_password: Option<String>,
    operation: Operation,
}

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case", deny_unknown_fields)]
enum Operation {
    RequestCode {
        phone: String,
    },
    Verify {
        phone: String,
        code: String,
    },
    SessionStatus,
    PairingReview {
        code: String,
    },
    PairingApprove {
        code: String,
    },
    RecipientStatus {
        phone: String,
    },
    CheckRecipient {
        phone: String,
        #[serde(default)]
        force: bool,
    },
    OpenChat {
        phone: String,
        #[serde(default)]
        cached_only: bool,
    },
    Conversations,
    ConversationInfo {
        conversation_id: ConversationId,
    },
    CreateGroup {
        name: String,
        phones: Vec<String>,
        operation_id: String,
    },
    InviteMember {
        conversation_id: ConversationId,
        phone: String,
    },
    RenameGroup {
        conversation_id: ConversationId,
        name: String,
    },
    RemoveMember {
        conversation_id: ConversationId,
        contact_id: ContactId,
    },
    GrantAdmin {
        conversation_id: ConversationId,
        contact_id: ContactId,
    },
    Join {
        conversation_id: ConversationId,
    },
    Leave {
        conversation_id: ConversationId,
    },
    SendText {
        conversation_id: ConversationId,
        body: String,
        transaction_id: String,
    },
    SetCallCapability {
        enabled: bool,
    },
    CallCapability {
        phone: String,
    },
    TurnConfig,
    SendCall {
        conversation_id: ConversationId,
        signal: CallSignal,
        transaction_id: String,
    },
    AcknowledgeCalls {
        event_ids: Vec<String>,
    },
    Sync,
    Devices {
        #[serde(default)]
        phone: Option<String>,
    },
    ExportKeys {
        path: String,
        passphrase: String,
    },
    ImportKeys {
        path: String,
        passphrase: String,
    },
    Logout,
}
fn run(request: Request) -> Result<Value> {
    if request.api_version != API_VERSION {
        return Err(Failure::invalid("Unsupported API version"));
    }
    let mut client = Client::new(&request.service)?;
    match (request.store_path, request.store_password) {
        (Some(path), Some(password)) => client = client.with_crypto_store(path, &password),
        (None, None) => {}
        _ => {
            return Err(Failure::invalid(
                "Both store path and password are required",
            ))
        }
    }
    use Operation::*;
    let operation = request.operation;
    match operation {
        RequestCode { phone } => return Ok(serde_json::to_value(client.request_code(&phone)?)?),
        Verify { phone, code } => {
            return Ok(serde_json::to_value(client.verify(&phone, &code)?.info()?)?)
        }
        _ => {}
    }
    let session = request
        .session
        .ok_or_else(|| Failure::invalid("Session required"))?
        .restore()?;
    let value = match operation {
        SessionStatus => serde_json::to_value(client.session_status(&session)?)?,
        PairingReview { code } => serde_json::to_value(client.review_pairing(&session, &code)?)?,
        PairingApprove { code } => serde_json::to_value(client.approve_pairing(&session, &code)?)?,
        RecipientStatus { phone } => {
            serde_json::to_value(client.recipient_status(&session, &phone)?)?
        }
        CheckRecipient { phone, force } => {
            serde_json::to_value(client.check_recipient(&session, &phone, force)?)?
        }
        OpenChat { phone, cached_only } => serde_json::to_value(if cached_only {
            client.open_chat_cached(&session, &phone)?
        } else {
            client.open_chat(&session, &phone)?
        })?,
        Conversations => serde_json::to_value(client.conversations(&session)?)?,
        ConversationInfo { conversation_id } => {
            serde_json::to_value(client.conversation_info(&session, &conversation_id)?)?
        }
        CreateGroup {
            name,
            phones,
            operation_id,
        } => serde_json::to_value(client.create_group(&session, &name, &phones, &operation_id)?)?,
        InviteMember {
            conversation_id,
            phone,
        } => {
            client.invite_member(&session, &conversation_id, &phone)?;
            Value::Null
        }
        RenameGroup {
            conversation_id,
            name,
        } => {
            client.rename_group(&session, &conversation_id, &name)?;
            Value::Null
        }
        RemoveMember {
            conversation_id,
            contact_id,
        } => {
            client.remove_member(&session, &conversation_id, &contact_id)?;
            Value::Null
        }
        GrantAdmin {
            conversation_id,
            contact_id,
        } => {
            client.grant_admin(&session, &conversation_id, &contact_id)?;
            Value::Null
        }
        Join { conversation_id } => {
            client.join(&session, &conversation_id)?;
            Value::Null
        }
        Leave { conversation_id } => {
            client.leave(&session, &conversation_id)?;
            Value::Null
        }
        SendText {
            conversation_id,
            body,
            transaction_id,
        } => serde_json::to_value(client.send_text(
            &session,
            &conversation_id,
            &body,
            &transaction_id,
        )?)?,
        SetCallCapability { enabled } => {
            client.set_call_capability(&session, enabled)?;
            Value::Null
        }
        CallCapability { phone } => {
            serde_json::to_value(client.call_capability(&session, &phone)?)?
        }
        TurnConfig => serde_json::to_value(client.turn_config(&session)?)?,
        SendCall {
            conversation_id,
            signal,
            transaction_id,
        } => {
            client.send_call(&session, &conversation_id, &signal, &transaction_id)?;
            Value::Null
        }
        AcknowledgeCalls { event_ids } => {
            client.acknowledge_calls(&session, &event_ids)?;
            Value::Null
        }
        Sync => serde_json::to_value(client.sync(&session)?)?,
        Devices { phone } => serde_json::to_value(client.devices(&session, phone.as_deref())?)?,
        ExportKeys { path, passphrase } => {
            client.export_keys(&session, path, &passphrase)?;
            Value::Null
        }
        ImportKeys { path, passphrase } => {
            serde_json::to_value(client.import_keys(&session, path, &passphrase)?)?
        }
        Logout => {
            client.logout(&session)?;
            Value::Null
        }
        RequestCode { .. } | Verify { .. } => unreachable!(),
    };
    Ok(value)
}

pub fn invoke(input: &str) -> String {
    let result = serde_json::from_str::<Request>(input)
        .map_err(anyhow::Error::from)
        .and_then(run);
    match result {
        Ok(data) => json!({"ok": true, "data": data}).to_string(),
        Err(error) => {
            let failure = Failure::from_error(&error);
            json!({"ok": false, "error": failure.message, "error_kind": failure.kind,
                "retry_after_seconds": failure.retry_after_seconds})
            .to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn session() -> Session {
        serde_json::from_value(json!({"homeserver":"http://127.0.0.1:9","user_id":"@a:x","device_id":"A","access_token":"private","phone":"+15555550101"})).unwrap()
    }
    #[test]
    fn legacy_commands_and_transport_fields_are_rejected() {
        for request in [
            json!({"api_version":2,"service":"http://127.0.0.1:9","operation":{"command":"import_legacy","payload":{}}}),
            json!({"command":"sync","service":"http://127.0.0.1:9"}),
            json!({"api_version":1,"service":"http://127.0.0.1:9","operation":{"command":"sync"}}),
            json!({"api_version":2,"service":"http://127.0.0.1:9","operation":{"command":"lookup","phone":"+15555550102"}}),
            json!({"api_version":2,"service":"http://127.0.0.1:9","operation":{"command":"sync","retry_events":[]}}),
            json!({"api_version":2,"service":"http://127.0.0.1:9","operation":{"command":"open_chat","user_id":"@b:x"}}),
        ] {
            let response: Value = serde_json::from_str(&invoke(&request.to_string())).unwrap();
            assert_eq!(response["ok"], false);
            assert_eq!(response["error_kind"], "invalid_input");
        }
    }
    #[test]
    fn session_metadata_is_stable_and_credentials_round_trip() -> Result<()> {
        let session = session();
        let info = session.info()?;
        assert!(serde_json::to_string(&info.account_id)?.contains("contact_"));
        assert!(info.device_id.starts_with("device_"));
        assert_eq!(info.restore()?.access_token, "private");
        let mut replacement = session.clone();
        replacement.access_token = "new".into();
        replacement.device_id = "B".into();
        assert_eq!(info.account_id, replacement.info()?.account_id);
        assert_ne!(info.device_id, replacement.info()?.device_id);
        Ok(())
    }
    #[test]
    fn cached_reads_do_not_require_a_network_connection() -> Result<()> {
        let session = session();
        let root =
            std::env::temp_dir().join(format!("dumber-offline-reads-{}", uuid::Uuid::new_v4()));
        let client = Client::new("http://127.0.0.1:9")?
            .with_crypto_store(root, "test-only-offline-reads-password-1234");
        assert!(client.conversations(&session)?.is_empty());
        assert_eq!(
            client
                .recipient_status(&session, "+15555550102")?
                .availability,
            Availability::Unknown
        );
        Ok(())
    }
}
