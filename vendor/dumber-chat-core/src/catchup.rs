//! Private delivery catch-up. Cursors never cross the public/JNI API.
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, VecDeque};

#[derive(Default, Serialize, Deserialize)]
pub(crate) struct CatchUp {
    heads: BTreeMap<String, String>,
    pub jobs: VecDeque<Gap>,
    #[serde(default)]
    pub retry_after_ms: u64,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Gap {
    pub room: String,
    pub from: String,
    stop_at: Option<String>,
}

impl CatchUp {
    pub fn observe(&mut self, room: &str, limited: bool, from: Option<&str>, events: &[Value]) {
        if limited {
            if let Some(from) = from {
                if !self
                    .jobs
                    .iter()
                    .any(|job| job.room == room && job.from == from)
                {
                    self.jobs.push_back(Gap {
                        room: room.into(),
                        from: from.into(),
                        stop_at: self.heads.get(room).cloned(),
                    });
                }
            }
        }
        if let Some(id) = events
            .iter()
            .rev()
            .find_map(|event| event["event_id"].as_str())
        {
            self.heads.insert(room.into(), id.into());
        }
    }

    pub fn leave(&mut self, room: &str) {
        self.jobs.retain(|job| job.room != room);
    }
}

impl Gap {
    /// Matrix pages run backwards. Return older missed events in chronological
    /// order and stop at the previous live head, without replaying earlier history.
    pub fn page(&self, mut events: Vec<Value>, end: Option<String>) -> (Vec<Value>, Option<Self>) {
        let boundary = self.stop_at.as_deref().and_then(|id| {
            events
                .iter()
                .position(|event| event["event_id"].as_str() == Some(id))
        });
        if let Some(index) = boundary {
            events.truncate(index);
        }
        events.reverse();
        let next = if boundary.is_some() {
            None
        } else {
            end.filter(|end| end != &self.from).map(|from| Self {
                from,
                ..self.clone()
            })
        };
        // Empty pages can still have a continuation token (e.g. expired events).
        (events, next)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn event(id: &str) -> Value {
        json!({"event_id":id})
    }

    #[test]
    fn gap_survives_restart_and_stops_at_last_delivered_event() {
        let mut state = CatchUp::default();
        state.observe("room", false, None, &[event("old")]);
        state.observe("room", true, Some("cursor"), &[event("new")]);
        let mut restored: CatchUp =
            serde_json::from_slice(&serde_json::to_vec(&state).unwrap()).unwrap();
        let gap = restored.jobs.pop_front().unwrap();
        let (events, next) = gap.page(
            vec![event("middle"), event("old"), event("older")],
            Some("more".into()),
        );
        assert_eq!(events, vec![event("middle")]);
        assert!(next.is_none());
    }

    #[test]
    fn empty_filtered_pages_continue_and_expired_anchor_does_not_loop() {
        let gap = Gap {
            room: "room".into(),
            from: "one".into(),
            stop_at: Some("expired".into()),
        };
        let (events, next) = gap.page(vec![], Some("two".into()));
        assert!(events.is_empty());
        let next = next.unwrap();
        assert_eq!(next.from, "two");
        assert!(next.page(vec![event("remaining")], None).1.is_none());
        assert!(gap.page(vec![], Some("one".into())).1.is_none());
    }

    #[test]
    fn initial_catchup_is_chronological_and_jobs_are_room_scoped() {
        let mut state = CatchUp::default();
        state.observe("a", true, Some("one"), &[event("a-new")]);
        state.observe("a", true, Some("one"), &[event("a-new")]);
        state.observe("b", true, Some("two"), &[event("b-new")]);
        assert_eq!(state.jobs.len(), 2);
        state.leave("b");
        let gap = state.jobs.pop_front().unwrap();
        let (events, next) = gap.page(vec![event("second"), event("first")], None);
        assert_eq!(events, vec![event("first"), event("second")]);
        assert!(next.is_none());
    }
}
