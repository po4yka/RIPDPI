use std::sync::{Arc, Mutex};

use android_support::{
    android_log_level_from_str, set_android_log_scope_level, throw_illegal_argument_env, throw_io_exception_env,
};
use jni::Env;
use jni::objects::JString;
use jni::sys::jlong;

use crate::telemetry::TunnelTelemetryState;

use super::super::registry::{SESSIONS, TunnelSession, TunnelSessionState};
use super::super::runtime::shared_tunnel_runtime;
use super::validation::parse_session_config;

pub(crate) fn create_session(env: &mut Env<'_>, config_json: JString) -> jlong {
    let parsed = match parse_session_config(env, config_json) {
        Ok(parsed) => parsed,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return 0;
        }
    };
    let config = Arc::new(parsed.config);
    let Some(native_log_level) = android_log_level_from_str(&config.misc.log_level) else {
        throw_illegal_argument_env(env, format!("Unsupported tunnel logLevel: {}", config.misc.log_level));
        return 0;
    };
    let runtime = match shared_tunnel_runtime() {
        Ok(runtime) => runtime,
        Err(err) => {
            throw_io_exception_env(env, format!("Failed to initialize Tokio runtime: {err}"));
            return 0;
        }
    };
    let telemetry = Arc::new(TunnelTelemetryState::new(parsed.log_context));
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);
    SESSIONS.insert(TunnelSession {
        runtime,
        config,
        last_error: Arc::new(Mutex::new(None)),
        telemetry,
        state: Mutex::new(TunnelSessionState::Ready),
    }) as jlong
}
