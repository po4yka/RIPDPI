use std::fmt;

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
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        event.record(&mut visitor);
        let metadata = event.metadata();
        let message = visitor.finish(metadata.target());

        match *metadata.level() {
            tracing::Level::ERROR => log::error!("{message}"),
            tracing::Level::WARN => log::warn!("{message}"),
            tracing::Level::INFO => log::info!("{message}"),
            tracing::Level::DEBUG => log::debug!("{message}"),
            tracing::Level::TRACE => log::trace!("{message}"),
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
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        event.record(&mut visitor);
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
            },
        );
    }
}

#[derive(Default)]
#[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
pub(crate) struct MessageFieldFormatter {
    message: Option<String>,
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

        if let Some(message) = self.message {
            parts.push(message);
        }

        for (field, value) in self.visible_fields {
            parts.push(format!("{field}={value}"));
        }

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

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    fn record_value(&mut self, field: &str, value: String) {
        if field != "message" && value.trim().is_empty() {
            return;
        }
        match field {
            "message" => self.message = Some(value),
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
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}
