// Uses only the reserved SSH-only test accounts. Session file must be private.
use anyhow::{ensure, Result};
use dumber_chat_core::{Client, Session};
use serde_json::{json, Value};
use std::{path::PathBuf, time::Duration};
fn main() -> Result<()> {
    let file = std::env::var("CHAT_TEST_SESSIONS")?;
    let sessions: Vec<Session> = serde_json::from_slice(&std::fs::read(file)?)?;
    let root = PathBuf::from(std::env::var("CHAT_TEST_STORE")?);
    let password = "test-store-password-not-for-production-123456";
    let client = Client::new("https://nokings.chat")?.with_crypto_store(&root, password);
    let (a, b) = (&sessions[0], &sessions[1]);
    let a_wire = serde_json::to_value(a)?;
    let b_wire = serde_json::to_value(b)?;
    let recipient_phone = b
        .phone()
        .map(str::to_owned)
        .or_else(|| std::env::var("CHAT_TEST_RECIPIENT_PHONE").ok())
        .ok_or_else(|| anyhow::anyhow!("Set CHAT_TEST_RECIPIENT_PHONE for legacy sessions"))?;
    let initial = client.sync(a)?;
    if let Ok(body) = std::env::var("CHAT_EXPECT_MESSAGE") {
        ensure!(
            serde_json::to_string(&initial)?.contains(&body),
            "Existing native device did not decrypt browser message"
        );
    }
    client.sync(b)?;
    let conversation = client.open_chat(a, &recipient_phone)?;
    // Raw Matrix access here is intentional: verify the server stores ciphertext.
    let raw_open: Value = serde_json::from_str(&dumber_chat_core::invoke(
        &json!({
            "command":"open_chat", "service":"https://nokings.chat", "session":a,
            "store_path":root, "store_password":password, "user_id":b_wire["user_id"]
        })
        .to_string(),
    ))?;
    let room = raw_open["data"]["room_id"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("{raw_open}"))?;
    client.sync(b)?;
    client.join(b, &conversation.id)?;
    client.sync(b)?;
    client.sync(a)?;
    let marker = format!("Encrypted Rust test {}", uuid::Uuid::new_v4());
    let txn = uuid::Uuid::new_v4().to_string();
    let sent = client.send_text(a, &conversation.id, &marker, &txn)?;
    let id = &sent.event_id;
    let raw: Value = reqwest::blocking::Client::new()
        .get(format!(
            "{}/_matrix/client/v3/rooms/{}/event/{}",
            a_wire["homeserver"].as_str().unwrap(),
            room,
            id
        ))
        .bearer_auth(a_wire["access_token"].as_str().unwrap())
        .send()?
        .error_for_status()?
        .json()?;
    ensure!(
        raw["type"] == "m.room.encrypted",
        "Server received plaintext"
    );
    ensure!(raw["content"].get("body").is_none(), "Body leaked");
    ensure!(!raw.to_string().contains(&marker), "Message leaked");
    let mut received = false;
    for _ in 0..6 {
        let sync = client.sync(b)?;
        if serde_json::to_string(&sync)?.contains(&marker) {
            received = true;
            break;
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    ensure!(received, "Recipient did not decrypt encrypted message");
    let reply = format!("Reply {marker}");
    client.send_text(
        b,
        &conversation.id,
        &reply,
        &uuid::Uuid::new_v4().to_string(),
    )?;
    let mut received = false;
    for _ in 0..6 {
        if serde_json::to_string(&client.sync(a)?)?.contains(&reply) {
            received = true;
            break;
        }
    }
    ensure!(received, "Sender did not decrypt reply");
    std::fs::write(
        root.join("test-message.json"),
        json!({"room_id":room,"body":marker}).to_string(),
    )?;
    let backup = root.join("backup.txt");
    if let Ok(import) = std::env::var("CHAT_IMPORT_BACKUP") {
        client.import_keys(a, import, "backup-password-123")?;
    }
    client.export_keys(a, &backup, "backup-password-123")?;
    ensure!(
        !std::fs::read_to_string(&backup)?.contains("session_key"),
        "Export not encrypted"
    );
    ensure!(
        client
            .import_keys(a, &backup, "incorrect-passphrase")
            .is_err(),
        "Wrong password accepted"
    );
    let imported = client.import_keys(a, &backup, "backup-password-123")?;
    ensure!(imported.total_count > 0, "Export contained no message keys");
    println!("PASS: SDK two-way encryption, ciphertext on Synapse, encrypted key export/import, wrong-password rejection");
    Ok(())
}
