//! Reserved accounts only, through the backend’s scripts/availability-smoke-server.py over SSH.
use anyhow::{ensure, Result};
use dumber_chat_core::{Client, RecipientUnavailable, Session};
use serde_json::{json, Value};
use uuid::Uuid;
mod support;

fn main() -> Result<()> {
    let service = std::env::var("CHAT_SERVICE").unwrap_or("http://127.0.0.1:18092".into());
    ensure!(
        service.starts_with("http://127.0.0.1:"),
        "Use the private SSH test service"
    );
    let store = std::env::var("CHAT_TEST_STORE")?;
    let password = "private-availability-test-password-1234567890";
    let client = Client::new(&service)?.with_crypto_store(&store, password);
    let http = reqwest::blocking::Client::new();
    let mut sessions: Vec<Session> = Vec::new();
    let mut extras: Vec<Session> = Vec::new();
    let outcome = (|| -> Result<()> {
        for phone in ["+15555550991", "+15555550992"] {
            client.request_code(phone)?;
            sessions.push(client.verify(phone, "123456")?);
        }
        let (a, b) = (&sessions[0], &sessions[1]);
        let a_wire = serde_json::to_value(a)?;
        let token = a_wire["access_token"].as_str().unwrap();
        let homeserver = a_wire["homeserver"].as_str().unwrap();
        let lookup = || -> Result<reqwest::blocking::Response> {
            Ok(http
                .post(format!("{service}/api/directory/lookup"))
                .bearer_auth(token)
                .json(&json!({"phone":"+15555550992"}))
                .send()?)
        };
        ensure!(
            lookup()?.status().as_u16() == 200,
            "Registered recipient not found"
        );
        let conversation = client.open_chat(a, "+15555550992")?;
        client.sync(b)?;
        client.join(b, &conversation.id)?;
        client.sync(b)?;
        client.sync(a)?;
        let transaction = Uuid::new_v4().to_string();
        let sent = client.send_text(
            a,
            &conversation.id,
            "availability test encrypted",
            &transaction,
        )?;
        let received = serde_json::to_string(&client.sync(b)?)?;
        ensure!(
            received.contains("availability test encrypted"),
            "Recipient did not decrypt message"
        );
        let room = support::room(a, &conversation.id)?;
        let event: Value = http
            .get(format!(
                "{homeserver}/_matrix/client/v3/rooms/{room}/event/{}",
                sent.event_id
            ))
            .bearer_auth(token)
            .send()?
            .error_for_status()?
            .json()?;
        ensure!(
            event["type"] == "m.room.encrypted",
            "Event is not encrypted"
        );
        ensure!(
            !event.to_string().contains("availability test encrypted"),
            "Plaintext leaked"
        );
        client.request_code("+15555550992")?;
        let second = client.verify("+15555550992", "123456")?;
        extras.push(second.clone());
        // The second device never syncs: offline devices must still count.
        client.logout(b)?;
        ensure!(
            lookup()?.status().as_u16() == 200,
            "Remaining offline device was ignored"
        );
        client.logout(&second)?;
        ensure!(
            lookup()?.status().as_u16() == 404,
            "Zero devices still discoverable"
        );
        // Retry a send that was accepted before logout, as after a lost acknowledgement.
        let duplicate: Value = http
            .put(format!(
                "{homeserver}/_matrix/client/v3/rooms/{room}/send/m.room.encrypted/{transaction}"
            ))
            .bearer_auth(token)
            .json(&event["content"])
            .send()?
            .error_for_status()?
            .json()?;
        ensure!(
            duplicate["event_id"] == sent.event_id,
            "Accepted transaction lost its idempotency after logout"
        );
        let count_events = || -> Result<usize> {
            let value: Value = http
                .get(format!(
                    "{homeserver}/_matrix/client/v3/rooms/{room}/messages?dir=b&limit=100"
                ))
                .bearer_auth(token)
                .send()?
                .error_for_status()?
                .json()?;
            Ok(value["chunk"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|e| e["type"] == "m.room.encrypted")
                .count())
        };
        let before = count_events()?;
        let failure = client
            .send_text(
                a,
                &conversation.id,
                "must not be accepted",
                &Uuid::new_v4().to_string(),
            )
            .unwrap_err();
        ensure!(
            failure.is::<RecipientUnavailable>(),
            "Wrong typed failure: {failure}"
        );
        let request = json!({"api_version":2,"service":service,"session":a.info()?,
            "store_path":store,"store_password":password,"operation":{"command":"send_text",
            "conversation_id":conversation.id,"body":"bridge rejection","transaction_id":Uuid::new_v4().to_string()}});
        let failure: Value = serde_json::from_str(&dumber_chat_core::invoke(&request.to_string()))?;
        ensure!(
            failure["error_kind"] == "recipient_unavailable",
            "JNI marker missing: {failure}"
        );
        ensure!(count_events()? == before, "Rejected message was persisted");
        client.request_code("+15555550992")?;
        let reconnected = client.verify("+15555550992", "123456")?;
        extras.push(reconnected.clone());
        ensure!(
            lookup()?.status().as_u16() == 200,
            "Re-login did not restore lookup"
        );
        client.send_text(
            a,
            &conversation.id,
            "recipient reconnected",
            &Uuid::new_v4().to_string(),
        )?;
        client.logout(&reconnected)?;
        println!("PASS: encrypted delivery, offline second device, last logout, negative lookup, server rejection, typed/JNI errors, no persistence, transaction retry, re-login");
        Ok(())
    })();
    for session in sessions.iter().chain(extras.iter()) {
        let _ = client.logout(session);
    }
    outcome
}
