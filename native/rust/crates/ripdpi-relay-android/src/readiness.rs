//! JNI readiness-push callback for the relay runtime (ADR 0003).
//!
//! Mirrors the warp `vpn_protect`/`readiness` callback shape: a
//! `(JavaVM, Global<JObject>)` pair stored behind an `Arc`, invoked once from
//! the relay runtime thread the moment the listener is bound (right after the
//! `runtime_ready` telemetry event), replacing the 50 ms Kotlin telemetry poll.
//! The poll stays as a graceful-degradation fallback on the Kotlin side — and
//! for the Apps Script backend, which has no native readiness event, the
//! register entry returns `0` so Kotlin keeps polling.
//!
//! Strict lifecycle-class event: one attach per session, never a data-plane
//! callback (see `JNI_CONTRACT.md` §8).

use std::sync::Arc;

use android_support::SharedJvm;
use jni::objects::JObject;
use jni::refs::Global;
use jni::sys::jlong;
use jni::{EnvUnowned, Outcome};

use crate::registry::session_from_handle;

struct JniReadinessCallback {
    vm: SharedJvm,
    listener: Global<JObject<'static>>,
}

// `JniReadinessCallback` auto-derives `Send + Sync`: `SharedJvm` (`Arc<JavaVM>`)
// and `Global<JObject<'static>>` are both `Send + Sync` in jni 0.22. Relying on
// the auto-derive rather than a manual `unsafe impl` keeps the compiler tripwire —
// a future non-thread-safe field breaks the `assert_send`/`assert_sync` guard
// below instead of being silently forced thread-safe.
const _: fn() = || {
    fn assert_send<T: Send>() {}
    fn assert_sync<T: Sync>() {}
    assert_send::<JniReadinessCallback>();
    assert_sync::<JniReadinessCallback>();
};

impl JniReadinessCallback {
    fn on_ready(&self) {
        // Scoped attach (jni 0.22 has no daemon variant): detaches when the callback returns,
        // so this runtime thread is never left permanently attached and can't block JVM teardown.
        let result: Result<(), jni::errors::Error> =
            self.vm.attach_current_thread_for_scope(|env| -> jni::errors::Result<()> {
                env.call_method(&self.listener, jni::jni_str!("onRuntimeReady"), jni::jni_sig!("()V"), &[])?;
                Ok(())
            });
        if let Err(error) = result {
            log::warn!("relay readiness callback failed: {error}");
        }
    }
}

/// JNI entry for `jniRegisterReadinessListener`.
///
/// Installs a one-shot readiness observer on the session identified by
/// `handle`. Returns `1` on success (native readiness push active), or `0` if
/// the handle is unknown, registration failed, or the backend has no native
/// readiness event (Apps Script) — in which case Kotlin keeps polling.
pub(crate) fn register_from_jni(mut env: EnvUnowned<'_>, handle: jlong, listener: JObject<'_>) -> jlong {
    match env
        .with_env(|env| -> jni::errors::Result<jlong> {
            let vm = env.get_java_vm()?;
            let listener_global: Global<JObject<'static>> = env.new_global_ref(listener)?;
            // The single auditable `JavaVM::from_raw` site lives in `SharedJvm::new`.
            let callback = Arc::new(JniReadinessCallback { vm: SharedJvm::new(&vm), listener: listener_global });
            Ok(install(handle, callback))
        })
        .into_outcome()
    {
        Outcome::Ok(result) => result,
        Outcome::Err(err) => {
            log::error!("relay readiness listener registration failed: {err}");
            0
        }
        Outcome::Panic(_) => 0,
    }
}

fn install(handle: jlong, callback: Arc<JniReadinessCallback>) -> jlong {
    match session_from_handle(handle) {
        Some(session) if session.set_readiness_observer(Arc::new(move || callback.on_ready())) => 1,
        _ => 0,
    }
}

/// JNI entry for `jniUnregisterReadinessListener`.
///
/// Replaces the readiness observer with a no-op, dropping the previously held
/// `GlobalRef` on this (JVM-attached) thread. A safe no-op when the handle is
/// unknown or the backend has no readiness observer.
pub(crate) fn unregister_entry(handle: jlong) {
    if let Some(session) = session_from_handle(handle) {
        session.set_readiness_observer(Arc::new(|| {}));
    }
}
