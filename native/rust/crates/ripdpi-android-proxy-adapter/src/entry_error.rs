use android_support::{sanitize_error_message, throw_runtime_exception};
use jni::EnvUnowned;
use ripdpi_android_bridge_support::{NativeBridgeError, decorate_message};

pub(super) fn log_and_throw(env: &mut EnvUnowned<'_>, label: &str, message: &str) {
    log::error!("{label}: {message}");
    throw_runtime_exception(env, sanitize_error_message(message, label));
}

/// `log_and_throw` variant that augments the `RuntimeException` text with
/// a typed [`NativeBridgeError`] trailer. The Java class and the
/// leading sanitised-message line are unchanged from
/// [`log_and_throw`]; only the sentinel-prefixed JSON trailer is added,
/// so callers that ignore the trailer see exactly what they always saw.
pub(super) fn log_and_throw_with_payload(
    env: &mut EnvUnowned<'_>,
    label: &str,
    message: &str,
    bridge: &NativeBridgeError,
) {
    log::error!("{label}: {message}");
    let sanitised = sanitize_error_message(message, label);
    throw_runtime_exception(env, decorate_message(&sanitised, bridge));
}
