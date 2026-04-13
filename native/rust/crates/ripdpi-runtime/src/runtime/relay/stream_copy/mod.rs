mod copy_halves;
mod freeze;
mod observers;
mod rotation;

use copy_halves::{copy_inbound_half, copy_outbound_half};
use freeze::FreezeDetector;
use observers::group_rotation_controller;
#[cfg(test)]
use rotation::RotationFailureReason;

use crate::sync::{Arc, Mutex};
use ripdpi_session::SessionState;
use std::io;
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use super::super::state::RuntimeState;

const RELAY_IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);

pub(super) const CONNECTION_FREEZE_MARKER: &str = "connection freeze detected";

pub(super) fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
    remembered_host_seed: Option<String>,
) -> io::Result<SessionState> {
    let rotation_state = group_rotation_controller(state, group_index, &session_seed)?;
    let _ = (client.set_read_timeout(Some(RELAY_IDLE_TIMEOUT)), client.set_write_timeout(None));
    let _ = (upstream.set_read_timeout(Some(RELAY_IDLE_TIMEOUT)), upstream.set_write_timeout(None));

    fn clone_err(role: &'static str) -> impl FnOnce(io::Error) -> io::Error {
        move |e| io::Error::other(format!("clone {role} socket for relay: {e}"))
    }
    let client_reader = client.try_clone().map_err(clone_err("client"))?;
    let client_writer = client.try_clone().map_err(clone_err("client"))?;
    let upstream_reader = upstream.try_clone().map_err(clone_err("upstream"))?;
    let upstream_writer = upstream.try_clone().map_err(clone_err("upstream"))?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let inbound_state = state.clone();
    let groups = &state.config.groups;
    let group = groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.actions.drop_sack;
    let peer_done = Arc::new(AtomicBool::new(false));
    let freeze_detected = Arc::new(AtomicBool::new(false));
    let remembered_host = Arc::new(Mutex::new(remembered_host_seed));
    let inbound_host = remembered_host.clone();
    let outbound_host = remembered_host.clone();
    let inbound_rotation = rotation_state.clone();
    let outbound_rotation = rotation_state.clone();

    let freeze_flag = freeze_detected.clone();
    let timeouts = state.config.timeouts;
    let down_done = peer_done.clone();
    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || {
            let detector =
                FreezeDetector::new(timeouts.freeze_window_ms, timeouts.freeze_min_bytes, timeouts.freeze_max_stalls);
            copy_inbound_half(
                upstream_reader,
                client_writer,
                &inbound_state,
                inbound_session,
                inbound_host,
                inbound_rotation,
                down_done,
                detector,
                freeze_flag,
            )
        })
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;

    // Keep the client->upstream half on the worker thread that already owns
    // this connection. That preserves the blocking desync path while avoiding
    // a second per-flow relay thread in steady state.
    let up_result = copy_outbound_half(
        client_reader,
        upstream_writer,
        outbound_state,
        group_index,
        outbound_session,
        outbound_host,
        outbound_rotation,
        peer_done.clone(),
    );
    if up_result.is_err() {
        peer_done.store(true, Ordering::Release);
        let _ = upstream.shutdown(Shutdown::Both);
        let _ = client.shutdown(Shutdown::Both);
    }
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    // Ensure both sockets are fully closed regardless of how relay threads exited.
    // The per-direction shutdown in each thread may be skipped on error paths;
    // this guarantees FIN is sent so sockets don't linger in CLOSE_WAIT.
    let _ = upstream.shutdown(Shutdown::Both);
    let _ = client.shutdown(Shutdown::Both);

    if drop_sack {
        let _ = crate::platform::detach_drop_sack(&upstream);
    }

    up_result.and(down_result)?;

    if freeze_detected.load(Ordering::Acquire) {
        return Err(io::Error::new(io::ErrorKind::TimedOut, CONNECTION_FREEZE_MARKER));
    }

    session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{
        DesyncGroup, OffsetBase, OffsetExpr, RotationCandidate, RotationPolicy, TcpChainStep, TcpChainStepKind,
    };
    use rotation::CircularTcpRotationController;
    use std::time::{Duration, Instant};

    fn rotation_controller() -> CircularTcpRotationController {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::tls_marker(OffsetBase::ExtLen, 0)),
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(2)),
        ];
        CircularTcpRotationController::new(
            group,
            RotationPolicy {
                candidates: vec![
                    RotationCandidate {
                        tcp_chain: vec![TcpChainStep::new(
                            TcpChainStepKind::HostFake,
                            OffsetExpr::marker(OffsetBase::EndHost, 8),
                        )],
                    },
                    RotationCandidate {
                        tcp_chain: vec![TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::host(1))],
                    },
                ],
                ..RotationPolicy::default()
            },
        )
        .expect("rotation controller")
    }

    #[test]
    fn freeze_detector_disabled_when_max_stalls_zero() {
        let mut d = FreezeDetector::new(5000, 512, 0);
        d.record_bytes(1);
        let far_future = Instant::now() + Duration::from_secs(300);
        assert!(!d.check(far_future));
    }

    #[test]
    fn freeze_detector_does_not_trigger_before_warm() {
        let mut d = FreezeDetector::new(100, 512, 1);
        let far_future = Instant::now() + Duration::from_secs(300);
        assert!(!d.check(far_future));
    }

    #[test]
    fn freeze_detector_triggers_after_consecutive_stalls() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // First window: good throughput -- reset
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // Windows 2-4: only trickle bytes (below 512)
        d.record_bytes(10);
        assert!(!d.check(start + Duration::from_millis(200)));
        assert_eq!(d.consecutive_stalls, 1);

        d.record_bytes(5);
        assert!(!d.check(start + Duration::from_millis(300)));
        assert_eq!(d.consecutive_stalls, 2);

        d.record_bytes(2);
        assert!(d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 3);
    }

    #[test]
    fn freeze_detector_resets_on_good_window() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // Warm-up window passes with good throughput
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // First stall window: only 10 bytes
        d.record_bytes(10);
        d.check(start + Duration::from_millis(200));
        assert_eq!(d.consecutive_stalls, 1);

        // Second stall window
        d.record_bytes(10);
        d.check(start + Duration::from_millis(300));
        assert_eq!(d.consecutive_stalls, 2);

        // Good window -- resets counter
        d.record_bytes(600);
        assert!(!d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 0);
    }

    #[test]
    fn freeze_detector_does_not_false_positive_on_slow_but_sufficient_transfer() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(5000, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // Each window: exactly at threshold (512 bytes)
        for i in 1..=10 {
            d.record_bytes(512);
            assert!(!d.check(start + Duration::from_millis(5000 * i)));
            assert_eq!(d.consecutive_stalls, 0);
        }
    }

    #[test]
    fn rotation_retransmission_failure_advances_on_next_round() {
        let mut controller = rotation_controller();
        let config = ripdpi_config::RuntimeConfig::default();

        controller.start_round(&config, 2, 0, b"GET / HTTP/1.1\r\n", Some(1), Some("example.org"), None);
        controller.observe_round_failure(Some("example.org"), None, RotationFailureReason::Retransmissions, 3);
        assert!(controller.pending_advance);
        assert_eq!(controller.consecutive_failures, 1);

        controller.start_round(&config, 3, 128, b"GET / HTTP/1.1\r\n", Some(4), Some("example.org"), None);

        assert_eq!(controller.active_candidate_index, Some(0));
        assert!(!controller.pending_advance);
    }

    #[test]
    fn rotation_success_clears_failure_window() {
        let mut controller = rotation_controller();
        controller.consecutive_failures = 2;
        controller.consecutive_rsts = 1;
        controller.last_failure_at = Some(Instant::now());

        controller.observe_round_success();

        assert_eq!(controller.consecutive_failures, 0);
        assert_eq!(controller.consecutive_rsts, 0);
        assert!(controller.last_failure_at.is_none());
    }

    #[test]
    fn rotation_reset_failure_rotates_on_next_round() {
        let mut controller = rotation_controller();
        let config = ripdpi_config::RuntimeConfig::default();

        controller.start_round(&config, 2, 0, b"GET / HTTP/1.1\r\n", Some(0), Some("example.org"), None);
        controller.observe_round_failure(
            Some("example.org"),
            None,
            RotationFailureReason::Transport(ripdpi_failure_classifier::FailureClass::TcpReset),
            0,
        );
        assert!(controller.pending_advance);

        controller.start_round(&config, 3, 64, b"GET / HTTP/1.1\r\n", Some(0), Some("example.org"), None);

        assert_eq!(controller.active_candidate_index, Some(0));
    }

    #[test]
    fn rotation_wraps_back_to_first_candidate() {
        let mut controller = rotation_controller();
        controller.active_candidate_index = Some(1);
        controller.pending_advance = true;

        controller.rotate_if_pending(Some("example.org"), None, 4);

        assert_eq!(controller.active_candidate_index, Some(0));
        assert!(!controller.pending_advance);
    }
}
