use super::*;

#[test]
fn outbound_send_error_preserves_strategy_execution_metadata() {
    let err = strategy_execution_error(
        "set_ttl_disorder",
        "disorder",
        Some("split"),
        0,
        io::Error::from_raw_os_error(libc::EINVAL),
    );

    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    match err {
        OutboundSendError::StrategyExecution {
            action,
            strategy_family,
            fallback,
            bytes_committed,
            source_errno,
            ..
        } => {
            assert_eq!(action, "set_ttl_disorder");
            assert_eq!(strategy_family, "disorder");
            assert_eq!(fallback, Some("split"));
            assert_eq!(bytes_committed, 0);
            assert_eq!(source_errno, Some(libc::EINVAL));
        }
        OutboundSendError::Transport { .. } => panic!("expected strategy execution error"),
    }
    assert!(err.to_string().contains("desync action=set_ttl_disorder"));
}

#[test]
fn outbound_send_error_into_io_error_preserves_fallback_details() {
    let err = strategy_execution_error(
        "write_disorder",
        "disorder",
        Some("split"),
        0,
        io::Error::from_raw_os_error(libc::EROFS),
    );
    let io_error = err.into_io_error();

    assert_eq!(io_error.kind(), io::ErrorKind::ReadOnlyFilesystem);
    assert_eq!(
        io_error.get_ref().and_then(|inner| inner.downcast_ref::<OutboundSendError>()).and_then(|inner| match inner {
            OutboundSendError::StrategyExecution { fallback, .. } => *fallback,
            OutboundSendError::Transport { .. } => None,
        }),
        Some("split")
    );
    assert!(io_error.get_ref().and_then(|inner| inner.downcast_ref::<OutboundSendError>()).is_some());
}

#[test]
fn android_ttl_fallback_filter_matches_capability_errors_only() {
    assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EROFS)));
    assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EINVAL)));
    assert!(!should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::ECONNRESET)));
}

#[test]
fn android_ttl_fallback_filter_matches_strategy_execution_source_errors() {
    let err = strategy_execution_error(
        "set_ttl_disorder",
        "disorder",
        Some("split"),
        0,
        io::Error::from_raw_os_error(libc::EROFS),
    );
    assert!(should_ignore_android_ttl_error(err.source_error()));
}

// ---------------------------------------------------------------
// apply_entropy_padding
// ---------------------------------------------------------------

#[test]
fn strategy_result_ok_passes_through() {
    let result: Result<i32, OutboundSendError> = strategy_result(Ok(42), "action", "family", Some("fallback"), 0);
    assert_eq!(result.unwrap(), 42);
}

#[test]
fn strategy_result_err_wraps_metadata() {
    let result: Result<i32, OutboundSendError> = strategy_result(
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "broken")),
        "write_split",
        "split",
        Some("disorder"),
        100,
    );
    match result.unwrap_err() {
        OutboundSendError::StrategyExecution { action, strategy_family, fallback, bytes_committed, .. } => {
            assert_eq!(action, "write_split");
            assert_eq!(strategy_family, "split");
            assert_eq!(fallback, Some("disorder"));
            assert_eq!(bytes_committed, 100);
        }
        OutboundSendError::Transport { source, .. } => panic!("expected StrategyExecution, got Transport({source})"),
    }
}

#[test]
fn transport_result_ok_passes_through() {
    let result: Result<i32, OutboundSendError> = transport_result(Ok(42));
    assert_eq!(result.unwrap(), 42);
}

#[test]
fn transport_result_err_wraps_as_transport() {
    let result: Result<i32, OutboundSendError> =
        transport_result(Err(io::Error::new(io::ErrorKind::BrokenPipe, "broken")));
    assert!(matches!(result.unwrap_err(), OutboundSendError::Transport { .. }));
}

// ---------------------------------------------------------------
// Write helper tests
// ---------------------------------------------------------------

#[test]
fn write_payload_progress_full_payload() {
    let (mut client, mut server) = connected_pair();
    let payload = b"hello world test data";
    write_payload_progress(&mut client, payload).expect("write succeeds");
    let mut buf = vec![0u8; payload.len()];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read succeeds");
    assert_eq!(&buf, payload);
}

#[test]
fn write_payload_progress_closed_stream_errors() {
    let (mut client, server) = connected_pair();
    drop(server);
    // Write enough data to overwhelm kernel buffers and trigger an error
    let big = vec![0u8; 1024 * 1024];
    let mut got_error = false;
    for _ in 0..16 {
        if write_payload_progress(&mut client, &big).is_err() {
            got_error = true;
            break;
        }
    }
    assert!(got_error, "expected write error after filling kernel buffer to closed peer");
}

#[test]
fn write_transport_payload_returns_byte_count() {
    let (mut client, _server) = connected_pair();
    let result = write_transport_payload(&mut client, b"hello");
    assert_eq!(result.unwrap(), 5);
}

#[test]
fn write_transport_payload_error_is_transport() {
    let (mut client, server) = connected_pair();
    drop(server);
    let big = vec![0u8; 1024 * 1024];
    let mut last_err = None;
    for _ in 0..16 {
        if let Err(err) = write_transport_payload(&mut client, &big) {
            last_err = Some(err);
            break;
        }
    }
    let err = last_err.expect("expected transport error after filling kernel buffer");
    assert!(matches!(err, OutboundSendError::Transport { .. }));
}

#[test]
fn write_strategy_named_accumulates_committed() {
    let (mut client, _server) = connected_pair();
    let result = write_strategy_payload_named(&mut client, b"hello world", "write_split", "split", None, 50);
    assert_eq!(result.unwrap(), 61); // 50 + 11
}

#[test]
fn write_strategy_named_error_has_metadata() {
    let (mut client, server) = connected_pair();
    drop(server);
    let big = vec![0u8; 1024 * 1024];
    let mut last_err = None;
    for _ in 0..16 {
        if let Err(err) = write_strategy_payload_named(&mut client, &big, "write_split", "split", Some("disorder"), 50)
        {
            last_err = Some(err);
            break;
        }
    }
    match last_err.expect("expected strategy error") {
        OutboundSendError::StrategyExecution { action, strategy_family, fallback, .. } => {
            assert_eq!(action, "write_split");
            assert_eq!(strategy_family, "split");
            assert_eq!(fallback, Some("disorder"));
        }
        OutboundSendError::Transport { source, .. } => panic!("expected StrategyExecution, got Transport({source})"),
    }
}

// ---------------------------------------------------------------
// execute_tcp_actions tests
// ---------------------------------------------------------------

#[test]
fn possible_payload_commit_survives_error_conversions_without_inventing_bytes() {
    let error = strategy_execution_error("send_fake", "fake", None, 0, io::Error::other("opaque send failure"))
        .with_possible_payload_commit();
    let receipt = error.execution_receipt().expect("failure receipt").clone();
    let error = error.with_execution_receipt(receipt);
    assert_eq!(error.bytes_committed(), 0);
    assert!(error.payload_commit_uncertain());
    let io_error = error.into_io_error();
    let inner = io_error.get_ref().and_then(|value| value.downcast_ref::<OutboundSendError>()).expect("typed error");
    assert_eq!(inner.bytes_committed(), 0);
    assert!(inner.payload_commit_uncertain());
    assert!(io_error.to_string().contains("payload_commit_uncertain=true"));
}

#[test]
fn partial_transport_write_preserves_bytes_received_by_peer() {
    use std::io::Read;
    use std::net::Shutdown;
    let (mut client, mut server) = connected_pair();
    socket2::SockRef::from(&client).set_send_buffer_size(1024).expect("small send buffer");
    client.set_nonblocking(true).expect("nonblocking sender");
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("peer deadline");
    let payload: Vec<u8> = (0..8 * 1024 * 1024).map(|index| (index % 251) as u8).collect();
    let error = write_transport_payload(&mut client, &payload).expect_err("peer does not drain send buffer");
    client.shutdown(Shutdown::Write).expect("finish queued prefix");
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read queued prefix");
    assert!(!received.is_empty() && received.len() < payload.len());
    assert_eq!(received, payload[..received.len()]);
    assert_eq!(error.bytes_committed(), received.len());
}

#[test]
fn transport_progress_remains_exact_when_receipt_count_is_bounded() {
    let committed = usize::from(u16::MAX) + 4096;
    let receipt = crate::TcpExecutionReceipt::failed_strategy_execution(
        None,
        1,
        0,
        1,
        0,
        committed,
        crate::TcpTerminalReason::Transport,
    );
    assert_eq!(receipt.payload_bytes_committed, usize::from(u16::MAX));
    let error =
        OutboundSendError::transport_with_progress(io::Error::new(io::ErrorKind::TimedOut, "partial write"), committed)
            .with_execution_receipt(receipt);
    assert_eq!(error.bytes_committed(), committed);
}
