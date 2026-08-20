use super::*;

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use crate::platform::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFakeSender, TcpFlagOverrides, TcpFragmentSender,
    TcpPayloadSegment, TcpPayloadSender, TcpPlatformCapabilities, TcpSocketOptions, TcpStageWait,
};
use crate::types::{TcpExecutionDisposition, TcpFallbackReason, TcpOffsetMarkerBase, TcpStrategyFamily};

struct ReceiptTestPlatform;

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
        Ok(())
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
        &ReceiptTestPlatform,
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
fn tlsrec_extlen_then_split_host_plus_one_records_applied_prelude_receipt() {
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

    let outcome = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform,
        &RuntimeConfig::default(),
        &group,
        &payload,
        progress,
        context,
        None,
        None,
        &unavailable,
        None,
    )
    .expect("tlsrec(extlen)+split(host+1) should send the ClientHello");

    let receipt = outcome.execution_receipt;
    assert_eq!(receipt.disposition, TcpExecutionDisposition::Applied);
    assert_eq!(receipt.tls_prelude_configured_count, 1);
    assert_eq!(receipt.tls_prelude_applied_count, 1);
    assert_eq!(receipt.tls_prelude_kind, Some(TcpChainStepKind::TlsRec));
    assert_eq!(receipt.tls_prelude_marker_base, Some(TcpOffsetMarkerBase::ExtLen));
    assert_eq!(receipt.tls_prelude_marker_delta, Some(0));
    assert!(receipt.tls_prelude_resolved_offset.is_some());
    assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Host));
    assert_eq!(receipt.marker_delta, Some(1));
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
        &ReceiptTestPlatform,
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
        &ReceiptTestPlatform,
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
        &ReceiptTestPlatform,
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
        &ReceiptTestPlatform,
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
        &ReceiptTestPlatform,
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
        &ReceiptTestPlatform,
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
