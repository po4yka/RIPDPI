use std::sync::Arc;

use ripdpi_apps_script_core::{AppsScriptRuntimeConfig, RelayRuntime as AppsScriptRelayRuntime};
use ripdpi_relay_core::{
    RelayRuntime as StandardRelayRuntime, ResolvedRelayRuntimeConfig, SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION,
};

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

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct RelayConfigEnvelope {
    schema_version: u32,
    kind: String,
}

pub(crate) fn create_session(config_json: &str) -> Option<SessionRuntime> {
    let envelope: RelayConfigEnvelope = serde_json::from_str(config_json).ok()?;
    if envelope.schema_version != SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION {
        return None;
    }
    if envelope.kind == "google_apps_script" {
        let config = AppsScriptRuntimeConfig::from_json(config_json).ok()?;
        Some(SessionRuntime::AppsScript(AppsScriptRelayRuntime::new(config)))
    } else {
        let config = serde_json::from_str::<ResolvedRelayRuntimeConfig>(config_json).ok()?;
        Some(SessionRuntime::Standard(StandardRelayRuntime::new(config)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn config(kind: &str) -> Value {
        json!({
            "schemaVersion": 10,
            "enabled": true,
            "kind": kind,
            "profileId": "schema-fixture",
            "server": "127.0.0.1",
            "serverPort": 443,
            "serverName": "localhost",
            "localSocksHost": "127.0.0.1",
            "localSocksPort": 11980,
            "udpEnabled": false,
            "tcpFallbackEnabled": true,
            "tlsFingerprintProfile": "chrome_stable"
        })
    }

    fn apps_script_config() -> Value {
        let mut value = config("google_apps_script");
        value["appsScriptScriptIds"] = json!(["schema-fixture"]);
        value["appsScriptAuthKey"] = json!("schema-fixture-auth");
        value
    }

    #[test]
    fn current_schema_dispatches_standard_and_apps_script_sessions() {
        assert!(matches!(create_session(&config("trojan").to_string()), Some(SessionRuntime::Standard(_))));
        assert!(matches!(create_session(&apps_script_config().to_string()), Some(SessionRuntime::AppsScript(_))));
    }

    #[test]
    fn every_jni_relay_rejects_missing_noncurrent_or_malformed_schema() {
        for payload in [config("trojan"), apps_script_config()] {
            let mut missing = payload.clone();
            missing.as_object_mut().expect("object").remove("schemaVersion");
            assert!(create_session(&missing.to_string()).is_none(), "missing schema must fail");
            for schema in [json!(9), json!(11), json!(-1), json!(10.5), json!("10"), Value::Null] {
                let mut invalid = payload.clone();
                invalid["schemaVersion"] = schema;
                assert!(create_session(&invalid.to_string()).is_none(), "invalid schema must fail");
            }
        }
    }

    #[test]
    fn current_apps_script_schema_keeps_supported_aliases() {
        let payload = json!({"schemaVersion": 10, "kind": "google_apps_script", "script_ids": ["schema-fixture"], "auth_key": "schema-fixture-auth"});
        assert!(matches!(create_session(&payload.to_string()), Some(SessionRuntime::AppsScript(_))));
    }

    #[test]
    fn apps_script_rejects_duplicate_schema_and_kind_fields() {
        for payload in [
            r#"{"schemaVersion":9,"schemaVersion":10,"kind":"google_apps_script","scriptIds":["schema-fixture"],"authKey":"schema-fixture-auth"}"#,
            r#"{"schemaVersion":10,"kind":"trojan","kind":"google_apps_script","scriptIds":["schema-fixture"],"authKey":"schema-fixture-auth"}"#,
        ] {
            assert!(create_session(payload).is_none(), "duplicate envelope field must fail");
        }
    }
}
