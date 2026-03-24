use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::types::FixtureEvent;

#[derive(Clone)]
pub struct EventLog {
    inner: Arc<Mutex<Vec<FixtureEvent>>>,
}

impl EventLog {
    pub(crate) fn new() -> Self {
        Self { inner: Arc::new(Mutex::new(Vec::new())) }
    }

    pub fn record(&self, event: FixtureEvent) {
        if let Ok(mut events) = self.inner.lock() {
            events.push(event);
        }
    }

    pub fn snapshot(&self) -> Vec<FixtureEvent> {
        self.inner.lock().map(|events| events.clone()).unwrap_or_default()
    }

    pub fn clear(&self) {
        if let Ok(mut events) = self.inner.lock() {
            events.clear();
        }
    }
}

pub(crate) fn event(
    service: &str,
    protocol: &str,
    peer: SocketAddr,
    target: Option<SocketAddr>,
    detail: &str,
    bytes: usize,
    sni: Option<String>,
) -> FixtureEvent {
    FixtureEvent {
        service: service.to_string(),
        protocol: protocol.to_string(),
        peer: peer.to_string(),
        target: target.map_or_else(|| "unknown".to_string(), |addr| addr.to_string()),
        detail: detail.to_string(),
        bytes,
        sni,
        created_at: now_ms(),
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis().try_into().unwrap_or(u64::MAX)
}
