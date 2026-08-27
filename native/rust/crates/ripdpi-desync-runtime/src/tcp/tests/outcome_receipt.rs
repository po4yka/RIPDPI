use super::*;

use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::Mutex;
use std::time::Duration;

use crate::platform::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFakeSender, TcpFlagOverrides, TcpFragmentSender,
    TcpPayloadSegment, TcpPayloadSender, TcpPlatformCapabilities, TcpSocketOptions, TcpStageWait,
};
use crate::types::{TcpExecutionDisposition, TcpFallbackReason, TcpOffsetMarkerBase, TcpStrategyFamily};

enum FaultSpec {
    WaitStage(io::ErrorKind),
}

type FaultQueue = Mutex<VecDeque<FaultSpec>>;

#[derive(Default)]
struct ReceiptTestPlatform {
    faults: FaultQueue,
}

impl TcpPlatformCapabilities for ReceiptTestPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        Some(64)
    }

    fn seqovl_supported(&self) -> bool {
        false
    }

    fn supports_fake_retransmit(&self) -> bool {
        true
    }

    fn tcp_segment_hint(&self, _stream: &TcpStream) -> io::Result<Option<ripdpi_desync::TcpSegmentHint>> {
        Ok(None)
    }

    fn tcp_activation_state(&self, _stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
        Ok(None)
    }
}

impl TcpSocketOptions for ReceiptTestPlatform {
    fn set_tcp_md5sig(&self, _stream: &TcpStream, _key_len: u16) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "md5sig unsupported in receipt test"))
    }

    fn set_tcp_window_clamp(&self, _stream: &TcpStream, _size: u32) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "window clamp unsupported in receipt test"))
    }

    fn wait_tcp_stage(&self, _stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
        match self.faults.lock().expect("receipt fault queue").pop_front() {
            Some(FaultSpec::WaitStage(kind)) => Err(io::Error::new(kind, "injected stage wait failure")),
            None => Ok(()),
        }
    }
}

impl TcpFakeSender for ReceiptTestPlatform {
    fn send_fake_rst(
        &self,
        _stream: &TcpStream,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "fake rst unsupported in receipt test"))
    }

    fn send_fake_tcp(
        &self,
        stream: &TcpStream,
        original_prefix: &[u8],
        _fake_prefix: &[u8],
        ttl: u8,
        _md5sig: bool,
        _default_ttl: u8,
        _options: FakeTcpOptions<'_>,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
        _wait: TcpStageWait,
    ) -> io::Result<()> {
        if ttl == 1 {
            return Err(io::Error::from_raw_os_error(libc::EPERM));
        }
        let mut stream = stream;
        stream.write_all(original_prefix)
    }
}

impl TcpPayloadSender for ReceiptTestPlatform {
    fn send_ordered_tcp_segments(
        &self,
        stream: &TcpStream,
        segments: &[OrderedTcpSegment<'_>],
        _original_payload_len: usize,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _md5sig: bool,
        _timestamp_delta_ticks: Option<i32>,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
        _wait: TcpStageWait,
    ) -> io::Result<()> {
        let mut stream = stream;
        for segment in segments {
            stream.write_all(segment.payload)?;
        }
        Ok(())
    }

    fn send_flagged_tcp_payload(
        &self,
        _stream: &TcpStream,
        _payload: &[u8],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _md5sig: bool,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "flagged payload unsupported in receipt test"))
    }

    fn send_seqovl_tcp(
        &self,
        _stream: &TcpStream,
        _real_chunk: &[u8],
        _fake_prefix: &[u8],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _md5sig: bool,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "seqovl unsupported in receipt test"))
    }
}

impl TcpFragmentSender for ReceiptTestPlatform {
    fn send_ip_fragmented_tcp(
        &self,
        _stream: &TcpStream,
        _payload: &[u8],
        _split_offset: usize,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _disorder: bool,
        _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "ipfrag unsupported in receipt test"))
    }

    fn send_multi_disorder_tcp(
        &self,
        _stream: &TcpStream,
        _payload: &[u8],
        _segments: &[TcpPayloadSegment],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _inter_segment_delay_ms: u32,
        _md5sig: bool,
        _original_flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "multi-disorder unsupported in receipt test"))
    }
}

#[test]
fn split_host_plus_one_returns_applied_receipt_with_real_write_and_await_counts() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)));

    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let unavailable = default_ttl_unavailable();

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &unavailable,
        None,
    )
    .expect("split(host+1) send should succeed");

    assert_eq!(outcome.execution_receipt.disposition, TcpExecutionDisposition::Applied);
    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
    assert_eq!(outcome.execution_receipt.marker_delta, Some(1));
    assert_eq!(outcome.execution_receipt.planned_steps, 1);
    assert_eq!(outcome.execution_receipt.real_writes_committed, 2);
    assert_eq!(outcome.execution_receipt.completed_awaits, 1);
    assert_eq!(outcome.execution_receipt.payload_bytes_committed, payload.len());

    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
fn filtered_split_host_plus_one_sends_plain_payload_with_activation_skipped_receipt() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    for (modifier, expected) in [
        (0, payload.as_slice()),
        (ripdpi_packets::MH_HMIX, b"GET /watch HTTP/1.1\r\nhOsT: youtube.com\r\n\r\n".as_slice()),
    ] {
        let mut group = test_group();
        group.actions.mod_http = modifier;
        group.actions.tcp_chain.push(
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)).with_activation_filter(Some(
                ripdpi_config::ActivationFilter {
                    payload_size: Some(NumericRange::new(100, 200)),
                    ..Default::default()
                },
            )),
        );
        let (mut client, mut server) = connected_pair();
        server.set_read_timeout(Some(Duration::from_secs(1))).expect("set read deadline");
        let progress = OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let outcome = send_prepared_with_group(
            &mut client,
            &ReceiptTestPlatform::default(),
            &RuntimeConfig::default(),
            &group,
            payload,
            progress,
            context,
            None,
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect("inactive split step must still send the plain payload");
        drop(client);
        let mut received = Vec::new();
        server.read_to_end(&mut received).expect("read the complete plain payload");
        assert_eq!(received, expected);

        let receipt = outcome.execution_receipt;
        assert_eq!(receipt.real_writes_committed, 1);
        assert_eq!(receipt.completed_awaits, 0);
        assert_eq!(receipt.payload_bytes_committed, payload.len());
        assert_eq!(receipt.disposition, TcpExecutionDisposition::ActivationSkipped, "modifier={modifier}");
        assert_eq!(receipt.configured_family, Some(TcpStrategyFamily::Split));
        assert_eq!(receipt.effective_family, None);
        assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
        assert_eq!(receipt.marker_delta, Some(1));
        assert_eq!(receipt.planned_steps, 0);
        assert_eq!(receipt.resolved_offset, None);
        assert_eq!(outcome.strategy_family, None);
    }
}

#[test]
fn split_host_plus_one_wait_failure_preserves_plan_and_committed_prefix() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let prefix = b"GET /watch HTTP/1.1\r\nHost: y";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)));
    let platform = ReceiptTestPlatform::default();
    platform.faults.lock().expect("receipt fault queue").push_back(FaultSpec::WaitStage(io::ErrorKind::TimedOut));
    let (mut client, mut server) = connected_pair();
    server.set_read_timeout(Some(Duration::from_secs(1))).expect("set read deadline");
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let error = send_prepared_with_group(
        &mut client,
        &platform,
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect_err("stage wait must fail after the split prefix was written");

    assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    assert!(platform.faults.lock().expect("receipt fault queue").is_empty(), "wait fault must be consumed");
    drop(client);
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read only the committed prefix");
    assert_eq!(received, prefix);

    let receipt = error.execution_receipt().expect("failure must include an execution receipt");
    assert_eq!(receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
    assert_eq!(receipt.configured_family, Some(TcpStrategyFamily::Split));
    assert_eq!(receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(receipt.attempted_actions, 2);
    assert_eq!(receipt.completed_actions, 1);
    assert_eq!(receipt.real_writes_committed, 1);
    assert_eq!(receipt.completed_awaits, 0);
    assert_eq!(receipt.payload_bytes_committed, prefix.len());
    assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
    assert_eq!(receipt.marker_delta, Some(1));
    assert_eq!(receipt.resolved_offset, Some(prefix.len()));
    assert_eq!(receipt.planned_steps, 1);
}

#[test]
fn filtered_split_host_plus_one_then_active_host_plus_two_uses_active_marker_in_receipt() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let host_plus_two = b"GET /watch HTTP/1.1\r\nHost: yo";
    let mut group = test_group();
    group.actions.tcp_chain.extend([
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)).with_activation_filter(Some(
            ripdpi_config::ActivationFilter { payload_size: Some(NumericRange::new(100, 200)), ..Default::default() },
        )),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(2)),
    ]);
    let platform = ReceiptTestPlatform::default();
    platform.faults.lock().expect("receipt fault queue").push_back(FaultSpec::WaitStage(io::ErrorKind::TimedOut));
    let (mut client, mut server) = connected_pair();
    server.set_read_timeout(Some(Duration::from_secs(1))).expect("set read deadline");
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let error = send_prepared_with_group(
        &mut client,
        &platform,
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect_err("stage wait must fail after active host+2 split prefix was written");
    drop(client);
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read only the committed prefix");
    assert_eq!(received, host_plus_two);

    let receipt = error.execution_receipt().expect("failure must include an execution receipt");
    assert_eq!(receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
    assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
    assert_eq!(receipt.marker_delta, Some(2));
    assert_eq!(receipt.resolved_offset, Some(host_plus_two.len()));
    assert_eq!(receipt.planned_steps, 1);
    assert_eq!(receipt.real_writes_committed, 1);
    assert_eq!(receipt.completed_awaits, 0);
}

#[test]
fn filtered_flagged_split_does_not_force_special_executor_flags_on_active_split() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.extend([
        with_original_flag_overrides(
            &TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)).with_activation_filter(Some(
                ripdpi_config::ActivationFilter {
                    payload_size: Some(NumericRange::new(100, 200)),
                    ..Default::default()
                },
            )),
            ripdpi_config::TcpFlagOverrides { set: Some(0x12), unset: None },
        ),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(2)),
    ]);
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("active unflagged split must not inherit flags from an inactive step");

    assert_eq!(outcome.execution_receipt.marker_delta, Some(2));
    assert_eq!(outcome.execution_receipt.resolved_offset, Some(b"GET /watch HTTP/1.1\r\nHost: yo".len()));
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
fn unresolved_ech_step_then_active_host_plus_two_uses_active_marker_in_receipt() {
    let payload = rust_packet_seeds::tls_client_hello();
    let mut group = test_group();
    group.actions.tcp_chain.extend([
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::EchExt, 0)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(2)),
    ]);
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(&payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        &payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("active host+2 split should run after absent ECH offset is skipped");

    assert_eq!(outcome.execution_receipt.disposition, TcpExecutionDisposition::Applied);
    assert_eq!(outcome.execution_receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
    assert_eq!(outcome.execution_receipt.marker_delta, Some(2));
    assert!(outcome.execution_receipt.resolved_offset.is_some());
    assert_eq!(outcome.execution_receipt.planned_steps, 1);
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(buf, payload);
}

#[test]
fn active_tls_prelude_with_filtered_send_step_records_tls_record_receipt() {
    for (kind, configured_family) in [
        (TcpChainStepKind::Split, TcpStrategyFamily::TlsRecordSplit),
        (TcpChainStepKind::Disorder, TcpStrategyFamily::Disorder),
    ] {
        let payload = rust_packet_seeds::tls_client_hello();
        let mut group = test_group();
        group.actions.tcp_chain.extend([
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
            TcpChainStep::new(kind, OffsetExpr::host(1)).with_activation_filter(Some(
                ripdpi_config::ActivationFilter { payload_size: Some(NumericRange::new(10, 20)), ..Default::default() },
            )),
        ]);
        let (mut client, _server) = connected_pair();
        let progress = OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );

        let outcome = send_prepared_with_group(
            &mut client,
            &ReceiptTestPlatform::default(),
            &RuntimeConfig::default(),
            &group,
            &payload,
            progress,
            context,
            None,
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect("active TLS prelude should remain positive evidence without active send steps");

        let receipt = outcome.execution_receipt;
        assert_eq!(receipt.disposition, TcpExecutionDisposition::Applied);
        assert_eq!(receipt.configured_family, Some(configured_family));
        assert_eq!(receipt.effective_family, Some(TcpStrategyFamily::TlsRecord));
        assert_eq!(receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
        assert_eq!(receipt.marker_base, None);
        assert_eq!(receipt.marker_delta, None);
        assert_eq!(receipt.resolved_offset, None);
        assert_eq!(receipt.planned_steps, 1);
        assert_eq!(receipt.real_writes_committed, 1);
        assert_eq!(receipt.completed_awaits, 0);
        assert_eq!(receipt.tls_prelude_configured_count, 1);
        assert_eq!(receipt.tls_prelude_applied_count, 1);
        assert_eq!(receipt.tls_prelude_kind, Some(TcpChainStepKind::TlsRec));
    }
}

#[test]
fn filtered_multidisorder_with_modifier_sends_modified_payload_as_activation_skipped() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let expected = b"GET /watch HTTP/1.1\r\nhOsT: youtube.com\r\n\r\n";
    let inactive =
        ripdpi_config::ActivationFilter { payload_size: Some(NumericRange::new(100, 200)), ..Default::default() };
    let mut group = test_group();
    group.actions.mod_http = ripdpi_packets::MH_HMIX;
    group.actions.tcp_chain.extend([
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::host(1)).with_activation_filter(Some(inactive)),
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::host(2)).with_activation_filter(Some(inactive)),
    ]);
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("inactive multidisorder should send the modified payload without plan failure");
    drop(client);
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read relayed payload");
    assert_eq!(received, expected);
    assert_eq!(outcome.execution_receipt.disposition, TcpExecutionDisposition::ActivationSkipped);
    assert_eq!(outcome.execution_receipt.effective_family, None);
    assert_eq!(outcome.execution_receipt.planned_steps, 0);
}

#[test]
fn filtered_split_then_active_disorder_reports_disorder_effective_family() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.extend([
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)).with_activation_filter(Some(
            ripdpi_config::ActivationFilter { payload_size: Some(NumericRange::new(100, 200)), ..Default::default() },
        )),
        TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::host(2)),
    ]);
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(8),
        AdaptivePlannerHints::default(),
    );

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        Some(8),
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("active disorder should execute after inactive split");

    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Disorder));
    assert_eq!(outcome.execution_receipt.marker_delta, Some(2));
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
fn tlsrec_extlen_then_split_host_plus_one_records_applied_prelude_receipt() {
    for fail_wait in [false, true] {
        let payload = rust_packet_seeds::tls_client_hello();
        let mut group = test_group();
        group.actions.tcp_chain.extend([
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)),
        ]);
        let (mut client, _server) = connected_pair();
        let progress = OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let unavailable = default_ttl_unavailable();

        let platform = ReceiptTestPlatform::default();
        if fail_wait {
            platform
                .faults
                .lock()
                .expect("receipt fault queue")
                .push_back(FaultSpec::WaitStage(io::ErrorKind::TimedOut));
        }
        let result = send_prepared_with_group(
            &mut client,
            &platform,
            &RuntimeConfig::default(),
            &group,
            &payload,
            progress,
            context,
            None,
            None,
            &unavailable,
            None,
        );
        let receipt = if fail_wait {
            let error = result.expect_err("wait failure after TLS prefix");
            let receipt = error.execution_receipt().expect("failure receipt").clone();
            assert_eq!(receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
            assert_eq!(receipt.real_writes_committed, 1);
            assert_eq!(receipt.completed_awaits, 0);
            assert!(receipt.payload_bytes_committed > 0);
            receipt
        } else {
            let receipt = result.expect("send TLS payload").execution_receipt;
            assert_eq!(receipt.disposition, TcpExecutionDisposition::Applied);
            receipt
        };
        assert!(receipt.tls_record_prelude_applied, "fail_wait={fail_wait}");
        assert_eq!(receipt.tls_prelude_configured_count, 1);
        assert_eq!(receipt.tls_prelude_applied_count, 1);
        assert_eq!(receipt.tls_prelude_kind, Some(TcpChainStepKind::TlsRec));
        assert_eq!(receipt.tls_prelude_marker_base, Some(TcpOffsetMarkerBase::ExtLen));
        assert_eq!(receipt.tls_prelude_marker_delta, Some(0));
        assert!(receipt.tls_prelude_resolved_offset.is_some());
        assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
        assert_eq!(receipt.marker_delta, Some(1));
    }
}

#[test]
fn tls_record_split_receipt_downgrades_when_prelude_does_not_change_payload() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.extend([
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(1)),
    ]);

    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect("split send should succeed even when TLS prelude is inapplicable");

    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::TlsRecordSplit));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
    let mut received = vec![0u8; payload.len()];
    server.read_exact(&mut received).expect("read relayed payload");
    assert_eq!(received, payload);
}

#[test]
fn unsupported_seq_overlap_records_effective_split_fallback() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::host(1)));

    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 2,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let unavailable = default_ttl_unavailable();

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &unavailable,
        None,
    )
    .expect("unsupported seq-overlap should lower to split");

    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::SeqOverlap));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));

    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
#[cfg(not(target_os = "linux"))]
fn special_plan_failure_preserves_plan_and_marker_provenance() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain = multidisorder_chain();
    let (mut client, _server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let unavailable = default_ttl_unavailable();

    let error = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &unavailable,
        None,
    )
    .expect_err("unsupported multi-disorder special plan should fail");
    let receipt = error.execution_receipt().expect("special failure receipt");

    assert_eq!(receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
    assert_eq!(receipt.configured_family, Some(TcpStrategyFamily::MultiDisorder));
    assert_eq!(receipt.effective_family, Some(TcpStrategyFamily::MultiDisorder));
    assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Absolute));
    assert_eq!(receipt.marker_delta, Some(2));
    assert!(receipt.planned_steps > 0);
    assert!(receipt.resolved_offset.is_some());
    assert!(receipt.terminal_reason.is_some());
}

#[test]
fn fake_disorder_ttl_lowering_records_effective_fake_split_family() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::host(1)));
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(8),
        AdaptivePlannerHints::default(),
    );
    let unavailable = default_ttl_unavailable();

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        Some(8),
        None,
        &unavailable,
        None,
    )
    .expect("fake disorder should lower after unavailable TTL send");

    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::FakeDisorder));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::FakeSplit));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));

    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
fn hostfake_at_host_start_records_effective_split_family() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::host(0)));
    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(8),
        AdaptivePlannerHints::default(),
    );
    let unavailable = default_ttl_unavailable();

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        Some(8),
        None,
        &unavailable,
        None,
    )
    .expect("hostfake without a host span should lower to split");

    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::HostFake));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));

    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

#[test]
#[cfg(not(target_os = "linux"))]
fn ipfrag2_special_plan_receipt_records_effective_fallback_family() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(12)));

    let (mut client, mut server) = connected_pair();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let unavailable = default_ttl_unavailable();

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &unavailable,
        None,
    )
    .expect("ipfrag2 fallback send should succeed");

    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::IpFragment2));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
    assert_eq!(outcome.execution_receipt.payload_bytes_committed, payload.len());

    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}
