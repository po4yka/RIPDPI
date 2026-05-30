//! JNI readiness-push callback for the WARP runtime (ADR 0003).
//!
//! Mirrors the [`vpn_protect`](crate::vpn_protect) callback shape: a
//! `(JavaVM, Global<JObject>)` pair stored behind an `Arc`, invoked once from
//! the WARP runtime thread the moment the listener is bound (right after the
//! `runtime_ready` telemetry event). This replaces the 50 ms Kotlin telemetry
//! poll with a single push, while the poll stays as a graceful-degradation
//! fallback on the Kotlin side.
//!
//! This is a strict lifecycle-class event — exactly one attach-per-session, NOT
//! a data-plane callback. Do not reuse this infrastructure for higher-frequency
//! events (see `JNI_CONTRACT.md` §8 and the `vpnservice-protect-invariant`
//! rule's data-plane preference).

use std::sync::Arc;

use jni::objects::JObject;
use jni::refs::Global;
use jni::sys::jlong;
use jni::{EnvUnowned, JavaVM, Outcome};

use crate::registry;

struct JniReadinessCallback {
    vm: JavaVM,
    listener: Global<JObject<'static>>,
}

// SAFETY: JavaVM is Send+Sync (a thin *mut sys::JavaVM wrapper). The
// Global<JObject<'static>> keeps the Java listener alive and is safe to use
// from any thread via attach_current_thread.
unsafe impl Send for JniReadinessCallback {}
// SAFETY: see the Send impl — both fields are themselves thread-safe and
// `on_ready()` only attaches the current thread to invoke one void method.
unsafe impl Sync for JniReadinessCallback {}

impl JniReadinessCallback {
    fn on_ready(&self) {
        let result: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            env.call_method(&self.listener, jni::jni_str!("onRuntimeReady"), jni::jni_sig!("()V"), &[])?;
            Ok(())
        });
        if let Err(error) = result {
            log::warn!("warp readiness callback failed: {error}");
        }
    }
}

/// JNI entry for `jniRegisterReadinessListener`.
///
/// Installs a one-shot readiness observer on the session identified by
/// `handle`. Returns `1` on success, `0` if the handle is unknown or
/// registration failed. Readiness is per-handle, so no generation token is
/// needed — the handle is the guard, and the `GlobalRef` is released when the
/// observer is replaced ([`unregister_entry`]) or the session is destroyed.
pub(crate) fn register_from_jni(mut env: EnvUnowned<'_>, handle: jlong, listener: JObject<'_>) -> jlong {
    match env
        .with_env(|env| -> jni::errors::Result<jlong> {
            let vm = env.get_java_vm()?;
            let listener_global: Global<JObject<'static>> = env.new_global_ref(listener)?;
            // SAFETY: the JavaVM is held live by JNI_OnLoad for the process
            // lifetime; from_raw copies only the thin pointer wrapper, so there
            // is no double-free risk.
            let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
            let callback = Arc::new(JniReadinessCallback { vm: vm_clone, listener: listener_global });
            Ok(install(handle, callback))
        })
        .into_outcome()
    {
        Outcome::Ok(result) => result,
        Outcome::Err(err) => {
            log::error!("warp readiness listener registration failed: {err}");
            0
        }
        Outcome::Panic(_) => 0,
    }
}

fn install(handle: jlong, callback: Arc<JniReadinessCallback>) -> jlong {
    match registry::get(handle) {
        Some(runtime) => {
            runtime.set_readiness_observer(Arc::new(move || callback.on_ready()));
            1
        }
        None => 0,
    }
}

/// JNI entry for `jniUnregisterReadinessListener`.
///
/// Replaces the readiness observer with a no-op, dropping the previously held
/// `GlobalRef` on this (JVM-attached) thread. A safe no-op when the handle is
/// unknown.
pub(crate) fn unregister_entry(handle: jlong) {
    if let Some(runtime) = registry::get(handle) {
        runtime.set_readiness_observer(Arc::new(|| {}));
    }
}
