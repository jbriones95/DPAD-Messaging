//! Recipient discovery policy, shared by all bindings. Never called by send_text.
use crate::{api::phone, Client, Contact, HttpFailure, Session};
use anyhow::Result;
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    Available,
    Unavailable,
    #[default]
    Unknown,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RecipientStatus {
    pub availability: Availability,
    pub contact: Option<Contact>,
    pub retry_at_ms: u64,
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub(crate) struct Entry {
    pub state: Availability,
    pub checked_at: u64,
    pub retry_at: u64,
    pub revision: u64,
}
pub(crate) fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
impl Client {
    /// Read availability without network traffic. An expired negative stays negative until rechecked.
    pub fn recipient_status(&self, session: &Session, number: &str) -> Result<RecipientStatus> {
        phone(number)?;
        let crypto = self.encrypted(session)?;
        let _guard = crypto.address_lock.lock().unwrap();
        let book = crypto.load_addresses()?;
        let entry = book.availability.get(number).cloned().unwrap_or_default();
        Ok(RecipientStatus {
            availability: entry.state,
            contact: if entry.state == Availability::Available {
                book.by_phone.get(number).map(|user| book.contact(user))
            } else {
                None
            },
            retry_at_ms: entry.retry_at.max(book.lookup_retry_at),
        })
    }

    /// Check a number without creating a conversation. Force bypasses TTL, never server backoff.
    pub fn check_recipient(
        &self,
        session: &Session,
        number: &str,
        force: bool,
    ) -> Result<RecipientStatus> {
        phone(number)?;
        let crypto = self.encrypted(session)?;
        let _discovery_guard = crypto.discovery_lock.lock().unwrap();
        let time = now();
        let revision = {
            let _guard = crypto.address_lock.lock().unwrap();
            let book = crypto.load_addresses()?;
            let entry = book.availability.get(number).cloned().unwrap_or_default();
            let fresh = entry.state == Availability::Available
                || (entry.state == Availability::Unavailable
                    && entry.checked_at.saturating_add(86_400_000) > time);
            if book.lookup_retry_at > time || (!force && (fresh || entry.retry_at > time)) {
                drop(_guard);
                return self.recipient_status(session, number);
            }
            entry.revision
        };
        let result = self.lookup_raw(session, number);
        {
            let _guard = crypto.address_lock.lock().unwrap();
            let mut book = crypto.load_addresses()?;
            if book.availability.get(number).map_or(0, |e| e.revision) != revision {
                drop(_guard);
                return self.recipient_status(session, number);
            }
            let mut entry = book.availability.get(number).cloned().unwrap_or_default();
            match &result {
                Ok(user) => {
                    book.remember_phone(user, number);
                    entry.state = Availability::Available;
                    entry.checked_at = time;
                    entry.retry_at = 0;
                    entry.revision += 1;
                }
                Err(error)
                    if error
                        .downcast_ref::<HttpFailure>()
                        .is_some_and(|e| e.status == 404) =>
                {
                    entry.state = Availability::Unavailable;
                    entry.checked_at = time;
                    entry.retry_at = 0;
                    entry.revision += 1;
                }
                Err(error) => {
                    if let Some(http) = error
                        .downcast_ref::<HttpFailure>()
                        .filter(|e| e.status == 429)
                    {
                        book.lookup_retry_at = time.saturating_add(
                            http.retry_after_seconds.unwrap_or(60).clamp(1, 86400) * 1000,
                        );
                    } else {
                        entry.retry_at = time.saturating_add(300_000);
                    }
                }
            }
            book.availability.insert(number.to_owned(), entry);
            crypto.save_addresses(&book)?;
        }
        if let Err(error) = result {
            if !error
                .downcast_ref::<HttpFailure>()
                .is_some_and(|e| e.status == 404)
            {
                return Err(error);
            }
        }
        self.recipient_status(session, number)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{
        io::{Read, Write},
        net::TcpListener,
        sync::{
            atomic::{AtomicUsize, Ordering},
            Arc,
        },
    };
    fn fixture(
        service: &str,
    ) -> Result<(
        Arc<Client>,
        Session,
        Arc<crate::encryption::EncryptedClient>,
    )> {
        let session: Session = serde_json::from_value(
            json!({"homeserver":"http://127.0.0.1:9","user_id":"@a:x","access_token":"test","device_id":"A"}),
        )?;
        let root =
            std::env::temp_dir().join(format!("dumber-discovery-test-{}", uuid::Uuid::new_v4()));
        let password = "test-only-discovery-password-1234567890";
        let crypto = crate::encryption::offline_test_client(&session, &root, password)?;
        Ok((
            Arc::new(Client::new(service)?.with_crypto_store(root, password)),
            session,
            crypto,
        ))
    }
    fn server(
        responses: Vec<(u16, serde_json::Value)>,
    ) -> (String, Arc<AtomicUsize>, std::thread::JoinHandle<()>) {
        server_routes(
            responses
                .into_iter()
                .map(|(status, body)| ("/api/directory/lookup", status, body))
                .collect(),
        )
    }
    fn server_routes(
        responses: Vec<(&'static str, u16, serde_json::Value)>,
    ) -> (String, Arc<AtomicUsize>, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let calls = Arc::new(AtomicUsize::new(0));
        let count = calls.clone();
        let worker = std::thread::spawn(move || {
            for (path, status, body) in responses {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0; 8192];
                let read = socket.read(&mut request).unwrap();
                assert!(
                    String::from_utf8_lossy(&request[..read]).starts_with(&format!("POST {path} "))
                );
                count.fetch_add(1, Ordering::SeqCst);
                let body = body.to_string();
                write!(socket,"HTTP/1.1 {status} Reply\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            }
        });
        (url, calls, worker)
    }
    #[test]
    fn explicit_call_rechecks_a_cached_unavailable_recipient() -> Result<()> {
        let _runtime_guard = crate::encryption::runtime().enter();
        let (url, calls, server) = server_routes(vec![
            ("/api/directory/lookup", 404, json!({"detail":"absent"})),
            ("/api/directory/lookup", 200, json!({"user_id":"@b:x"})),
            ("/api/calls/lookup", 200, json!({"supported":true})),
        ]);
        let (client, session, crypto) = fixture(&url)?;
        assert_eq!(
            client
                .check_recipient(&session, "+15555550102", false)?
                .availability,
            Availability::Unavailable
        );
        assert!(client.call_capability(&session, "+15555550102")?.supported);
        assert_eq!(calls.load(Ordering::SeqCst), 3);
        assert!(crypto.load_addresses()?.room_metadata.is_empty());
        server.join().unwrap();
        Ok(())
    }
    #[test]
    fn concurrent_checks_reuse_positive_without_creating_rooms() -> Result<()> {
        let _runtime_guard = crate::encryption::runtime().enter();
        let (url, calls, server) = server(vec![(200, json!({"user_id":"@b:x"}))]);
        let (client, session, crypto) = fixture(&url)?;
        let workers: Vec<_> = (0..4)
            .map(|_| {
                let client = client.clone();
                let session = session.clone();
                std::thread::spawn(move || {
                    client
                        .check_recipient(&session, "+15555550102", false)
                        .unwrap()
                })
            })
            .collect();
        for worker in workers {
            assert_eq!(worker.join().unwrap().availability, Availability::Available);
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert!(crypto.load_addresses()?.room_metadata.is_empty());
        server.join().unwrap();
        Ok(())
    }
    #[test]
    fn negative_cache_survives_failed_refresh_and_historical_identity_resolution() -> Result<()> {
        let _runtime_guard = crate::encryption::runtime().enter();
        let (url, calls, server) = server(vec![
            (404, json!({"detail":"absent"})),
            (500, json!({"detail":"offline"})),
            (429, json!({"retry_after_seconds":120})),
        ]);
        let (client, session, crypto) = fixture(&url)?;
        assert_eq!(
            client
                .check_recipient(&session, "+15555550102", false)?
                .availability,
            Availability::Unavailable
        );
        client.check_recipient(&session, "+15555550102", false)?;
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert!(client
            .check_recipient(&session, "+15555550102", true)
            .is_err());
        {
            let _guard = crypto.address_lock.lock().unwrap();
            let mut book = crypto.load_addresses()?;
            book.remember_phone("@b:x", "+15555550102");
            crypto.save_addresses(&book)?;
        }
        assert_eq!(
            client
                .recipient_status(&session, "+15555550102")?
                .availability,
            Availability::Unavailable
        );
        assert!(client
            .check_recipient(&session, "+15555550103", true)
            .is_err());
        let status = client.check_recipient(&session, "+15555550104", true)?;
        assert_eq!(status.availability, Availability::Unknown);
        assert!(status.retry_at_ms > now());
        assert_eq!(calls.load(Ordering::SeqCst), 3);
        assert!(client.open_chat_cached(&session, "+15555550102").is_err());
        server.join().unwrap();
        Ok(())
    }
    #[test]
    fn stale_lookup_cannot_override_newer_rejection() -> Result<()> {
        let _runtime_guard = crate::encryption::runtime().enter();
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let (client, session, crypto) = fixture(&format!("http://{}", listener.local_addr()?))?;
        let copy = client.clone();
        let session_copy = session.clone();
        let lookup =
            std::thread::spawn(move || copy.check_recipient(&session_copy, "+15555550102", true));
        let (mut socket, _) = listener.accept()?;
        let mut buffer = [0; 8192];
        assert!(socket.read(&mut buffer)? > 0);
        {
            let _guard = crypto.address_lock.lock().unwrap();
            let mut book = crypto.load_addresses()?;
            book.availability.insert(
                "+15555550102".into(),
                Entry {
                    state: Availability::Unavailable,
                    revision: 1,
                    ..Default::default()
                },
            );
            crypto.save_addresses(&book)?;
        }
        let body = json!({"user_id":"@b:x"}).to_string();
        write!(socket,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len())?;
        assert_eq!(
            lookup.join().unwrap()?.availability,
            Availability::Unavailable
        );
        Ok(())
    }
}
