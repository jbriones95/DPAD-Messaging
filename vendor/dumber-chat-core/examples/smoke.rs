//! Run against the SSH-only development service. Never uses real SMS.
use anyhow::{bail, Result};
use dumber_chat_core::Client;
use dumber_chat_core::{ConversationId, MessageContent, SyncUpdate};
use uuid::Uuid;

fn contains(sync: &SyncUpdate, room: &ConversationId, body: &str) -> bool {
    sync.conversations.iter().any(|update| {
        &update.conversation.id == room
            && update
                .messages
                .iter()
                .any(|m| matches!(&m.content, MessageContent::Text { body: text } if text == body))
    })
}

fn main() -> Result<()> {
    let url = std::env::var("CHAT_SERVICE").unwrap_or("http://127.0.0.1:18091".into());
    let store = std::env::var("CHAT_TEST_STORE")?;
    let client =
        Client::new(&url)?.with_crypto_store(store, "private-test-store-password-1234567890");
    let a_phone = "+15555550101";
    let b_phone = "+15555550102";
    client.request_code(a_phone)?;
    let a = client.verify(a_phone, "123456")?;
    client.request_code(b_phone)?;
    let b = client.verify(b_phone, "123456")?;
    client.request_code(a_phone)?;
    let a2 = client.verify(a_phone, "123456")?;
    assert_eq!(a.phone(), a2.phone());
    assert!(client.open_chat(&a, "+15555550103").is_err());
    client.sync(&a)?;
    client.sync(&b)?;
    client.sync(&a2)?;
    let room = client.open_chat(&a, b_phone)?.id;
    client.sync(&b)?;
    client.join(&b, &room)?;
    client.sync(&b)?;
    client.sync(&a)?;
    assert_eq!(
        client.open_chat(&a, b_phone)?.id,
        room,
        "reuse the existing direct room"
    );
    let marker = format!("Rust smoke {}", Uuid::new_v4());
    let txn = Uuid::new_v4().to_string();
    let sent = client.send_text(&a, &room, &marker, &txn)?;
    assert_eq!(
        sent,
        client.send_text(&a, &room, &marker, &txn)?,
        "send retries are idempotent"
    );
    let received = client.sync(&b)?;
    if !contains(&received, &room, &marker) {
        bail!("Recipient did not receive message");
    }
    let reply = format!("Reply {marker}");
    client.send_text(&b, &room, &reply, &Uuid::new_v4().to_string())?;
    if !contains(&client.sync(&a)?, &room, &reply) {
        bail!("Sender did not receive reply");
    }
    client.logout(&a)?;
    client.logout(&a2)?;
    client.logout(&b)?;
    println!("PASS: registration, repeat login, directory, room reuse, two-way text, idempotent send, logout");
    Ok(())
}
