use std::sync::Arc;

use ripdpi_apps_script_core::{AppsScriptRuntimeConfig, RelayRuntime as AppsScriptRelayRuntime};
use ripdpi_relay_core::{RelayRuntime as StandardRelayRuntime, ResolvedRelayRuntimeConfig};

#[derive(Clone)]
pub(crate) enum SessionRuntime {
    Standard(Arc<StandardRelayRuntime>),
    AppsScript(Arc<AppsScriptRelayRuntime>),
}

impl SessionRuntime {
    pub(crate) async fn run(&self) -> std::io::Result<()> {
        match self {
            Self::Standard(session) => session.clone().run().await,
            Self::AppsScript(session) => session.clone().run().await,
        }
    }

    pub(crate) fn stop(&self) {
        match self {
            Self::Standard(session) => session.stop(),
            Self::AppsScript(session) => session.stop(),
        }
    }

    /// Install a one-shot readiness observer (ADR 0003). Returns `true` if the
    /// backend supports a native readiness event. The Apps Script relay has no
    /// such event, so it returns `false` and the Kotlin wrapper keeps polling
    /// telemetry for that backend.
    pub(crate) fn set_readiness_observer(&self, observer: Arc<dyn Fn() + Send + Sync>) -> bool {
        match self {
            Self::Standard(session) => {
                session.set_readiness_observer(observer);
                true
            }
            Self::AppsScript(_) => false,
        }
    }
}

pub(crate) fn create_session(config_json: &str) -> Option<SessionRuntime> {
    if relay_kind(config_json).as_deref() == Some("google_apps_script") {
        let config = AppsScriptRuntimeConfig::from_json(config_json).ok()?;
        Some(SessionRuntime::AppsScript(AppsScriptRelayRuntime::new(config)))
    } else {
        let config = serde_json::from_str::<ResolvedRelayRuntimeConfig>(config_json).ok()?;
        Some(SessionRuntime::Standard(StandardRelayRuntime::new(config)))
    }
}

fn relay_kind(config_json: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(config_json).ok()?;
    value.get("kind")?.as_str().map(ToOwned::to_owned)
}
