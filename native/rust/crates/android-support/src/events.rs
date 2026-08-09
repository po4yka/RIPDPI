use std::sync::{Arc, Mutex, OnceLock, PoisonError};

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
            "warp" | "amneziawg" => Some(Self::Warp),
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
    proxy: EventQueue,
    relay: EventQueue,
    warp: EventQueue,
    tunnel: EventQueue,
    diagnostics: EventQueue,
}

struct EventQueue {
    sender: flume::Sender<NativeEventRecord>,
    receiver: flume::Receiver<NativeEventRecord>,
    operation_gate: Mutex<()>,
}

impl EventQueue {
    fn bounded(capacity: usize) -> Self {
        let (sender, receiver) = flume::bounded(capacity.max(1));
        Self { sender, receiver, operation_gate: Mutex::new(()) }
    }

    fn push_drop_oldest(&self, event: NativeEventRecord) {
        match self.sender.try_send(event) {
            Ok(()) => {}
            Err(flume::TrySendError::Full(event)) => {
                let _ = self.receiver.try_recv();
                let _ = self.sender.try_send(event);
            }
            Err(flume::TrySendError::Disconnected(_)) => {}
        }
    }

    fn push_drop_oldest_routed(&self, event: NativeEventRecord) {
        let _guard = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.push_drop_oldest(event);
    }

    fn drain(&self) -> Vec<NativeEventRecord> {
        self.receiver.try_iter().collect()
    }

    fn drain_routed(&self) -> Vec<NativeEventRecord> {
        let _guard = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.drain()
    }

    fn clear(&self) {
        for _ in self.receiver.try_iter() {}
    }

    fn clear_routed(&self) {
        let _guard = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.clear();
    }

    fn drain_matching(&self, mut predicate: impl FnMut(&NativeEventRecord) -> bool) -> Vec<NativeEventRecord> {
        let _guard = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        let (matching, retained): (Vec<_>, Vec<_>) = self.receiver.try_iter().partition(|event| predicate(event));
        for event in retained {
            if self.sender.try_send(event).is_err() {
                log::error!("failed to retain routed native event");
            }
        }
        matching
    }

    fn clear_matching(&self, predicate: impl FnMut(&NativeEventRecord) -> bool) {
        drop(self.drain_matching(predicate));
    }
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
                proxy: EventQueue::bounded(config.proxy_capacity),
                relay: EventQueue::bounded(config.relay_capacity),
                warp: EventQueue::bounded(config.warp_capacity),
                tunnel: EventQueue::bounded(config.tunnel_capacity),
                diagnostics: EventQueue::bounded(config.diagnostics_capacity),
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

    pub fn drain_diagnostics_for_session(&self, session_id: &str) -> Vec<NativeEventRecord> {
        self.inner.diagnostics.drain_matching(|event| event.diagnostics_session_id.as_deref() == Some(session_id))
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

    pub fn clear_diagnostics_for_session(&self, session_id: &str) {
        self.inner.diagnostics.clear_matching(|event| event.diagnostics_session_id.as_deref() == Some(session_id));
    }

    pub(crate) fn push(&self, ring: EventRing, event: NativeEventRecord) {
        match ring {
            EventRing::Diagnostics => self.inner.diagnostics.push_drop_oldest_routed(event),
            _ => self.ring(ring).push_drop_oldest(event),
        }
    }

    fn drain(&self, ring: EventRing) -> Vec<NativeEventRecord> {
        match ring {
            EventRing::Diagnostics => self.inner.diagnostics.drain_routed(),
            _ => self.ring(ring).drain(),
        }
    }

    fn clear(&self, ring: EventRing) {
        match ring {
            EventRing::Diagnostics => self.inner.diagnostics.clear_routed(),
            _ => self.ring(ring).clear(),
        }
    }

    fn ring(&self, ring: EventRing) -> &EventQueue {
        match ring {
            EventRing::Proxy => &self.inner.proxy,
            EventRing::Relay => &self.inner.relay,
            EventRing::Warp => &self.inner.warp,
            EventRing::Tunnel => &self.inner.tunnel,
            EventRing::Diagnostics => &self.inner.diagnostics,
        }
    }
}

pub(crate) fn global_event_rings() -> &'static EventRingBuffers {
    static EVENT_RINGS: OnceLock<EventRingBuffers> = OnceLock::new();
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

pub fn drain_diagnostics_events_for_session(session_id: &str) -> Vec<NativeEventRecord> {
    global_event_rings().drain_diagnostics_for_session(session_id)
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

pub fn clear_diagnostics_events_for_session(session_id: &str) {
    global_event_rings().clear_diagnostics_for_session(session_id);
}

#[cfg(test)]
mod tests {
    use super::{EventRing, EventRingBuffers, NativeEventRecord, RingConfig};

    fn event(message: &str) -> NativeEventRecord {
        NativeEventRecord {
            source: "test".to_string(),
            level: "info".to_string(),
            message: message.to_string(),
            created_at: 0,
            kind: None,
            runtime_id: None,
            mode: None,
            policy_signature: None,
            fingerprint_hash: None,
            diagnostics_session_id: None,
            subsystem: None,
        }
    }

    #[test]
    fn bounded_queue_drops_oldest_event() {
        let rings = EventRingBuffers::new(RingConfig { proxy_capacity: 2, ..RingConfig::default() });

        rings.push(EventRing::Proxy, event("first"));
        rings.push(EventRing::Proxy, event("second"));
        rings.push(EventRing::Proxy, event("third"));

        let messages: Vec<_> = rings.drain_proxy().into_iter().map(|event| event.message).collect();
        assert_eq!(messages, ["second", "third"]);
    }

    #[test]
    fn clear_drains_events_without_returning_them() {
        let rings = EventRingBuffers::new(RingConfig::default());

        rings.push(EventRing::Diagnostics, event("diagnostic"));
        rings.clear_diagnostics();

        assert!(rings.drain_diagnostics().is_empty());
    }

    #[test]
    fn diagnostics_session_drain_preserves_parallel_session_events() {
        let rings = EventRingBuffers::new(RingConfig::default());
        let mut stage_a_first = event("stage-a-first");
        stage_a_first.diagnostics_session_id = Some("stage-a".to_string());
        let mut stage_b = event("stage-b");
        stage_b.diagnostics_session_id = Some("stage-b".to_string());
        let mut stage_a_second = event("stage-a-second");
        stage_a_second.diagnostics_session_id = Some("stage-a".to_string());

        rings.push(EventRing::Diagnostics, stage_a_first);
        rings.push(EventRing::Diagnostics, stage_b);
        rings.push(EventRing::Diagnostics, stage_a_second);

        let stage_a_rings = rings.clone();
        let stage_a = std::thread::spawn(move || stage_a_rings.drain_diagnostics_for_session("stage-a"));
        let stage_b_rings = rings.clone();
        let stage_b = std::thread::spawn(move || stage_b_rings.drain_diagnostics_for_session("stage-b"));
        let stage_a_messages =
            stage_a.join().expect("stage-a drain").into_iter().map(|event| event.message).collect::<Vec<_>>();
        let stage_b_messages =
            stage_b.join().expect("stage-b drain").into_iter().map(|event| event.message).collect::<Vec<_>>();

        assert_eq!(stage_a_messages, ["stage-a-first", "stage-a-second"]);
        assert_eq!(stage_b_messages, ["stage-b"]);
    }

    #[test]
    fn diagnostics_session_clear_preserves_parallel_session_events() {
        let rings = EventRingBuffers::new(RingConfig::default());
        let mut stage_a = event("stale-stage-a");
        stage_a.diagnostics_session_id = Some("stage-a".to_string());
        let mut stage_b = event("active-stage-b");
        stage_b.diagnostics_session_id = Some("stage-b".to_string());
        rings.push(EventRing::Diagnostics, stage_a);
        rings.push(EventRing::Diagnostics, stage_b);

        rings.clear_diagnostics_for_session("stage-a");

        assert!(rings.drain_diagnostics_for_session("stage-a").is_empty());
        assert_eq!(
            rings.drain_diagnostics_for_session("stage-b").into_iter().map(|event| event.message).collect::<Vec<_>>(),
            ["active-stage-b"],
        );
    }

    #[test]
    fn routing_domains_include_amneziawg_on_the_warp_ring() {
        assert_eq!(EventRing::from_routing_field("proxy"), Some(EventRing::Proxy));
        assert_eq!(EventRing::from_routing_field("relay"), Some(EventRing::Relay));
        assert_eq!(EventRing::from_routing_field("warp"), Some(EventRing::Warp));
        assert_eq!(EventRing::from_routing_field("amneziawg"), Some(EventRing::Warp));
        assert_eq!(EventRing::from_routing_field("tunnel"), Some(EventRing::Tunnel));
        assert_eq!(EventRing::from_routing_field("diagnostics"), Some(EventRing::Diagnostics));
        assert_eq!(EventRing::from_routing_field("monitor"), Some(EventRing::Diagnostics));
        assert_eq!(EventRing::from_routing_field("unknown"), None);
    }
}
