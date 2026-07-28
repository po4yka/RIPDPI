mod entries;
mod icmp;
#[cfg(all(test, not(feature = "loom")))]
mod jni_tests;
mod lifecycle;
#[cfg(all(test, feature = "loom"))]
mod loom;
pub(crate) mod pcap;
mod pcap_entries;
mod registry;
mod runtime;
#[cfg(all(test, not(feature = "loom")))]
mod state_machine;
mod stats;
mod telemetry;

pub(crate) use entries::{
    tunnel_create_entry, tunnel_destroy_entry, tunnel_forwarding_evidence_entry, tunnel_icmp_ingress_packets_entry,
    tunnel_start_entry, tunnel_stats_entry, tunnel_stop_entry, tunnel_telemetry_entry,
};
pub(crate) use pcap_entries::{
    tunnel_pcap_list_captures_entry, tunnel_pcap_redact_entry, tunnel_pcap_start_entry, tunnel_pcap_stop_entry,
};

#[cfg(test)]
pub(crate) use lifecycle::{
    ensure_tunnel_destroyable, ensure_tunnel_start_allowed, rollback_failed_tunnel_start, take_running_tunnel,
    validate_tun_fd,
};
#[cfg(test)]
pub(crate) use registry::{SESSIONS, TunnelSession, TunnelSessionState, lookup_tunnel_session, remove_tunnel_session};
#[cfg(test)]
pub(crate) use runtime::shared_tunnel_runtime;
#[cfg(test)]
pub(crate) use stats::stats_snapshots_for_state;

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;
    use jni::sys::jlong;
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
            telemetry: Arc::new(TunnelTelemetryState::new(None)),
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
    fn tunnel_exception_messages_match_contract_fixture() {
        use golden_test_support::assert_contract_fixture;
        use serde_json::json;

        // Document every exception message thrown by the tunnel JNI layer.
        // If a message changes, this fixture breaks and both sides must update.
        let messages = json!([
            {"message": "Invalid TUN file descriptor", "javaClass": "java.lang.IllegalArgumentException"},
            {"message": "Invalid tunnel config payload", "javaClass": "java.lang.IllegalArgumentException"},
            {"message": "Invalid tunnel handle", "javaClass": "java.lang.IllegalArgumentException"},
            {"message": "Unknown tunnel handle", "javaClass": "java.lang.IllegalArgumentException"},
            {"message": "Tunnel session is already starting", "javaClass": "java.lang.IllegalStateException"},
            {"message": "Tunnel session is already running", "javaClass": "java.lang.IllegalStateException"},
            {"message": "Tunnel session has been destroyed", "javaClass": "java.lang.IllegalStateException"},
            {"message": "Cannot destroy a starting tunnel session", "javaClass": "java.lang.IllegalStateException"},
            {"message": "Cannot destroy a running tunnel session", "javaClass": "java.lang.IllegalStateException"},
            {"message": "Tunnel session has already been destroyed", "javaClass": "java.lang.IllegalStateException"},
        ]);

        let actual = serde_json::to_string_pretty(&messages).expect("serialize");
        assert_contract_fixture("tunnel_exception_messages.json", &actual);
    }

    #[test]
    fn rollback_failed_tunnel_start_restores_ready_state() {
        let session = TunnelSession {
            runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new(None)),
            state: Mutex::new(TunnelSessionState::Starting { cancel: Arc::new(CancellationToken::new()) }),
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
