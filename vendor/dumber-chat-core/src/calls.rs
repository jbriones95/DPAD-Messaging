//! Encrypted, bounded call signaling. Media and the active-call state belong to the host.
use crate::{
    api::AddressBook, availability::now, Client, Contact, ConversationId, ConversationKind,
    Failure, Membership, Session,
};
use anyhow::{ensure, Result};
use reqwest::Method;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallKind {
    Invite,
    Answer,
    Candidates,
    SelectAnswer,
    Reject,
    Hangup,
    Negotiate,
}
impl CallKind {
    fn event_type(&self) -> &'static str {
        match self {
            Self::Invite => "m.call.invite",
            Self::Answer => "m.call.answer",
            Self::Candidates => "m.call.candidates",
            Self::SelectAnswer => "m.call.select_answer",
            Self::Reject => "m.call.reject",
            Self::Hangup => "m.call.hangup",
            Self::Negotiate => "m.call.negotiate",
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IceCandidate {
    pub candidate: String,
    pub sdp_mid: String,
    pub sdp_m_line_index: u32,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallSignal {
    pub kind: CallKind,
    pub call_id: String,
    pub party_id: String,
    pub sent_at_ms: u64,
    #[serde(default)]
    pub sdp: Option<String>,
    #[serde(default)]
    pub description_type: Option<String>,
    #[serde(default)]
    pub candidates: Vec<IceCandidate>,
    #[serde(default)]
    pub selected_party_id: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CallEvent {
    pub event_id: String,
    pub conversation_id: ConversationId,
    pub sender: Contact,
    pub expires_at_ms: u64,
    pub signal: CallSignal,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CallCapability {
    pub supported: bool,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct TurnConfig {
    pub uris: Vec<String>,
    pub username: String,
    pub password: String,
    pub ttl: u64,
}
fn identifier(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
}
impl CallSignal {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            identifier(&self.call_id) && identifier(&self.party_id),
            "Invalid call identity"
        );
        ensure!(self.sent_at_ms > 0, "Missing call timestamp");
        ensure!(self.candidates.len() <= 32, "Too many candidates");
        for c in &self.candidates {
            ensure!(
                c.candidate.len() <= 2048 && c.sdp_mid.len() <= 64 && c.sdp_m_line_index < 8,
                "Invalid candidate"
            );
        }
        if matches!(
            self.kind,
            CallKind::Invite | CallKind::Answer | CallKind::Negotiate
        ) {
            let sdp = self.sdp.as_deref().unwrap_or("");
            let media: Vec<_> = sdp.lines().filter(|l| l.starts_with("m=")).collect();
            let fingerprint = sdp.lines().any(|line| {
                line.strip_prefix("a=fingerprint:sha-256 ")
                    .is_some_and(|value| {
                        let bytes: Vec<_> = value.split(':').collect();
                        bytes.len() == 32
                            && bytes
                                .iter()
                                .all(|b| b.len() == 2 && b.bytes().all(|c| c.is_ascii_hexdigit()))
                    })
            });
            ensure!(
                sdp.len() <= 65536
                    && media.len() == 1
                    && media[0].starts_with("m=audio ")
                    && media[0].split_ascii_whitespace().nth(2) == Some("UDP/TLS/RTP/SAVPF")
                    && fingerprint,
                "Expected encrypted audio-only SDP"
            );
            if self.kind == CallKind::Negotiate {
                ensure!(
                    matches!(self.description_type.as_deref(), Some("offer" | "answer")),
                    "Invalid description type"
                );
            }
        }
        if self.kind == CallKind::SelectAnswer {
            ensure!(
                self.selected_party_id.as_deref().is_some_and(identifier),
                "Missing selected party"
            );
        }
        ensure!(
            self.reason.as_ref().is_none_or(|s| s.len() <= 64),
            "Invalid reason"
        );
        Ok(())
    }
    fn content(&self) -> Value {
        let mut v = json!({"version":"1", "call_id":self.call_id, "party_id":self.party_id,
            "chat.dumber.sent_at_ms":self.sent_at_ms});
        match self.kind {
            CallKind::Invite => {v["offer"] = json!({"type":"offer","sdp":self.sdp}); v["lifetime"] = json!(45000);}
            CallKind::Answer => v["answer"] = json!({"type":"answer","sdp":self.sdp}),
            CallKind::Negotiate => {v["description"] = json!({"type":self.description_type,"sdp":self.sdp}); v["lifetime"] = json!(15000);}
            CallKind::Candidates => v["candidates"] = Value::Array(self.candidates.iter().map(|c| json!({"candidate":c.candidate,"sdpMid":c.sdp_mid,"sdpMLineIndex":c.sdp_m_line_index})).collect()),
            CallKind::SelectAnswer => v["selected_party_id"] = json!(self.selected_party_id),
            CallKind::Hangup => v["reason"] = json!(self.reason.as_deref().unwrap_or("user_hangup")),
            CallKind::Reject => {},
        }
        v
    }
    fn parse(event: &Value) -> Option<Self> {
        let kind = match event["type"].as_str()? {
            "m.call.invite" => CallKind::Invite,
            "m.call.answer" => CallKind::Answer,
            "m.call.candidates" => CallKind::Candidates,
            "m.call.select_answer" => CallKind::SelectAnswer,
            "m.call.reject" => CallKind::Reject,
            "m.call.hangup" => CallKind::Hangup,
            "m.call.negotiate" => CallKind::Negotiate,
            _ => return None,
        };
        let c = &event["content"];
        if c["version"] != "1" {
            return None;
        }
        let description = match kind {
            CallKind::Invite => &c["offer"],
            CallKind::Answer => &c["answer"],
            _ => &c["description"],
        };
        let mut candidates = Vec::new();
        if kind == CallKind::Candidates {
            let entries = c["candidates"].as_array()?;
            if entries.len() > 32 {
                return None;
            }
            for entry in entries {
                candidates.push(IceCandidate {
                    candidate: entry["candidate"].as_str()?.into(),
                    sdp_mid: entry["sdpMid"].as_str()?.into(),
                    sdp_m_line_index: u32::try_from(entry["sdpMLineIndex"].as_u64()?).ok()?,
                });
            }
        }
        let signal = Self {
            kind,
            call_id: c["call_id"].as_str()?.into(),
            party_id: c["party_id"].as_str()?.into(),
            sent_at_ms: c["chat.dumber.sent_at_ms"].as_u64()?,
            sdp: description["sdp"].as_str().map(str::to_owned),
            description_type: description["type"].as_str().map(str::to_owned),
            candidates,
            selected_party_id: c["selected_party_id"].as_str().map(str::to_owned),
            reason: c["reason"].as_str().map(str::to_owned),
        };
        signal.validate().ok()?;
        Some(signal)
    }
}
pub(crate) fn receive(
    book: &mut AddressBook,
    conversation_id: &ConversationId,
    session: &Session,
    event: &Value,
) {
    // Never accept a plaintext event, even in an encrypted room.
    if event["e2ee"] != true || event["sender"] == session.user_id {
        return;
    }
    let Some(event_id) = event["event_id"].as_str() else {
        return;
    };
    if book.call_seen.contains_key(event_id) || book.call_inbox.contains_key(event_id) {
        return;
    }
    let Some(signal) = CallSignal::parse(event) else {
        return;
    };
    let now = now();
    let lifetime = match signal.kind {
        CallKind::Invite => event["content"]["lifetime"]
            .as_u64()
            .unwrap_or(0)
            .min(45000),
        CallKind::Negotiate => event["content"]["lifetime"]
            .as_u64()
            .unwrap_or(0)
            .min(15000),
        _ => 60000,
    };
    let expires_at_ms = signal.sent_at_ms.saturating_add(lifetime);
    if expires_at_ms <= now || signal.sent_at_ms > now.saturating_add(30000) {
        return;
    }
    let conversation = book.conversation(conversation_id, &session.user_id);
    if conversation.kind != ConversationKind::Direct
        || conversation.membership != Membership::Joined
        || conversation.participants.len() != 1
        || conversation
            .members
            .iter()
            .filter(|m| m.membership == Membership::Joined)
            .count()
            != 2
    {
        return;
    }
    let Some(sender) = event["sender"].as_str() else {
        return;
    };
    let sender = book.contact(sender);
    if conversation.participants[0].id != sender.id || book.call_inbox.len() >= 512 {
        return;
    }
    book.call_inbox.insert(
        event_id.into(),
        CallEvent {
            event_id: event_id.into(),
            conversation_id: conversation_id.clone(),
            sender,
            expires_at_ms,
            signal,
        },
    );
}
impl Client {
    pub fn set_call_capability(&self, session: &Session, enabled: bool) -> Result<()> {
        self.request(
            Method::POST,
            &format!("{}/api/calls/capability", self.service),
            Some(&session.access_token),
            Some(json!({"enabled":enabled})),
        )?;
        Ok(())
    }
    pub fn call_capability(&self, session: &Session, number: &str) -> Result<CallCapability> {
        // A deliberate call must recheck discovery: the normal messaging cache can
        // retain a negative result for a day, including after the peer logs back in.
        // Forced checks still respect the server's rate-limit backoff.
        let status = self.check_recipient(session, number, true)?;
        if status.availability == crate::Availability::Unavailable {
            return Ok(CallCapability { supported: false });
        }
        if status.availability != crate::Availability::Available {
            return Err(Failure::invalid("Recipient capability is unknown"));
        }
        let crypto = self.encrypted(session)?;
        let user = {
            let _guard = crypto.address_lock.lock().unwrap();
            crypto
                .load_addresses()?
                .by_phone
                .get(number)
                .cloned()
                .ok_or_else(|| Failure::invalid("Unknown recipient"))?
        };
        let result = self.request(
            Method::POST,
            &format!("{}/api/calls/lookup", self.service),
            Some(&session.access_token),
            Some(json!({"user_id":user})),
        )?;
        Ok(serde_json::from_value(result)?)
    }
    pub fn turn_config(&self, session: &Session) -> Result<TurnConfig> {
        let config: TurnConfig =
            serde_json::from_value(self.matrix(session, Method::GET, "/voip/turnServer", None)?)?;
        ensure!(
            !config.uris.is_empty() && config.uris.len() <= 16 && config.ttl > 0,
            "TURN unavailable"
        );
        ensure!(
            config
                .uris
                .iter()
                .all(|u| u.starts_with("turn:") || u.starts_with("turns:")),
            "Invalid TURN URI"
        );
        Ok(config)
    }
    pub fn send_call(
        &self,
        session: &Session,
        conversation_id: &ConversationId,
        signal: &CallSignal,
        transaction_id: &str,
    ) -> Result<()> {
        signal.validate()?;
        ensure!(identifier(transaction_id), "Invalid transaction ID");
        ensure!(
            signal.sent_at_ms.saturating_add(60000) > now()
                && signal.sent_at_ms <= now().saturating_add(30000),
            "Expired signal"
        );
        let crypto = self.encrypted(session)?;
        let room = {
            let _guard = crypto.address_lock.lock().unwrap();
            let book = crypto.load_addresses()?;
            let room = book.room(conversation_id)?.to_owned();
            let c = book.conversation(conversation_id, &session.user_id);
            ensure!(
                c.kind == ConversationKind::Direct
                    && c.membership == Membership::Joined
                    && c.participants.len() == 1
                    && c.members
                        .iter()
                        .filter(|m| m.membership == Membership::Joined)
                        .count()
                        == 2,
                "Call requires a joined direct conversation"
            );
            room
        };
        crypto.send_call(
            &room,
            signal.kind.event_type(),
            signal.content(),
            transaction_id,
        )
    }
    pub fn acknowledge_calls(&self, session: &Session, event_ids: &[String]) -> Result<()> {
        ensure!(event_ids.len() <= 512, "Too many acknowledgements");
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let mut book = crypto.load_addresses()?;
        for id in event_ids {
            if let Some(event) = book.call_inbox.remove(id) {
                book.call_seen.insert(id.clone(), event.expires_at_ms);
            }
        }
        crypto.save_addresses(&book)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn offer() -> CallSignal {
        CallSignal {
            kind: CallKind::Invite,
            call_id: "call1".into(),
            party_id: "party1".into(),
            sent_at_ms: now(),
            sdp: Some(format!(
                "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fingerprint:sha-256 {}\r\n",
                vec!["AB"; 32].join(":")
            )),
            description_type: None,
            candidates: vec![],
            selected_party_id: None,
            reason: None,
        }
    }
    #[test]
    fn audio_offer_round_trips_and_rejects_video_or_unencrypted_sdp() {
        let mut s = offer();
        let raw = json!({"type":s.kind.event_type(),"content":s.content()});
        assert_eq!(CallSignal::parse(&raw).unwrap().sdp, s.sdp);
        s.sdp
            .as_mut()
            .unwrap()
            .push_str("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n");
        assert!(s.validate().is_err());
        s.sdp = Some("m=audio 9 RTP/AVP 0\r\n".into());
        assert!(s.validate().is_err());
        s = offer();
        s.sdp = s.sdp.map(|v| v.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"));
        assert!(s.validate().is_err());
        s = offer();
        s.sdp = s.sdp.map(|v| v.replace("AB:", "ZZ:"));
        assert!(s.validate().is_err());
    }
    #[test]
    fn rejects_malformed_and_unbounded_signals() {
        let mut s = offer();
        s.call_id = "".into();
        assert!(s.validate().is_err());
        s = offer();
        s.kind = CallKind::SelectAnswer;
        assert!(s.validate().is_err());
        s = offer();
        s.candidates = vec![IceCandidate {
            candidate: "x".repeat(2049),
            sdp_mid: "0".into(),
            sdp_m_line_index: 0,
        }];
        assert!(s.validate().is_err());
        assert!(
            CallSignal::parse(&json!({"type":"m.call.invite","content":{"version":0}})).is_none()
        );
    }
}
