//! Relay session lifecycle delegates behind the JNI exports in `lib.rs`.
//!
//! These functions are the `relay_*_entry` bodies that `lib.rs` wraps in
//! `ffi_boundary`. Ordering is `create -> start -> stop -> destroy`; `start`
//! blocks for the whole session while `stop` only signals it. Sessions live in
//! the `registry` module keyed by the opaque `jlong` handle. Failures are
//! reported through return values — these functions never throw Java
//! exceptions.

use std::future::Future;
use std::time::Duration;

use android_support::{JNI_VERSION, clear_relay_events_for_runtime, init_android_logging};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};
use tracing::Instrument;

use crate::registry::{insert_session, remove_session, session_from_handle};
use crate::runtime::create_session;
use crate::telemetry::{IDLE_TELEMETRY_JSON, install_quality_observer, serialize_runtime_telemetry};

/// `JNI_OnLoad` body: mask `SIGPIPE`, install Android logging and the panic
/// hook, and install the default `rustls` crypto provider. Returns the JNI
/// version the library targets. Runs once at library load.
pub(crate) fn jni_on_load_entry(_vm: JavaVM) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-relay-native");
        android_support::install_panic_hook();
        let _ = rustls::crypto::ring::default_provider().install_default();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

/// `jniCreate`: parse `config_json`, build a relay session and register it.
/// Returns the new opaque handle, or `0` if the config is invalid or session
/// construction fails — no Java exception is thrown.
pub(crate) fn relay_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = config_json.mutf8_chars(env)?.to_str().into_owned();
            let Some(session) = create_session(&config_json) else {
                return Ok(0);
            };

            install_quality_observer(&session);
            let handle = insert_session(session);
            Ok(jlong::try_from(handle).unwrap_or(0))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

/// `jniStart`: look up the session, build a multi-thread Tokio runtime and run
/// the relay to completion. **Blocks the calling (JNI) thread for the whole
/// session lifetime.** Returns `0` on a clean exit, `1` if `handle` is unknown,
/// or `2` if the runtime fails to build or the session errors.
pub(crate) fn relay_start_entry(_env: EnvUnowned<'_>, handle: jlong) -> jint {
    let Some(session) = session_from_handle(handle) else {
        return 1;
    };

    let runtime_id = handle.to_string();
    let session_run = session.run().instrument(tracing::info_span!(
        "relay_runtime",
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        runtime_id = runtime_id,
    ));
    match run_to_completion(session_run) {
        Ok(()) => 0,
        Err(_) => 2,
    }
}

/// How long the stopped session's runtime waits for its blocking tasks. The
/// service waits 5 s for `jniStart` to return after a stop.
const RUNTIME_SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(500);

/// Runs `session` on a new multi-thread runtime, then shuts the runtime down
/// within [`RUNTIME_SHUTDOWN_TIMEOUT`]. Dropping a runtime instead waits with
/// no bound for `spawn_blocking` tasks, such as a hostname lookup parked in
/// getaddrinfo or the JNI resolver, so a stop could overrun the service's
/// stop budget. A task still blocked then finishes on its own thread and its
/// result is discarded.
fn run_to_completion(session: impl Future<Output = std::io::Result<()>>) -> std::io::Result<()> {
    let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build()?;
    let result = runtime.block_on(session);
    runtime.shutdown_timeout(RUNTIME_SHUTDOWN_TIMEOUT);
    result
}

/// `jniStop`: signal the running session to shut down so the blocked
/// `relay_start_entry` returns. Does not block. Idempotent — a no-op if
/// `handle` is unknown or already destroyed.
pub(crate) fn relay_stop_entry(_env: EnvUnowned<'_>, handle: jlong) {
    if let Some(session) = session_from_handle(handle) {
        session.stop();
    }
}

/// `jniPollTelemetry`: serialize the session's current runtime telemetry to a
/// JSON `jstring`, falling back to the idle telemetry payload for an unknown
/// handle. Non-blocking.
pub(crate) fn relay_poll_telemetry_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload = session_from_handle(handle)
                .and_then(|session| serialize_runtime_telemetry(&session, &handle.to_string()))
                .unwrap_or_else(|| IDLE_TELEMETRY_JSON.to_string());
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

/// `jniDestroy`: remove the session from the registry, retiring the handle.
/// Idempotent — a no-op if `handle` is unknown or already removed. Should run
/// only after `relay_start_entry` has returned.
pub(crate) fn relay_destroy_entry(_env: EnvUnowned<'_>, handle: jlong) {
    remove_session(handle);
    clear_relay_events_for_runtime(&handle.to_string());
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use super::run_to_completion;

    #[test]
    fn a_stopped_session_returns_without_waiting_for_a_blocked_lookup() {
        let started = Instant::now();
        let result = run_to_completion(async {
            // Stands in for a hostname lookup parked in getaddrinfo.
            drop(tokio::task::spawn_blocking(|| std::thread::sleep(Duration::from_secs(4))));
            tokio::task::yield_now().await;
            Ok(())
        });
        assert!(result.is_ok());
        assert!(started.elapsed() < Duration::from_secs(2), "start returned after {:?}", started.elapsed());
    }
}
