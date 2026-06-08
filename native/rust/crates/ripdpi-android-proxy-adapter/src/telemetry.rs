use android_support::throw_runtime_exception_env;
use jni::Env;
use jni::sys::{jlong, jstring};

use ripdpi_android_bridge_support::JniProxyError;

use super::registry::lookup_proxy_session;
use crate::quality_sink::quality_window;

pub(crate) fn poll_proxy_telemetry(env: &mut Env<'_>, handle: jlong) -> jstring {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return std::ptr::null_mut();
        }
    };
    match serialize_proxy_telemetry(&session.telemetry.snapshot()) {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(err) => {
                throw_runtime_exception_env(env, err.to_string());
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            JniProxyError::Serialization(err).throw(env);
            std::ptr::null_mut()
        }
    }
}

fn serialize_proxy_telemetry<T: serde::Serialize>(snapshot: &T) -> serde_json::Result<String> {
    let mut value = serde_json::to_value(snapshot)?;
    if let Some(quality) = quality_window().snapshot() {
        value["connectionQuality"] = serde_json::to_value(quality)?;
    }
    serde_json::to_string(&value)
}

#[cfg(test)]
mod tests {
    use ripdpi_quality::QualitySample;
    use serde_json::Value;

    use super::serialize_proxy_telemetry;
    use crate::quality_sink::quality_window;

    #[test]
    fn proxy_telemetry_serialization_projects_quality_window() {
        quality_window().reset();
        quality_window().record(QualitySample { rtt_ms: 42, succeeded: true, loss_pct: 0.0 });

        let json = serialize_proxy_telemetry(&serde_json::json!({"source": "proxy"})).expect("serialize telemetry");
        let value: Value = serde_json::from_str(&json).expect("valid json");

        assert_eq!(value["connectionQuality"]["transportKind"], "tcp_proxy");
        assert_eq!(value["connectionQuality"]["sampleCount"], 1);
        assert_eq!(value["connectionQuality"]["rttP50Ms"], 42);
        quality_window().reset();
    }
}
