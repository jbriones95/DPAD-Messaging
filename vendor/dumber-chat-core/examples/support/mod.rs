//! Test-only server inspection: never part of the public client interface.
use anyhow::{Context, Result};
use dumber_chat_core::{ConversationId, Session};
use serde_json::Value;
use sha2::{Digest, Sha256};

pub fn room(session: &Session, id: &ConversationId) -> Result<String> {
    let wire = serde_json::to_value(session)?;
    let response: Value = reqwest::blocking::Client::new()
        .get(format!(
            "{}/_matrix/client/v3/joined_rooms",
            wire["homeserver"].as_str().context("Missing server")?
        ))
        .bearer_auth(wire["access_token"].as_str().context("Missing token")?)
        .send()?
        .error_for_status()?
        .json()?;
    let wanted = serde_json::to_value(id)?;
    response["joined_rooms"]
        .as_array()
        .context("Missing joined rooms")?
        .iter()
        .filter_map(Value::as_str)
        .find(|room| wanted == format!("conversation_{:x}", Sha256::digest(room.as_bytes())))
        .map(str::to_owned)
        .context("Conversation not joined")
}
