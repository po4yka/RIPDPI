use std::collections::VecDeque;
use std::sync::{Arc, PoisonError};

use once_cell::sync::OnceCell;

use crate::sync::Mutex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NativeEventRecord {
    pub source: String,
    pub level: String,
    pub message: String,
    pub created_at: u64,
    pub kind: Option<String>,
    pub runtime_id: Option<String>,
    pub mode: Option<String>,
    pub policy_signature: Option<String>,
    pub fingerprint_hash: Option<String>,
    pub diagnostics_session_id: Option<String>,
    pub subsystem: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RingConfig {
    pub proxy_capacity: usize,
    pub relay_capacity: usize,
    pub warp_capacity: usize,
    pub tunnel_capacity: usize,
    pub diagnostics_capacity: usize,
}

impl Default for RingConfig {
    fn default() -> Self {
        Self {
            proxy_capacity: 128,
            relay_capacity: 128,
            warp_capacity: 128,
            tunnel_capacity: 128,
            diagnostics_capacity: 256,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EventRing {
    Proxy,
    Relay,
    Warp,
    Tunnel,
    Diagnostics,
}

impl EventRing {
    pub(crate) fn from_routing_field(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "proxy" => Some(Self::Proxy),
            "relay" => Some(Self::Relay),
            "warp" => Some(Self::Warp),
            "tunnel" => Some(Self::Tunnel),
            "diagnostics" | "monitor" => Some(Self::Diagnostics),
            _ => None,
        }
    }

    pub(crate) fn default_subsystem(self) -> &'static str {
        match self {
            Self::Proxy => "proxy",
            Self::Relay => "relay",
            Self::Warp => "warp",
            Self::Tunnel => "tunnel",
            Self::Diagnostics => "diagnostics",
        }
    }
}

struct EventRingBuffersInner {
    config: RingConfig,
    proxy: Mutex<VecDeque<NativeEventRecord>>,
    relay: Mutex<VecDeque<NativeEventRecord>>,
    warp: Mutex<VecDeque<NativeEventRecord>>,
    tunnel: Mutex<VecDeque<NativeEventRecord>>,
    diagnostics: Mutex<VecDeque<NativeEventRecord>>,
}

#[derive(Clone)]
pub struct EventRingBuffers {
    inner: Arc<EventRingBuffersInner>,
}

impl Default for EventRingBuffers {
    fn default() -> Self {
        Self::new(RingConfig::default())
    }
}

impl EventRingBuffers {
    pub fn new(config: RingConfig) -> Self {
        Self {
            inner: Arc::new(EventRingBuffersInner {
                proxy: Mutex::new(VecDeque::with_capacity(config.proxy_capacity)),
                relay: Mutex::new(VecDeque::with_capacity(config.relay_capacity)),
                warp: Mutex::new(VecDeque::with_capacity(config.warp_capacity)),
                tunnel: Mutex::new(VecDeque::with_capacity(config.tunnel_capacity)),
                diagnostics: Mutex::new(VecDeque::with_capacity(config.diagnostics_capacity)),
                config,
            }),
        }
    }

    pub fn drain_proxy(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Proxy)
    }

    pub fn drain_relay(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Relay)
    }

    pub fn drain_warp(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Warp)
    }

    pub fn drain_tunnel(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Tunnel)
    }

    pub fn drain_diagnostics(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Diagnostics)
    }

    pub fn clear_proxy(&self) {
        self.clear(EventRing::Proxy);
    }

    pub fn clear_relay(&self) {
        self.clear(EventRing::Relay);
    }

    pub fn clear_warp(&self) {
        self.clear(EventRing::Warp);
    }

    pub fn clear_tunnel(&self) {
        self.clear(EventRing::Tunnel);
    }

    pub fn clear_diagnostics(&self) {
        self.clear(EventRing::Diagnostics);
    }

    pub(crate) fn push(&self, ring: EventRing, event: NativeEventRecord) {
        let capacity = self.capacity(ring);
        let mut guard = self.ring(ring).lock().unwrap_or_else(PoisonError::into_inner);
        if guard.len() >= capacity {
            guard.pop_front();
        }
        guard.push_back(event);
    }

    fn drain(&self, ring: EventRing) -> Vec<NativeEventRecord> {
        self.ring(ring).lock().unwrap_or_else(PoisonError::into_inner).drain(..).collect()
    }

    fn clear(&self, ring: EventRing) {
        self.ring(ring).lock().unwrap_or_else(PoisonError::into_inner).clear();
    }

    fn ring(&self, ring: EventRing) -> &Mutex<VecDeque<NativeEventRecord>> {
        match ring {
            EventRing::Proxy => &self.inner.proxy,
            EventRing::Relay => &self.inner.relay,
            EventRing::Warp => &self.inner.warp,
            EventRing::Tunnel => &self.inner.tunnel,
            EventRing::Diagnostics => &self.inner.diagnostics,
        }
    }

    fn capacity(&self, ring: EventRing) -> usize {
        match ring {
            EventRing::Proxy => self.inner.config.proxy_capacity,
            EventRing::Relay => self.inner.config.relay_capacity,
            EventRing::Warp => self.inner.config.warp_capacity,
            EventRing::Tunnel => self.inner.config.tunnel_capacity,
            EventRing::Diagnostics => self.inner.config.diagnostics_capacity,
        }
    }
}

pub(crate) fn global_event_rings() -> &'static EventRingBuffers {
    static EVENT_RINGS: OnceCell<EventRingBuffers> = OnceCell::new();
    EVENT_RINGS.get_or_init(EventRingBuffers::default)
}

pub fn drain_proxy_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_proxy()
}

pub fn drain_relay_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_relay()
}

pub fn drain_warp_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_warp()
}

pub fn drain_tunnel_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_tunnel()
}

pub fn drain_diagnostics_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_diagnostics()
}

pub fn clear_proxy_events() {
    global_event_rings().clear_proxy();
}

pub fn clear_relay_events() {
    global_event_rings().clear_relay();
}

pub fn clear_warp_events() {
    global_event_rings().clear_warp();
}

pub fn clear_tunnel_events() {
    global_event_rings().clear_tunnel();
}

pub fn clear_diagnostics_events() {
    global_event_rings().clear_diagnostics();
}
