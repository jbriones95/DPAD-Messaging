//! Opt-in test using THREE RESERVED accounts on a private test service.
//! Never share these sessions with another running client/crypto store.
use anyhow::{ensure, Context, Result};
use dumber_chat_core::{Client, ConversationId, MessageContent, Session};
use serde_json::Value;
mod support;
use std::path::PathBuf;

fn receive(client: &Client, session: &Session, body: &str) -> Result<()> {
    for _ in 0..6 {
        if client
            .sync(session)?
            .conversations
            .iter()
            .flat_map(|c| &c.messages)
            .any(|m| matches!(&m.content, MessageContent::Text { body: value } if value == body))
        {
            return Ok(());
        }
    }
    anyhow::bail!("Expected encrypted group message was not decrypted")
}
fn wire_event(session: &Session, room: &str, event: &str) -> Result<Value> {
    let wire = serde_json::to_value(session)?;
    Ok(reqwest::blocking::Client::new()
        .get(format!(
            "{}/_matrix/client/v3/rooms/{room}/event/{event}",
            wire["homeserver"].as_str().unwrap()
        ))
        .bearer_auth(wire["access_token"].as_str().unwrap())
        .send()?
        .error_for_status()?
        .json()?)
}
fn cannot_decrypt(
    client: &Client,
    root: &std::path::Path,
    session: &Session,
    event: Value,
) -> Result<()> {
    // Test actual keys, rather than treating absence from /sync as proof of encryption.
    let file = root.join(format!("membership-keys-{}.txt", uuid::Uuid::new_v4()));
    let passphrase = "test-only-export-password-123";
    client.export_keys(session, &file, passphrase)?;
    let keys = matrix_sdk_crypto::decrypt_room_key_export(std::fs::File::open(&file)?, passphrase)?;
    std::fs::remove_file(file)?;
    let encrypted = serde_json::from_value(event)?;
    let runtime = tokio::runtime::Runtime::new()?;
    for key in keys {
        let inbound = matrix_sdk_crypto::olm::InboundGroupSession::from_export(&key)?;
        ensure!(
            runtime.block_on(inbound.decrypt(&encrypted)).is_err(),
            "Member decrypted an event outside its membership interval"
        );
    }
    Ok(())
}
fn main() -> Result<()> {
    let service = std::env::var("CHAT_SERVICE")
        .context("Use an explicitly configured private test service")?;
    let sessions: Vec<Session> =
        serde_json::from_slice(&std::fs::read(std::env::var("CHAT_TEST_SESSIONS")?)?)?;
    ensure!(
        sessions.len() == 3,
        "Supply exactly three reserved test sessions"
    );
    let root = PathBuf::from(std::env::var("CHAT_TEST_STORE")?);
    let password = "test-only-group-store-password-123456789";
    let client = Client::new(&service)?.with_crypto_store(&root, password);
    let (a, b, c) = (&sessions[0], &sessions[1], &sessions[2]);
    let numbers = vec![
        b.phone().context("Second session needs phone")?.into(),
        c.phone().context("Third session needs phone")?.into(),
    ];
    let operation = uuid::Uuid::new_v4().to_string();
    let group = client.create_group(a, "Reserved group test", &numbers, &operation)?;
    let duplicate = client.create_group(a, "Reserved group test", &numbers, &operation)?;
    ensure!(group.id == duplicate.id, "Creation retry made a duplicate");
    let id: &ConversationId = &group.id;
    client.sync(b)?;
    client.join(b, id)?;
    client.sync(b)?;
    client.sync(c)?;
    let room = support::room(a, id)?;
    let before = "Before third participant joins";
    let pre = client.send_text(a, id, before, &uuid::Uuid::new_v4().to_string())?;
    receive(&client, b, before)?;
    let pre = wire_event(a, &room, &pre.event_id)?;
    ensure!(
        pre["type"] == "m.room.encrypted" && !pre.to_string().contains(before),
        "Plaintext on server"
    );
    client.join(c, id)?;
    client.sync(c)?;
    cannot_decrypt(&client, &root, c, pre)?;
    let body = format!("Joined group {}", uuid::Uuid::new_v4());
    client.send_text(a, id, &body, &uuid::Uuid::new_v4().to_string())?;
    receive(&client, b, &body)?;
    receive(&client, c, &body)?;
    let info = client.conversation_info(a, id)?;
    let c_contact = info
        .members
        .iter()
        .find(|m| m.contact.phone.as_deref() == c.phone())
        .context("Missing third member")?
        .contact
        .id
        .clone();
    client.remove_member(a, id, &c_contact)?;
    let post = client.send_text(a, id, "After removal", &uuid::Uuid::new_v4().to_string())?;
    receive(&client, b, "After removal")?;
    client.sync(c)?;
    cannot_decrypt(&client, &root, c, wire_event(a, &room, &post.event_id)?)?;
    let info = client.conversation_info(a, id)?;
    ensure!(
        info.kind == dumber_chat_core::ConversationKind::Group,
        "Shrinking group became direct"
    );
    let successor = info
        .members
        .iter()
        .find(|m| m.contact.phone.as_deref() == b.phone())
        .context("Missing successor")?
        .contact
        .id
        .clone();
    client.grant_admin(a, id, &successor)?;
    client.leave(a, id)?;
    client.rename_group(b, id, "Successor manages group")?;
    client.leave(b, id)?;
    println!("PASS: encrypted three-account delivery, membership key boundaries, retry-safe creation, admin handover and stable group identity");
    Ok(())
}
