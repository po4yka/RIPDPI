use android_support::{sanitize_error_message, throw_runtime_exception};
use jni::EnvUnowned;

pub(super) fn log_and_throw(env: &mut EnvUnowned<'_>, label: &str, message: &str) {
    log::error!("{label}: {message}");
    throw_runtime_exception(env, sanitize_error_message(message, label));
}
