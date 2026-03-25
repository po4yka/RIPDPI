#[cfg(test)]
mod jni_tests;
mod lifecycle;
#[cfg(feature = "loom")]
mod loom;
mod registry;
#[cfg(test)]
mod state_machine;
mod stats;
mod telemetry;

use android_support::throw_runtime_exception;
use jni::objects::JString;
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;

use lifecycle::{create_session, destroy_session, start_session, stop_session};
use stats::stats_session;
use telemetry::telemetry_session;

#[cfg(test)]
pub(crate) use lifecycle::{
    ensure_tunnel_destroyable, ensure_tunnel_start_allowed, rollback_failed_tunnel_start, take_running_tunnel,
    validate_tun_fd,
};
#[cfg(test)]
pub(crate) use registry::{
    lookup_tunnel_session, remove_tunnel_session, shared_tunnel_runtime, TunnelSession, TunnelSessionState, SESSIONS,
};
#[cfg(test)]
pub(crate) use stats::stats_snapshots_for_state;

pub(crate) fn tunnel_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    android_support::init_android_logging("ripdpi-tunnel-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel session creation panicked");
            0
        },
    )
}

pub(crate) fn tunnel_start_entry(mut env: JNIEnv, handle: jlong, tun_fd: jint) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle, tun_fd)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session start panicked"));
}

pub(crate) fn tunnel_stop_entry(mut env: JNIEnv, handle: jlong) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session stop panicked"));
}

pub(crate) fn tunnel_stats_entry(mut env: JNIEnv, handle: jlong) -> jlongArray {
    android_support::init_android_logging("ripdpi-tunnel-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stats_session(&mut env, handle))).unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Tunnel stats retrieval panicked");
        std::ptr::null_mut()
    })
}

pub(crate) fn tunnel_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| telemetry_session(&mut env, handle))).unwrap_or_else(
        |_| {
            throw_runtime_exception(&mut env, "Tunnel telemetry retrieval panicked");
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn tunnel_destroy_entry(mut env: JNIEnv, handle: jlong) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle)))
        .map_err(|_| throw_runtime_exception(&mut env, "Tunnel session destroy panicked"));
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    use crate::config::{config_from_payload, sample_payload};
    use crate::telemetry::TunnelTelemetryState;
    use crate::to_handle;
    use ripdpi_tunnel_core::{DnsStatsSnapshot, Stats};
    use tokio_util::sync::CancellationToken;

    #[test]
    fn rejects_invalid_handle() {
        assert!(to_handle(0).is_none());
        assert!(to_handle(-1).is_none());
    }

    #[test]
    fn rejects_unknown_tunnel_handle_lookup() {
        let Err(err) = lookup_tunnel_session(99) else {
            panic!("expected unknown handle error");
        };

        assert_eq!(err, "Unknown tunnel handle");
    }

    #[test]
    fn rejects_invalid_tun_fd() {
        assert_eq!(validate_tun_fd(-1).expect_err("invalid tun fd"), "Invalid TUN file descriptor",);
    }

    #[test]
    fn tunnel_state_rejects_duplicate_start() {
        let worker = std::thread::spawn(|| {});
        let state = TunnelSessionState::Running {
            cancel: Arc::new(CancellationToken::new()),
            stats: Arc::new(Stats::new()),
            worker,
        };

        let err = ensure_tunnel_start_allowed(&state).expect_err("duplicate start");

        if let TunnelSessionState::Running { worker, .. } = state {
            let _ = worker.join();
        }
        assert_eq!(err, "Tunnel session is already running");
    }

    #[test]
    fn tunnel_state_rejects_stop_when_ready() {
        let mut state = TunnelSessionState::Ready;
        let err = take_running_tunnel(&mut state).expect_err("ready stop");

        assert_eq!(err, "Tunnel session is not running");
    }

    #[test]
    fn shared_tunnel_runtime_is_reused() {
        let first = shared_tunnel_runtime().expect("shared runtime");
        let second = shared_tunnel_runtime().expect("shared runtime");

        assert!(Arc::ptr_eq(&first, &second));
    }

    #[test]
    fn tunnel_stats_when_ready_are_zero() {
        assert_eq!(stats_snapshots_for_state(&TunnelSessionState::Ready).0, (0, 0, 0, 0));
    }

    #[test]
    fn tunnel_state_rejects_destroy_when_running() {
        let worker = std::thread::spawn(|| {});
        let state = TunnelSessionState::Running {
            cancel: Arc::new(CancellationToken::new()),
            stats: Arc::new(Stats::new()),
            worker,
        };

        let err = ensure_tunnel_destroyable(&state).expect_err("running destroy");

        if let TunnelSessionState::Running { worker, .. } = state {
            let _ = worker.join();
        }
        assert_eq!(err, "Cannot destroy a running tunnel session");
    }

    #[test]
    fn destroy_removes_ready_tunnel_session() {
        let handle = SESSIONS.insert(TunnelSession {
            runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new()),
            state: Mutex::new(TunnelSessionState::Ready),
        }) as jlong;

        let removed = remove_tunnel_session(handle).expect("removed session");
        assert!(matches!(*removed.state.lock().expect("state lock"), TunnelSessionState::Ready,));
        assert_eq!(
            match lookup_tunnel_session(handle) {
                Ok(_) => panic!("expected session removal"),
                Err(err) => err,
            },
            "Unknown tunnel handle",
        );
    }

    #[test]
    fn rollback_failed_tunnel_start_restores_ready_state() {
        let session = TunnelSession {
            runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new()),
            state: Mutex::new(TunnelSessionState::Starting),
        };
        session.telemetry.mark_started("127.0.0.1:1080".to_string());

        rollback_failed_tunnel_start(&session, "spawn failed".to_string());

        assert!(matches!(*session.state.lock().expect("state lock"), TunnelSessionState::Ready));
        assert_eq!(session.last_error.lock().expect("last error lock").as_deref(), Some("spawn failed"));
        assert!(ensure_tunnel_start_allowed(&session.state.lock().expect("state lock")).is_ok());
        assert!(ensure_tunnel_destroyable(&session.state.lock().expect("state lock")).is_ok());

        let snapshot = session.telemetry.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snapshot.state, "idle");
        assert_eq!(snapshot.active_sessions, 0);
        assert_eq!(snapshot.total_sessions, 1);
        assert_eq!(snapshot.total_errors, 1);
        assert_eq!(snapshot.last_error.as_deref(), Some("spawn failed"));
    }
}
