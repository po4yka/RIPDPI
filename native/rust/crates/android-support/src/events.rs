use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError, TryLockError};

use crossbeam_queue::ArrayQueue;

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
    pub attempt_id: Option<u64>,
    pub attempt_sequence: Option<u64>,
    pub stage: Option<String>,
    pub outcome: Option<String>,
    pub duration_ms: Option<u64>,
    pub failure_stage: Option<String>,
    pub failure_class: Option<String>,
    pub io_error_kind: Option<String>,
    pub os_error_code: Option<i32>,
    pub peer_close_phase: Option<String>,
    pub carrier_disposition: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayEventDrain {
    pub events: Vec<NativeEventRecord>,
    pub dropped_event_count: u64,
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
    operation_gate: Mutex<EventQueueState>,
    contended_drop_notices: ArrayQueue<String>,
    unattributed_contended_drops: AtomicU64,
}

#[derive(Default)]
struct EventQueueState {
    dropped_by_runtime: HashMap<String, u64>,
    dropped_runtime_order: VecDeque<String>,
}

impl EventQueue {
    fn bounded(capacity: usize) -> Self {
        let (sender, receiver) = flume::bounded(capacity.max(1));
        Self {
            sender,
            receiver,
            operation_gate: Mutex::new(EventQueueState::default()),
            contended_drop_notices: ArrayQueue::new(capacity.max(1)),
            unattributed_contended_drops: AtomicU64::new(0),
        }
    }

    fn push_drop_oldest_routed(&self, event: NativeEventRecord) {
        match self.sender.try_send(event) {
            Ok(()) => {}
            Err(flume::TrySendError::Full(event)) => match self.operation_gate.try_lock() {
                Ok(mut state) => {
                    self.flush_contended_drops(&mut state);
                    if let Ok(evicted) = self.receiver.try_recv()
                        && let Some(runtime_id) = evicted.runtime_id.as_ref()
                    {
                        increment_runtime_drop(&mut state, runtime_id, self.sender.capacity().unwrap_or(1));
                    }
                    let _ = self.sender.try_send(event);
                }
                Err(TryLockError::Poisoned(poisoned)) => {
                    let mut state = poisoned.into_inner();
                    self.flush_contended_drops(&mut state);
                    if let Some(runtime_id) = event.runtime_id.as_ref() {
                        increment_runtime_drop(&mut state, runtime_id, self.sender.capacity().unwrap_or(1));
                    }
                }
                Err(TryLockError::WouldBlock) => self.record_contended_drop(event.runtime_id),
            },
            Err(flume::TrySendError::Disconnected(_)) => {}
        }
    }

    fn push_drop_oldest_unrouted(&self, event: NativeEventRecord) {
        match self.sender.try_send(event) {
            Ok(()) => {}
            Err(flume::TrySendError::Full(event)) => {
                let _ = self.receiver.try_recv();
                let _ = self.sender.try_send(event);
            }
            Err(flume::TrySendError::Disconnected(_)) => {}
        }
    }

    fn drain(&self) -> Vec<NativeEventRecord> {
        self.receiver.try_iter().collect()
    }

    fn drain_routed(&self) -> Vec<NativeEventRecord> {
        let mut state = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.flush_contended_drops(&mut state);
        let events = self.drain();
        *state = EventQueueState::default();
        events
    }

    fn clear(&self) {
        for _ in self.receiver.try_iter() {}
    }

    fn clear_routed(&self) {
        let mut state = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.flush_contended_drops(&mut state);
        self.clear();
        *state = EventQueueState::default();
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

    fn drain_runtime(&self, runtime_id: &str) -> RelayEventDrain {
        let mut state = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.flush_contended_drops(&mut state);
        let (events, retained): (Vec<_>, Vec<_>) =
            self.receiver.try_iter().partition(|event| event.runtime_id.as_deref() == Some(runtime_id));
        for event in retained {
            if self.sender.try_send(event).is_err() {
                log::error!("failed to retain routed native event");
            }
        }
        // This is a cumulative runtime counter. Live and terminal snapshots can
        // overlap, so consumers take the maximum observed value instead of
        // summing samples and double-counting the same evictions.
        let dropped_event_count = state
            .dropped_by_runtime
            .get(runtime_id)
            .copied()
            .unwrap_or_default()
            .saturating_add(self.unattributed_contended_drops.load(Ordering::Relaxed));
        RelayEventDrain { events, dropped_event_count }
    }

    fn clear_matching(&self, predicate: impl FnMut(&NativeEventRecord) -> bool) {
        drop(self.drain_matching(predicate));
    }

    fn clear_runtime(&self, runtime_id: &str) {
        let mut state = self.operation_gate.lock().unwrap_or_else(PoisonError::into_inner);
        self.flush_contended_drops(&mut state);
        let (_, retained): (Vec<_>, Vec<_>) =
            self.receiver.try_iter().partition(|event| event.runtime_id.as_deref() == Some(runtime_id));
        for event in retained {
            if self.sender.try_send(event).is_err() {
                log::error!("failed to retain routed native event");
            }
        }
        state.dropped_by_runtime.remove(runtime_id);
        state.dropped_runtime_order.retain(|event_runtime_id| event_runtime_id != runtime_id);
    }

    fn record_contended_drop(&self, runtime_id: Option<String>) {
        if runtime_id.and_then(|value| self.contended_drop_notices.push(value).err()).is_some() {
            self.unattributed_contended_drops.fetch_add(1, Ordering::Relaxed);
        }
    }

    fn flush_contended_drops(&self, state: &mut EventQueueState) {
        while let Some(runtime_id) = self.contended_drop_notices.pop() {
            increment_runtime_drop(state, &runtime_id, self.sender.capacity().unwrap_or(1));
        }
    }
}

fn increment_runtime_drop(state: &mut EventQueueState, runtime_id: &str, capacity: usize) {
    if !state.dropped_by_runtime.contains_key(runtime_id) {
        while state.dropped_by_runtime.len() >= capacity {
            let Some(oldest) = state.dropped_runtime_order.pop_front() else {
                break;
            };
            state.dropped_by_runtime.remove(&oldest);
        }
        state.dropped_runtime_order.push_back(runtime_id.to_string());
    }
    *state.dropped_by_runtime.entry(runtime_id.to_string()).or_default() += 1;
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

    pub fn drain_relay_for_runtime(&self, runtime_id: &str) -> RelayEventDrain {
        self.inner.relay.drain_runtime(runtime_id)
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
            EventRing::Relay | EventRing::Diagnostics => self.ring(ring).push_drop_oldest_routed(event),
            _ => self.ring(ring).push_drop_oldest_unrouted(event),
        }
    }

    fn drain(&self, ring: EventRing) -> Vec<NativeEventRecord> {
        match ring {
            EventRing::Relay | EventRing::Diagnostics => self.ring(ring).drain_routed(),
            _ => self.ring(ring).drain(),
        }
    }

    fn clear(&self, ring: EventRing) {
        match ring {
            EventRing::Relay | EventRing::Diagnostics => self.ring(ring).clear_routed(),
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

pub fn drain_relay_events_for_runtime(runtime_id: &str) -> RelayEventDrain {
    global_event_rings().drain_relay_for_runtime(runtime_id)
}

pub fn clear_relay_events_for_runtime(runtime_id: &str) {
    global_event_rings().inner.relay.clear_runtime(runtime_id);
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
    use tracing_subscriber::prelude::*;

    use super::{EventRing, EventRingBuffers, NativeEventRecord, RingConfig};
    use crate::EventRingLayer;

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
            attempt_id: None,
            attempt_sequence: None,
            stage: None,
            outcome: None,
            duration_ms: None,
            failure_stage: None,
            failure_class: None,
            io_error_kind: None,
            os_error_code: None,
            peer_close_phase: None,
            carrier_disposition: None,
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
    fn relay_runtime_drain_preserves_fifo_and_reports_its_evictions() {
        let rings = EventRingBuffers::new(RingConfig { relay_capacity: 4, ..RingConfig::default() });
        let relay_stage = |runtime_id: &str, attempt_id: u64, sequence: u64, message: &str| NativeEventRecord {
            source: "relay-test".to_string(),
            level: "info".to_string(),
            message: message.to_string(),
            created_at: sequence,
            kind: Some("relay_attempt_stage".to_string()),
            runtime_id: Some(runtime_id.to_string()),
            mode: None,
            policy_signature: None,
            fingerprint_hash: None,
            diagnostics_session_id: None,
            subsystem: Some("relay".to_string()),
            attempt_id: Some(attempt_id),
            attempt_sequence: Some(sequence),
            stage: Some("reality_tls".to_string()),
            outcome: Some(if message.ends_with("failed") { "failed" } else { "succeeded" }.to_string()),
            duration_ms: Some(sequence * 10),
            failure_stage: message.ends_with("failed").then(|| "reality_tls".to_string()),
            failure_class: message.ends_with("failed").then(|| "connection_reset".to_string()),
            io_error_kind: message.ends_with("failed").then(|| "connection_reset".to_string()),
            os_error_code: message.ends_with("failed").then_some(104),
            peer_close_phase: None,
            carrier_disposition: None,
        };

        rings.push(EventRing::Relay, relay_stage("runtime-a", 1, 1, "a-evicted"));
        rings.push(EventRing::Relay, relay_stage("runtime-b", 1, 1, "b-first"));
        rings.push(EventRing::Relay, relay_stage("runtime-a", 1, 2, "a-second"));
        rings.push(EventRing::Relay, relay_stage("runtime-b", 1, 2, "b-second"));
        rings.push(EventRing::Relay, relay_stage("runtime-a", 1, 3, "a-failed"));

        let runtime_a = rings.drain_relay_for_runtime("runtime-a");
        assert_eq!(runtime_a.dropped_event_count, 1);
        assert_eq!(
            runtime_a
                .events
                .into_iter()
                .map(|event| (event.message, event.attempt_sequence, event.failure_stage))
                .collect::<Vec<_>>(),
            [
                ("a-second".to_string(), Some(2), None),
                ("a-failed".to_string(), Some(3), Some("reality_tls".to_string())),
            ],
        );

        let runtime_b = rings.drain_relay_for_runtime("runtime-b");
        assert_eq!(runtime_b.dropped_event_count, 0);
        assert_eq!(
            runtime_b.events.into_iter().map(|event| event.message).collect::<Vec<_>>(),
            ["b-first", "b-second"],
        );
    }

    #[test]
    fn relay_runtime_poll_preserves_attempt_sequence_across_drains() {
        let rings = EventRingBuffers::default();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(rings.clone()));
        let _guard = tracing::subscriber::set_default(subscriber);
        let attempt = tracing::info_span!(
            "relay_attempt",
            ring = "relay",
            runtime_id = "runtime-sequence",
            subsystem = "relay",
            attempt_id = 42_u64,
        );
        let _entered = attempt.enter();

        tracing::info!(kind = "relay_attempt_stage", stage = "reality_tls", outcome = "started", "first");
        let first = rings.drain_relay_for_runtime("runtime-sequence");
        drop(_entered);
        for attempt_id in 100_u64..300 {
            let churn = tracing::info_span!(
                "relay_attempt",
                ring = "relay",
                runtime_id = "runtime-churn",
                subsystem = "relay",
                attempt_id,
            );
            let _churn_entered = churn.enter();
            tracing::info!(kind = "relay_attempt_stage", stage = "tcp_connect", outcome = "started", "churn");
        }
        let _entered = attempt.enter();
        tracing::info!(kind = "relay_attempt_stage", stage = "reality_tls", outcome = "succeeded", "second");
        let second = rings.drain_relay_for_runtime("runtime-sequence");

        assert_eq!(first.events.into_iter().map(|event| event.attempt_sequence).collect::<Vec<_>>(), [Some(1)]);
        assert_eq!(second.events.into_iter().map(|event| event.attempt_sequence).collect::<Vec<_>>(), [Some(2)]);
    }

    #[test]
    fn relay_internal_events_do_not_consume_attempt_stage_sequence() {
        let rings = EventRingBuffers::default();
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(rings.clone()));
        let _guard = tracing::subscriber::set_default(subscriber);
        let attempt = tracing::info_span!(
            "relay_attempt",
            ring = "relay",
            runtime_id = "runtime-sequence",
            subsystem = "relay",
            attempt_id = 42_u64,
        );
        let _entered = attempt.enter();

        tracing::info!(kind = "relay_attempt_stage", stage = "tcp_connect", outcome = "started", "stage-one");
        tracing::info!(kind = "relay_internal_marker", stage = "socket_probe", outcome = "started", "internal");
        tracing::info!(kind = "relay_attempt_stage", stage = "tcp_connect", outcome = "succeeded", "stage-two");

        let events = rings.drain_relay_for_runtime("runtime-sequence").events;

        assert_eq!(
            events
                .into_iter()
                .map(|event| (event.message, event.attempt_id, event.attempt_sequence))
                .collect::<Vec<_>>(),
            [
                ("stage-one".to_string(), Some(42), Some(1)),
                ("internal".to_string(), Some(42), None),
                ("stage-two".to_string(), Some(42), Some(2)),
            ],
        );
    }

    #[test]
    fn relay_emission_drops_without_blocking_when_runtime_drain_gate_is_contended() {
        let rings = EventRingBuffers::new(RingConfig { relay_capacity: 1, ..RingConfig::default() });
        let mut retained = event("retained");
        retained.runtime_id = Some("runtime-contended".to_string());
        rings.push(EventRing::Relay, retained);

        let _gate = rings.inner.relay.operation_gate.lock().expect("lock runtime drain gate");
        let mut dropped = event("dropped-without-waiting");
        dropped.runtime_id = Some("runtime-contended".to_string());
        rings.push(EventRing::Relay, dropped);
        drop(_gate);

        let drain = rings.drain_relay_for_runtime("runtime-contended");
        assert_eq!(drain.events.into_iter().map(|event| event.message).collect::<Vec<_>>(), ["retained"]);
        assert_eq!(drain.dropped_event_count, 1);
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
