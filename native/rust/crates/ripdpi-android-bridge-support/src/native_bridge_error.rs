//! Typed error payload carried alongside the existing Java exception
//! contracts.
//!
//! The Kotlin/Rust JNI boundary owns two error channels (`JNI_CONTRACT.md`
//! § 7): a Java exception class with a human-readable message, or a
//! sentinel return value. Both channels are stable and we keep them. As
//! the JNI surface grows, callers (telemetry, integration tests, future
//! features) want more than a message: a domain tag, a stable code, a
//! retryable flag, and forward-compatible room for additional fields.
//!
//! `NativeBridgeError` is that payload. It is serialised as a JSON object
//! appended to the existing exception message after the sentinel line
//! [`SENTINEL`] so:
//!
//! - **Callers that ignore the payload** read `Throwable.getMessage()` and
//!   see exactly the prefix string they always saw. The sentinel is on a
//!   subsequent line; existing log lines and equality checks that compare
//!   the leading message are unaffected.
//! - **Typed callers** split on [`SENTINEL`] and JSON-decode the trailer.
//!   The Kotlin counterpart lives in
//!   `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/NativeBridgeErrorPayload.kt`.
//! - **Forward compatibility.** Unknown future fields in the JSON are
//!   ignored by the Kotlin parser; the schemaVersion field documents
//!   which payload shape the producer emitted. Bumping
//!   [`SCHEMA_VERSION`] is reserved for **breaking** changes — additive
//!   fields stay on the same version.
//!
//! The payload is purely additive — no JNI method renames, no exception
//! class changes, no behavior change for callers that ignore it.

use serde_json::{Map, Value};

/// Current `NativeBridgeError` schema version. Increment only for a
/// breaking shape change. New optional fields are additive and stay on
/// the same version.
pub const SCHEMA_VERSION: u32 = 1;

/// Sentinel line that separates the human-readable exception message
/// from the appended JSON payload. The exact string is part of the wire
/// contract — keep it in sync with `NativeBridgeErrorPayload.SENTINEL`
/// on the Kotlin side.
pub const SENTINEL: &str = "---ripdpi-native-bridge-error---";

/// Coarse domain tag. Distinct from the JNI library: the relay domain
/// can be raised by helpers that live in `ripdpi-android` (e.g. a
/// relay-config validation called from the proxy bridge), and
/// `Diagnostics`, `Root`, and `Telemetry` callers all live across
/// multiple crates. The string form is what reaches the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NativeBridgeErrorDomain {
    Proxy,
    Tunnel,
    Relay,
    Diagnostics,
    Root,
    Telemetry,
}

impl NativeBridgeErrorDomain {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Proxy => "proxy",
            Self::Tunnel => "tunnel",
            Self::Relay => "relay",
            Self::Diagnostics => "diagnostics",
            Self::Root => "root",
            Self::Telemetry => "telemetry",
        }
    }
}

/// Typed error payload appended to a JNI exception message.
///
/// Construct with [`Self::new`] (domain + code + message) and decorate
/// with the optional builder methods as the call site has the
/// information. `retryable` defaults to `false` — flip it to `true` only
/// when the caller can reasonably retry the same operation without
/// further intervention (transient I/O, contention).
#[derive(Debug, Clone)]
pub struct NativeBridgeError {
    domain: NativeBridgeErrorDomain,
    code: String,
    message: String,
    cause_class: Option<String>,
    handle_state: Option<String>,
    retryable: bool,
}

impl NativeBridgeError {
    /// Construct a payload with the minimum required fields. `code`
    /// should be a short stable identifier (`"create_failed"`,
    /// `"start_panic"`, `"handle_unknown"`); `message` is the
    /// human-readable explanation that will also become the leading
    /// exception text via [`decorate_message`].
    pub fn new(domain: NativeBridgeErrorDomain, code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            domain,
            code: code.into(),
            message: message.into(),
            cause_class: None,
            handle_state: None,
            retryable: false,
        }
    }

    /// Stamp the Java class name of the underlying cause (e.g.
    /// `"java.io.IOException"`). The native side rarely throws with a
    /// `Throwable` cause set — this field documents the conceptual
    /// cause class for telemetry / debug.
    #[must_use]
    pub fn with_cause_class(mut self, cause_class: impl Into<String>) -> Self {
        self.cause_class = Some(cause_class.into());
        self
    }

    /// Stamp the runtime state machine slot the operation was rejected
    /// from (`"idle"`, `"running"`, `"destroyed"`, `"unknown_handle"`,
    /// `"invalid_handle"`). Useful when the same `code` can fire from
    /// multiple states.
    #[must_use]
    pub fn with_handle_state(mut self, handle_state: impl Into<String>) -> Self {
        self.handle_state = Some(handle_state.into());
        self
    }

    /// Mark the failure as safely retryable by the caller without
    /// changing inputs. Defaults to `false`. Lifecycle violations
    /// (already running, destroyed, bad handle) are **not** retryable
    /// by definition.
    #[must_use]
    pub fn retryable(mut self, retryable: bool) -> Self {
        self.retryable = retryable;
        self
    }

    pub fn domain(&self) -> NativeBridgeErrorDomain {
        self.domain
    }
    pub fn code(&self) -> &str {
        &self.code
    }
    pub fn message(&self) -> &str {
        &self.message
    }

    /// Serialise the payload to its canonical JSON form. Optional
    /// fields are omitted when absent; ordering is stable so the
    /// fixture-based tests can pin it.
    pub fn to_json(&self) -> String {
        let mut obj = Map::with_capacity(7);
        obj.insert("schemaVersion".into(), Value::from(SCHEMA_VERSION));
        obj.insert("domain".into(), Value::from(self.domain.as_str()));
        obj.insert("code".into(), Value::from(self.code.clone()));
        obj.insert("message".into(), Value::from(self.message.clone()));
        if let Some(cause) = &self.cause_class {
            obj.insert("causeClass".into(), Value::from(cause.clone()));
        }
        if let Some(state) = &self.handle_state {
            obj.insert("handleState".into(), Value::from(state.clone()));
        }
        obj.insert("retryable".into(), Value::from(self.retryable));
        Value::Object(obj).to_string()
    }
}

/// Decorate a human-readable message with the typed payload trailer.
///
/// The result has the shape:
///
/// ```text
/// {human}
/// ---ripdpi-native-bridge-error---
/// {json}
/// ```
///
/// Callers that read `Throwable.getMessage()` and only look at the
/// leading line are unaffected; typed callers split on [`SENTINEL`].
pub fn decorate_message(human: &str, bridge: &NativeBridgeError) -> String {
    format!("{human}\n{SENTINEL}\n{}", bridge.to_json())
}
