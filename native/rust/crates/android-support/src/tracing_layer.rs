use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use tracing::Subscriber;
use tracing_subscriber::layer::{Context, Layer};
use tracing_subscriber::registry::LookupSpan;

use crate::events::{EventRing, EventRingBuffers, NativeEventRecord, global_event_rings};

#[cfg(target_os = "android")]
pub(crate) struct AndroidLogLayer;

#[cfg(target_os = "android")]
impl<S> Layer<S> for AndroidLogLayer
where
    S: Subscriber + for<'span> LookupSpan<'span>,
{
    fn on_event(&self, event: &tracing::Event<'_>, ctx: Context<'_, S>) {
        let metadata = event.metadata();
        let level = tracing_log::AsLog::as_log(metadata.level());
        if level > log::max_level() {
            return;
        }
        let mut visitor = MessageFieldFormatter::default();
        visitor.record_span_fields(event, &ctx);
        event.record(&mut visitor);
        if let Some(message) = visitor.finish_logcat_event(metadata.target()) {
            log::log!(target: metadata.target(), level, "{message}");
        }
    }
}

#[derive(Clone)]
pub struct EventRingLayer {
    buffers: EventRingBuffers,
}

impl EventRingLayer {
    pub fn new(buffers: EventRingBuffers) -> Self {
        Self { buffers }
    }

    pub fn global() -> Self {
        Self::new(global_event_rings().clone())
    }
}

impl<S> Layer<S> for EventRingLayer
where
    S: Subscriber + for<'span> LookupSpan<'span>,
{
    fn on_new_span(&self, attrs: &tracing::span::Attributes<'_>, id: &tracing::span::Id, ctx: Context<'_, S>) {
        let Some(span) = ctx.span(id) else {
            return;
        };
        let mut visitor = MessageFieldFormatter::default();
        attrs.record(&mut visitor);
        span.extensions_mut().insert(InheritedEventFields::from(&visitor));
    }

    fn on_event(&self, event: &tracing::Event<'_>, ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        let attempt_sequence = visitor.record_span_fields(event, &ctx);
        event.record(&mut visitor);
        let is_relay_attempt_stage = visitor.kind.as_deref() == Some("relay_attempt_stage");
        if is_relay_attempt_stage && visitor.attempt_id.is_some() && visitor.attempt_sequence.is_none() {
            // Ordering: sequence values only need unique monotonic numbering for one attempt.
            // The event record carries all published state separately.
            visitor.attempt_sequence =
                attempt_sequence.map(|sequence| sequence.fetch_add(1, Ordering::Relaxed).saturating_add(1));
        } else if !is_relay_attempt_stage {
            visitor.attempt_sequence = None;
        }
        let metadata = event.metadata();
        let Some(ring) = visitor.ring().as_deref().and_then(EventRing::from_routing_field) else {
            return;
        };

        self.buffers.push(
            ring,
            NativeEventRecord {
                source: visitor.source().unwrap_or_else(|| metadata.target().to_string()),
                level: metadata.level().as_str().to_ascii_lowercase(),
                message: visitor.message_or_target(metadata.target()),
                created_at: now_ms(),
                kind: visitor.kind(),
                runtime_id: visitor.runtime_id(),
                mode: visitor.mode().map(|value| value.to_ascii_lowercase()),
                policy_signature: visitor.policy_signature(),
                fingerprint_hash: visitor.fingerprint_hash(),
                diagnostics_session_id: visitor.diagnostics_session_id(),
                subsystem: visitor.subsystem().or_else(|| Some(ring.default_subsystem().to_string())),
                attempt_id: visitor.attempt_id,
                attempt_sequence: visitor.attempt_sequence,
                stage: visitor.stage.clone(),
                outcome: visitor.outcome.clone(),
                duration_ms: visitor.duration_ms,
                failure_stage: visitor.failure_stage.clone(),
                failure_class: visitor.failure_class.clone(),
                io_error_kind: visitor.io_error_kind.clone(),
                os_error_code: visitor.os_error_code,
                peer_close_phase: visitor.peer_close_phase.clone(),
                carrier_disposition: visitor.carrier_disposition.clone(),
            },
        );
    }
}

#[derive(Clone, Default)]
struct InheritedEventFields {
    ring: Option<String>,
    subsystem: Option<String>,
    source: Option<String>,
    runtime_id: Option<String>,
    attempt_id: Option<u64>,
    attempt_sequence: Option<Arc<AtomicU64>>,
}

impl From<&MessageFieldFormatter> for InheritedEventFields {
    fn from(visitor: &MessageFieldFormatter) -> Self {
        Self {
            ring: visitor.ring.clone(),
            subsystem: visitor.subsystem.clone(),
            source: visitor.source.clone(),
            runtime_id: visitor.runtime_id.clone(),
            attempt_id: visitor.attempt_id,
            attempt_sequence: visitor.attempt_id.map(|_| Arc::new(AtomicU64::new(0))),
        }
    }
}

#[derive(Default)]
#[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
pub(crate) struct MessageFieldFormatter {
    message: Option<String>,
    logcat_message: Option<String>,
    visible_fields: Vec<(String, String)>,
    ring: Option<String>,
    subsystem: Option<String>,
    session: Option<String>,
    profile: Option<String>,
    path_mode: Option<String>,
    source: Option<String>,
    kind: Option<String>,
    runtime_id: Option<String>,
    mode: Option<String>,
    policy_signature: Option<String>,
    fingerprint_hash: Option<String>,
    diagnostics_session_id: Option<String>,
    attempt_id: Option<u64>,
    attempt_sequence: Option<u64>,
    stage: Option<String>,
    outcome: Option<String>,
    duration_ms: Option<u64>,
    failure_stage: Option<String>,
    failure_class: Option<String>,
    io_error_kind: Option<String>,
    os_error_code: Option<i32>,
    peer_close_phase: Option<String>,
    carrier_disposition: Option<String>,
}

impl MessageFieldFormatter {
    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn record_named_debug(&mut self, field: &str, value: &dyn fmt::Debug) {
        self.record_value(field, format!("{value:?}"));
    }

    #[cfg_attr(any(not(any(test, target_os = "android")), all(test, feature = "loom")), allow(dead_code))]
    pub(crate) fn record_named_str(&mut self, field: &str, value: &str) {
        self.record_value(field, value.to_string());
    }

    #[cfg_attr(any(not(any(test, target_os = "android")), all(test, feature = "loom")), allow(dead_code))]
    pub(crate) fn finish(self, target: &str) -> String {
        let mut parts = Vec::new();

        for prefix in [
            self.subsystem.as_ref().map(|value| format!("subsystem={value}")),
            self.session.as_ref().map(|value| format!("session={value}")),
            self.profile.as_ref().map(|value| format!("profile={value}")),
            self.path_mode.as_ref().map(|value| format!("pathMode={value}")),
            self.source.as_ref().map(|value| format!("source={value}")),
        ]
        .into_iter()
        .flatten()
        {
            parts.push(prefix);
        }

        if let Some(message) = self.logcat_message.or(self.message) {
            parts.push(message);
        }

        parts.extend(self.visible_fields.into_iter().map(|(field, value)| format!("{field}={value}")));
        parts.extend(
            [
                ("event", self.kind),
                ("runtimeId", self.runtime_id),
                ("diagnosticsSessionId", self.diagnostics_session_id),
                ("attemptId", self.attempt_id.map(|value| value.to_string())),
                ("stage", self.stage),
                ("outcome", self.outcome),
                ("failureStage", self.failure_stage),
                ("failureClass", self.failure_class),
                ("ioErrorKind", self.io_error_kind),
                ("osErrorCode", self.os_error_code.map(|value| value.to_string())),
                ("durationMs", self.duration_ms.map(|value| value.to_string())),
            ]
            .into_iter()
            .filter_map(|(field, value)| value.map(|value| format!("{field}={value}"))),
        );

        if parts.is_empty() { target.to_string() } else { parts.join(" ") }
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn message_or_target(&self, target: &str) -> String {
        self.message.clone().unwrap_or_else(|| target.to_string())
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn ring(&self) -> Option<String> {
        self.ring.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn subsystem(&self) -> Option<String> {
        self.subsystem.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn source(&self) -> Option<String> {
        self.source.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn kind(&self) -> Option<String> {
        self.kind.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn runtime_id(&self) -> Option<String> {
        self.runtime_id.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn mode(&self) -> Option<String> {
        self.mode.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn policy_signature(&self) -> Option<String> {
        self.policy_signature.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn fingerprint_hash(&self) -> Option<String> {
        self.fingerprint_hash.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn diagnostics_session_id(&self) -> Option<String> {
        self.diagnostics_session_id.clone()
    }

    #[cfg(any(target_os = "android", all(test, not(feature = "loom"))))]
    pub(crate) fn finish_logcat_event(self, target: &str) -> Option<String> {
        // Keep successful attempt stages in the ring. Logcat records the failing stage.
        if self.kind.as_deref() == Some("relay_attempt_stage") && self.failure_class.is_none() {
            return None;
        }
        Some(self.finish(target))
    }

    fn record_span_fields<S>(&mut self, event: &tracing::Event<'_>, ctx: &Context<'_, S>) -> Option<Arc<AtomicU64>>
    where
        S: Subscriber + for<'span> LookupSpan<'span>,
    {
        let mut attempt_sequence = None;
        if let Some(scope) = ctx.event_scope(event) {
            for span in scope.from_root() {
                if let Some(fields) = span.extensions().get::<InheritedEventFields>() {
                    self.inherit(fields);
                    if fields.attempt_id.is_some() {
                        attempt_sequence.clone_from(&fields.attempt_sequence);
                    }
                }
            }
        }
        attempt_sequence
    }

    fn inherit(&mut self, inherited: &InheritedEventFields) {
        if inherited.ring.is_some() {
            self.ring.clone_from(&inherited.ring);
        }
        if inherited.subsystem.is_some() {
            self.subsystem.clone_from(&inherited.subsystem);
        }
        if inherited.source.is_some() {
            self.source.clone_from(&inherited.source);
        }
        if inherited.runtime_id.is_some() {
            self.runtime_id.clone_from(&inherited.runtime_id);
        }
        if inherited.attempt_id.is_some() {
            self.attempt_id = inherited.attempt_id;
        }
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    fn record_value(&mut self, field: &str, value: String) {
        if field != "message" && value.trim().is_empty() {
            return;
        }
        match field {
            "message" => self.message = Some(value),
            "logcat_message" => self.logcat_message = Some(value),
            "ring" => self.ring = Some(value),
            "subsystem" => self.subsystem = Some(value),
            "session" => self.session = Some(value),
            "profile" => self.profile = Some(value),
            "path_mode" | "pathMode" => self.path_mode = Some(value),
            "source" => self.source = Some(value),
            "kind" => self.kind = Some(value),
            "runtime_id" | "runtimeId" => self.runtime_id = Some(value),
            "mode" => self.mode = Some(value),
            "policy_signature" | "policySignature" => self.policy_signature = Some(value),
            "fingerprint_hash" | "fingerprintHash" => self.fingerprint_hash = Some(value),
            "diagnostics_session_id" | "diagnosticsSessionId" => self.diagnostics_session_id = Some(value),
            "attempt_id" | "attemptId" => self.attempt_id = value.parse().ok(),
            "attempt_sequence" | "attemptSequence" => self.attempt_sequence = value.parse().ok(),
            "stage" => self.stage = Some(value),
            "outcome" => self.outcome = Some(value),
            "duration_ms" | "durationMs" => self.duration_ms = value.parse().ok(),
            "failure_stage" | "failureStage" => self.failure_stage = Some(value),
            "failure_class" | "failureClass" => self.failure_class = Some(value),
            "io_error_kind" | "ioErrorKind" => self.io_error_kind = Some(value),
            "os_error_code" | "osErrorCode" => self.os_error_code = value.parse().ok(),
            "peer_close_phase" | "peerClosePhase" => self.peer_close_phase = Some(value),
            "carrier_disposition" | "carrierDisposition" => self.carrier_disposition = Some(value),
            _ => self.visible_fields.push((field.to_string(), value)),
        }
    }
}

impl tracing::field::Visit for MessageFieldFormatter {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn fmt::Debug) {
        self.record_named_debug(field.name(), value);
    }

    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        self.record_named_str(field.name(), value);
    }

    fn record_u64(&mut self, field: &tracing::field::Field, value: u64) {
        self.record_value(field.name(), value.to_string());
    }

    fn record_i64(&mut self, field: &tracing::field::Field, value: i64) {
        self.record_value(field.name(), value.to_string());
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}
