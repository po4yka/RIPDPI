use std::sync::{Arc, Mutex};

use android_support::{
    android_log_level_from_debug_verbosity, android_log_level_from_str, set_android_log_scope_level,
};
use jni::objects::JString;
use jni::sys::jlong;
use jni::Env;
use log::LevelFilter;
use ripdpi_proxy_config::RuntimeConfigEnvelope;

use crate::config::{parse_proxy_config_json, runtime_config_envelope_from_payload};
use crate::lifecycle::proxy_error;
use crate::registry::{ProxySession, ProxySessionState, SESSIONS};
use ripdpi_android_bridge_support::{throw_illegal_argument_env_with_payload, JniProxyError};
use ripdpi_android_telemetry_adapter::ProxyTelemetryState;

pub(crate) fn create_session(env: &mut Env<'_>, config_json: JString) -> jlong {
    let Ok(json) = config_json.try_to_string(env) else {
        throw_illegal_argument_env_with_payload(
            env,
            "Invalid proxy config payload",
            &proxy_error("create_config_invalid", "Invalid proxy config payload")
                .with_cause_class("java.lang.IllegalArgumentException"),
        );
        return 0;
    };

    let payload = match parse_proxy_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("create_config_parse_failed", detail)
                    .with_cause_class("java.lang.IllegalArgumentException"),
            );
            return 0;
        }
    };

    let envelope = match runtime_config_envelope_from_payload(payload) {
        Ok(envelope) => envelope,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("create_config_envelope_failed", detail)
                    .with_cause_class("java.lang.IllegalArgumentException"),
            );
            return 0;
        }
    };
    let Some(native_log_level) = native_log_level(env, &envelope) else {
        return 0;
    };
    let config = envelope.config;

    if let Err(err) = ripdpi_proxy_runtime::create_listener(&config) {
        let detail = err.to_string();
        JniProxyError::Io(err).throw_with_payload(
            env,
            &proxy_error("create_listener_probe_failed", detail)
                .with_cause_class("java.io.IOException")
                .retryable(true),
        );
        return 0;
    }

    let autolearn_enabled = config.host_autolearn.enabled;
    let telemetry = Arc::new(ProxyTelemetryState::new(envelope.log_context.clone()));
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);
    telemetry.set_autolearn_state(autolearn_enabled, 0, 0, 0, None, None);

    SESSIONS.insert(ProxySession {
        config,
        runtime_context: envelope.runtime_context,
        telemetry,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
}

fn native_log_level(env: &mut Env<'_>, envelope: &RuntimeConfigEnvelope) -> Option<LevelFilter> {
    match envelope.native_log_level.as_deref() {
        Some(value) => android_log_level_from_str(value).or_else(|| {
            let detail = format!("Unsupported proxy nativeLogLevel: {value}");
            throw_illegal_argument_env_with_payload(
                env,
                &detail,
                &proxy_error("create_unsupported_log_level", detail.clone())
                    .with_cause_class("java.lang.IllegalArgumentException"),
            );
            None
        }),
        None => Some(android_log_level_from_debug_verbosity(envelope.config.process.debug)),
    }
}
