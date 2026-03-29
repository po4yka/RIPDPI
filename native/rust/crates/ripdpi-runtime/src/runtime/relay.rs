mod failure_retry;
mod first_exchange;
mod stream_copy;
mod tls_boundary;

use std::io;
use std::net::{SocketAddr, TcpStream};

use crate::runtime_policy::ConnectionRoute;

use self::failure_retry::{prepare_relay, record_stream_relay_success, PreparedRelay};
use self::stream_copy::{relay_streams, CONNECTION_FREEZE_MARKER};
use super::routing::{emit_failure_classified, note_block_signal_for_failure};
use super::state::RuntimeState;

#[cfg(test)]
use std::time::{Duration, Instant};

#[cfg(test)]
use self::failure_retry::classify_first_write_failure;
#[cfg(test)]
use self::first_exchange::{first_response_timeout, response_trigger_supported, timeout_count_limit};
#[cfg(test)]
use self::tls_boundary::{
    OutboundTlsFirstRecordAssembler, TlsRecordBoundaryTracker, FIRST_TLS_RECORD_ASSEMBLY_TIMEOUT,
};
#[cfg(test)]
use ripdpi_config::{DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST};
#[cfg(test)]
use ripdpi_failure_classifier::{FailureAction, FailureClass, FailureStage};

pub(super) fn relay(
    mut client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    route: ConnectionRoute,
    seed_request: Option<Vec<u8>>,
) -> io::Result<()> {
    let PreparedRelay {
        upstream,
        route,
        session_state,
        success_recorded,
        success_host,
        success_payload,
        client_closed,
    } = prepare_relay(&mut client, upstream, state, target, route, seed_request)?;

    if client_closed {
        let _ = upstream.shutdown(std::net::Shutdown::Both);
        let _ = client.shutdown(std::net::Shutdown::Both);
        return Ok(());
    }

    let relay_result = relay_streams(client, upstream, state, route.group_index, session_state, success_host.clone());
    match relay_result {
        Ok(final_state) => {
            if !success_recorded && final_state.recv_count > 0 {
                record_stream_relay_success(
                    state,
                    target,
                    &route,
                    success_host.as_deref(),
                    success_payload.as_deref(),
                )?;
            }
            Ok(())
        }
        Err(ref err) if err.to_string().contains(CONNECTION_FREEZE_MARKER) => {
            let failure = ripdpi_failure_classifier::classify_connection_freeze(
                0,
                state.config.timeouts.freeze_max_stalls,
                state.config.timeouts.freeze_window_ms,
            );
            note_block_signal_for_failure(state, success_host.as_deref(), &failure, None);
            emit_failure_classified(state, target, &failure, success_host.as_deref());
            relay_result.map(|_| ())
        }
        Err(_) => relay_result.map(|_| ()),
    }
}

#[cfg(test)]
mod tests {
    #[cfg(test)]
    use super::super::routing::trigger_flag;
    use super::*;
    use ripdpi_config::{RuntimeConfig, DETECT_CONNECT, DETECT_HTTP_LOCAT};
    use ripdpi_packets::DEFAULT_FAKE_TLS;
    use ripdpi_session::TriggerEvent;

    #[test]
    fn first_write_desync_capability_errors_classify_as_strategy_execution_failures() {
        let failure = classify_first_write_failure(&super::super::desync::OutboundSendError::StrategyExecution {
            action: "set_ttl",
            strategy_family: "disorder",
            fallback: Some("split"),
            bytes_committed: 0,
            source_errno: Some(libc::EINVAL),
            source: io::Error::from_raw_os_error(libc::EINVAL),
        });

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.stage, FailureStage::FirstWrite);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.summary.contains("desync action=set_ttl"));
    }

    #[test]
    fn first_write_desync_unsupported_actions_classify_as_strategy_execution_failures() {
        let failure = classify_first_write_failure(&super::super::desync::OutboundSendError::StrategyExecution {
            action: "await_writable_split",
            strategy_family: "split",
            fallback: None,
            bytes_committed: 0,
            source_errno: None,
            source: io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"),
        });

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.stage, FailureStage::FirstWrite);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.summary.contains("desync action=await_writable"));
    }

    #[test]
    fn first_write_desync_failures_preserve_fallback_metadata() {
        let failure = classify_first_write_failure(&super::super::desync::OutboundSendError::StrategyExecution {
            action: "write_disorder",
            strategy_family: "disorder",
            fallback: Some("split"),
            bytes_committed: 0,
            source_errno: Some(libc::EROFS),
            source: io::Error::from_raw_os_error(libc::EROFS),
        });

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert!(failure.evidence.summary.contains("fallback=split"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "fallback=split"));
    }

    #[test]
    fn zero_byte_split_first_write_failures_retry_matching_group() {
        let failure = classify_first_write_failure(&super::super::desync::OutboundSendError::StrategyExecution {
            action: "write_split",
            strategy_family: "split",
            fallback: None,
            bytes_committed: 0,
            source_errno: Some(libc::EROFS),
            source: io::Error::from_raw_os_error(libc::EROFS),
        });

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::RetryWithMatchingGroup);
        assert!(failure.evidence.tags.iter().any(|tag| tag == "strategyFamily=split"));
        assert!(failure.evidence.tags.iter().any(|tag| tag == "bytesCommitted=0"));
    }

    #[test]
    fn partial_first_write_failures_do_not_retry_matching_group() {
        let failure = classify_first_write_failure(&super::super::desync::OutboundSendError::StrategyExecution {
            action: "write_split",
            strategy_family: "split",
            fallback: None,
            bytes_committed: 3,
            source_errno: Some(libc::EROFS),
            source: io::Error::from_raw_os_error(libc::EROFS),
        });

        assert_eq!(failure.class, FailureClass::StrategyExecutionFailure);
        assert_eq!(failure.action, FailureAction::SurfaceOnly);
        assert!(failure.evidence.tags.iter().any(|tag| tag == "bytesCommitted=3"));
    }

    #[test]
    fn timeout_and_trigger_helpers_follow_runtime_configuration() {
        let mut config = RuntimeConfig::default();
        config.timeouts.partial_timeout_ms = 75;
        config.timeouts.timeout_ms = 900;
        let tls_tracker = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &tls_tracker), Some(Duration::from_millis(75)));

        config.timeouts.partial_timeout_ms = 0;
        let inactive_tracker = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &config);
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(900)));

        config.timeouts.timeout_ms = 0;
        config.groups[0].matches.detect = DETECT_HTTP_LOCAT | DETECT_CONNECT;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), Some(Duration::from_millis(250)));
        assert_eq!(timeout_count_limit(&config), 1);
        assert!(response_trigger_supported(&config, TriggerEvent::Redirect));
        assert!(response_trigger_supported(&config, TriggerEvent::Connect));
        assert!(!response_trigger_supported(&config, TriggerEvent::Torst));
        assert_eq!(trigger_flag(TriggerEvent::SslErr), DETECT_TLS_HANDSHAKE_FAILURE);
        assert_eq!(trigger_flag(TriggerEvent::Torst), DETECT_TORST);

        config.groups[0].matches.detect = 0;
        assert_eq!(first_response_timeout(&config, &inactive_tracker), None);
    }

    #[test]
    fn tls_record_tracker_handles_partial_records_and_limits() {
        let mut config = RuntimeConfig::default();
        config.timeouts.partial_timeout_ms = 50;

        let mut tracker = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x05, 0xaa]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xbb, 0xcc, 0xdd, 0xee]);
        assert!(!tracker.waiting_for_tls_record());

        let mut limited_config = RuntimeConfig::default();
        limited_config.timeouts.partial_timeout_ms = 50;
        limited_config.timeouts.timeout_bytes_limit = 3;
        let mut limited = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &limited_config);
        limited.observe(&[0x16, 0x03, 0x03, 0x00]);
        assert!(!limited.active());

        let mut invalid = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &config);
        invalid.observe(&[0x13, 0x03, 0x03, 0x00, 0x01, 0x00]);
        assert!(!invalid.active());
    }

    // -- Characterization: TLS record tracker state transitions --

    #[test]
    fn tls_record_tracker_inactive_without_partial_timeout() {
        let mut config = RuntimeConfig::default();
        config.timeouts.partial_timeout_ms = 0;
        let tracker = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &config);
        assert!(!tracker.active());
        assert!(!tracker.waiting_for_tls_record());
    }

    #[test]
    fn tls_record_tracker_inactive_for_non_tls_request() {
        let mut config = RuntimeConfig::default();
        config.timeouts.partial_timeout_ms = 50;
        let non_tls = b"GET / HTTP/1.1\r\n";
        let tracker = TlsRecordBoundaryTracker::for_first_response(non_tls, &config);
        assert!(!tracker.active());
    }

    #[test]
    fn tls_record_tracker_multi_record_observation() {
        let mut config = RuntimeConfig::default();
        config.timeouts.partial_timeout_ms = 50;
        let mut tracker = TlsRecordBoundaryTracker::for_first_response(DEFAULT_FAKE_TLS, &config);
        assert!(tracker.active());

        // First record: content type 0x16 (handshake), size 3
        tracker.observe(&[0x16, 0x03, 0x03, 0x00, 0x03]);
        assert!(tracker.waiting_for_tls_record());
        tracker.observe(&[0xaa, 0xbb, 0xcc]);
        assert!(!tracker.waiting_for_tls_record());

        // Second record: content type 0x14 (change cipher spec), size 1
        tracker.observe(&[0x14, 0x03, 0x03, 0x00, 0x01, 0xff]);
        assert!(!tracker.waiting_for_tls_record());
        assert!(tracker.active());
    }

    #[test]
    fn outbound_tls_first_record_assembler_buffers_partial_client_hello_until_complete() {
        let mut assembler = OutboundTlsFirstRecordAssembler::new();
        let start = Instant::now();

        assert!(assembler.push(&[0x16, 0x03, 0x03, 0x00], start).is_none());
        assert!(assembler.is_buffering());
        let payload = assembler
            .push(&[0x05, 0x01, 0x00, 0x00, 0x00, 0x00], start + Duration::from_millis(10))
            .expect("completed tls record");

        assert_eq!(payload, vec![0x16, 0x03, 0x03, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00]);
        assert!(!assembler.is_buffering());
    }

    #[test]
    fn outbound_tls_first_record_assembler_falls_back_for_invalid_header() {
        let mut assembler = OutboundTlsFirstRecordAssembler::new();
        let payload =
            assembler.push(&[0x16, 0x00, 0x00, 0x00, 0x01, 0xff], Instant::now()).expect("invalid header should flush");

        assert_eq!(payload, vec![0x16, 0x00, 0x00, 0x00, 0x01, 0xff]);
        assert!(!assembler.is_buffering());
    }

    #[test]
    fn outbound_tls_first_record_assembler_flushes_partial_record_after_timeout() {
        let mut assembler = OutboundTlsFirstRecordAssembler::new();
        let start = Instant::now();

        assert!(assembler.push(&[0x16, 0x03], start).is_none());
        assert!(assembler.is_buffering());
        assert!(assembler.flush_on_timeout(start + Duration::from_millis(50)).is_none());

        let payload = assembler
            .flush_on_timeout(start + FIRST_TLS_RECORD_ASSEMBLY_TIMEOUT + Duration::from_millis(1))
            .expect("partial tls record should flush on timeout");
        assert_eq!(payload, vec![0x16, 0x03]);
        assert!(!assembler.is_buffering());
    }
}
