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

mod disorder_ttl;
mod fake_send_safety;
mod fake_split;

enum FaultSpec {
    WaitStage(io::ErrorKind),
    OrderedSegments(io::ErrorKind),
    OrderedSegmentsAfterPartialGenuine,
    FakeSendAfterPrefix(usize),
    FakePayload { bytes: Vec<u8>, kind: io::ErrorKind },
}

type FaultQueue = Mutex<VecDeque<FaultSpec>>;

#[derive(Default)]
pub(super) struct ReceiptTestPlatform {
    raw_unavailable: bool,
    faults: FaultQueue,
    require_disorder_raw_original: bool,
    /// The socket TTL seen by each fake RST send.
    fake_rst_ttls: Mutex<Vec<u32>>,
    /// Fake segments handed to the raw ordered-segment sender.
    ordered_fake_segments: Mutex<usize>,
    /// Bytes of those fake segments, in send order.
    ordered_fake_payloads: Mutex<Vec<Vec<u8>>>,
    /// The socket TTL seen by each send-queue wait.
    stage_ttls: Mutex<Vec<u32>>,
}

impl TcpPlatformCapabilities for ReceiptTestPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        Some(64)
    }

    fn seqovl_supported(&self) -> bool {
        !self.raw_unavailable
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

    fn wait_tcp_stage(&self, stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
        self.stage_ttls.lock().expect("stage ttl log").push(stream.ttl()?);
        let mut faults = self.faults.lock().expect("receipt fault queue");
        match faults.front() {
            Some(FaultSpec::WaitStage(kind)) => {
                let kind = *kind;
                faults.pop_front();
                Err(io::Error::new(kind, "injected stage wait failure"))
            }
            _ => Ok(()),
        }
    }
}

impl TcpFakeSender for ReceiptTestPlatform {
    fn send_fake_rst(
        &self,
        stream: &TcpStream,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        self.fake_rst_ttls.lock().expect("fake rst ttl log").push(stream.ttl().expect("read socket ttl"));
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
        options: FakeTcpOptions<'_>,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
        _wait: TcpStageWait,
    ) -> io::Result<()> {
        let mut faults = self.faults.lock().expect("receipt fault queue");
        if let Some(FaultSpec::FakeSendAfterPrefix(len)) = faults.front() {
            let len = *len;
            faults.pop_front();
            drop(faults);
            (&*stream).write_all(&original_prefix[..len.min(original_prefix.len())])?;
            return Err(io::Error::from_raw_os_error(libc::EPERM));
        }
        drop(faults);
        if self.require_disorder_raw_original {
            if options.require_raw_path || options.force_raw_original != (ttl == 1) {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "incorrect disorder original transport"));
            }
        } else if ttl == 1 {
            return Err(io::Error::from_raw_os_error(libc::EPERM));
        }
        let mut faults = self.faults.lock().expect("receipt fault queue");
        if let Some(FaultSpec::FakePayload { bytes, kind }) = faults.front()
            && bytes == original_prefix
        {
            let kind = *kind;
            faults.pop_front();
            return Err(io::Error::new(kind, "injected fake payload failure"));
        }
        drop(faults);
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
        let mut faults = self.faults.lock().expect("receipt fault queue");
        if let Some(FaultSpec::OrderedSegments(kind)) = faults.front() {
            let kind = *kind;
            faults.pop_front();
            return Err(io::Error::new(kind, "injected ordered segment failure"));
        }
        if matches!(faults.front(), Some(FaultSpec::OrderedSegmentsAfterPartialGenuine)) {
            faults.pop_front();
            drop(faults);
            let genuine = segments.iter().find(|segment| !segment.use_fake_timestamp).expect("real Host segment");
            let mut stream = stream;
            stream.write_all(&genuine.payload[..3])?;
            return Err(io::Error::other(io::Error::from(io::ErrorKind::WouldBlock)));
        }
        drop(faults);
        // Like the raw sender, only genuine bytes take stream sequence space.
        let mut stream = stream;
        for segment in segments {
            if segment.use_fake_timestamp {
                *self.ordered_fake_segments.lock().expect("ordered fake segment count") += 1;
                self.ordered_fake_payloads.lock().expect("ordered fake payloads").push(segment.payload.to_vec());
            } else {
                stream.write_all(segment.payload)?;
            }
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
fn plain_fallback_failures_keep_partial_progress_and_configured_marker() {
    use std::net::Shutdown;
    let payload: Vec<u8> = (0..8 * 1024 * 1024).map(|index| (index % 251) as u8).collect();
    for inactive in [false, true] {
        let mut group = test_group();
        let step = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(-(payload.len() as i64) - 1));
        group.actions.tcp_chain.push(if inactive {
            step.with_activation_filter(Some(ripdpi_config::ActivationFilter {
                round: Some(NumericRange::new(2, 2)),
                ..Default::default()
            }))
        } else {
            step
        });
        let (mut client, mut peer) = connected_pair();
        socket2::SockRef::from(&client).set_send_buffer_size(1024).expect("small send buffer");
        client.set_nonblocking(true).expect("nonblocking sender");
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer deadline");
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let error = send_prepared_with_group(
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
        .expect_err("plain fallback reaches peer backpressure");
        client.shutdown(Shutdown::Write).expect("finish queued prefix");
        let mut received = Vec::new();
        peer.read_to_end(&mut received).expect("read queued prefix");
        assert!(!received.is_empty() && received.len() < payload.len());
        assert_eq!(received, payload[..received.len()]);
        assert_eq!(error.bytes_committed(), received.len(), "inactive={inactive}");
        let receipt = error.execution_receipt().expect("failed plain write receipt");
        assert_eq!(receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
        assert_eq!(receipt.configured_family, Some(TcpStrategyFamily::Split));
        assert_eq!(receipt.effective_family, None);
        assert_eq!(receipt.marker_base, Some(TcpOffsetMarkerBase::Absolute));
        // Telemetry is bounded; transport progress above must remain exact.
        assert_eq!(receipt.payload_bytes_committed, received.len().min(usize::from(u16::MAX)));
        assert_eq!(receipt.completed_actions, 0);
        assert_eq!(receipt.terminal_reason, Some(crate::TcpTerminalReason::Transport));
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
    // Reach round 2 explicitly; seqovl itself only runs in round 1.
    group.set_round_activation(Some(NumericRange::new(1, 2)));

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
fn fake_disorder_preserves_first_original_transport_without_requiring_raw() {
    let payload = b"GET / HTTP/1.1\r\nHost: audit.invalid\r\n\r\n";
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::host(1)));
    group.actions.fake_tcp_timestamp_enabled = true;
    let (mut client, mut server) = connected_pair();
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("peer read timeout");
    let progress =
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        Some(8),
        AdaptivePlannerHints::default(),
    );
    let platform = ReceiptTestPlatform { require_disorder_raw_original: true, ..ReceiptTestPlatform::default() };

    let outcome = send_prepared_with_group(
        &mut client,
        &platform,
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
    .expect("first original transport must retain the disorder TTL without requiring raw selection");

    let mut received = vec![0; payload.len()];
    server.read_exact(&mut received).expect("read original payload");
    assert_eq!(received, payload);
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::FakeDisorder));
}

#[test]
fn fake_disorder_boundary_failures_restore_socket_ttl() {
    let payload = b"GET / HTTP/1.1\r\nHost: audit.invalid\r\n\r\n";
    for fail_write in [false, true] {
        let mut group = test_group();
        group
            .actions
            .tcp_chain
            .push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::absolute(payload.len() as i64)));
        let (mut client, mut server) = connected_pair();
        client.set_ttl(37).expect("set initial socket TTL");
        server.set_read_timeout(Some(Duration::from_secs(2))).expect("peer read timeout");
        let platform = ReceiptTestPlatform::default();
        if fail_write {
            client.shutdown(std::net::Shutdown::Write).expect("close write half");
        } else {
            platform.faults.lock().expect("faults").push_back(FaultSpec::WaitStage(io::ErrorKind::TimedOut));
        }
        let progress =
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            Some(8),
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
            Some(8),
            None,
            &default_ttl_unavailable(),
            None,
        )
        .expect_err("boundary write or wait must fail");

        assert_eq!(client.ttl().expect("socket TTL after failure"), 37);
        if fail_write {
            assert_eq!(error.source_error().kind(), io::ErrorKind::BrokenPipe);
            assert_eq!(error.bytes_committed(), 0);
            assert_eq!(server.read(&mut [0; 1]).expect("peer EOF"), 0);
        } else {
            assert_eq!(error.source_error().kind(), io::ErrorKind::TimedOut);
            assert_eq!(error.bytes_committed(), payload.len());
            let mut received = vec![0; payload.len()];
            server.read_exact(&mut received).expect("read original payload");
            assert_eq!(received, payload);
        }
    }
}

#[test]
fn fake_disorder_receipt_matches_boundary_regions_and_sender_waits() {
    let payload = b"GET / HTTP/1.1\r\nHost: audit.invalid\r\n\r\n".as_slice();
    for (bytes, offset, ttl_unavailable, order, seq_mode, expected_family, writes, awaits) in [
        (
            payload,
            payload.len(),
            false,
            FakeOrder::BeforeEach,
            FakeSeqMode::Duplicate,
            Some(TcpStrategyFamily::Disorder),
            1,
            1,
        ),
        (payload, payload.len(), true, FakeOrder::BeforeEach, FakeSeqMode::Duplicate, None, 1, 1),
        (payload, 0, false, FakeOrder::BeforeEach, FakeSeqMode::Duplicate, Some(TcpStrategyFamily::Fake), 1, 1),
        (&[][..], 0, false, FakeOrder::BeforeEach, FakeSeqMode::Duplicate, None, 0, 0),
        (payload, 2, false, FakeOrder::BeforeEach, FakeSeqMode::Duplicate, Some(TcpStrategyFamily::FakeDisorder), 2, 2),
        (
            payload,
            2,
            false,
            FakeOrder::BeforeEach,
            FakeSeqMode::Sequential,
            Some(TcpStrategyFamily::FakeDisorder),
            2,
            1,
        ),
        (
            payload,
            2,
            false,
            FakeOrder::AllFakesFirst,
            FakeSeqMode::Duplicate,
            Some(TcpStrategyFamily::FakeDisorder),
            2,
            1,
        ),
        (
            payload,
            2,
            false,
            FakeOrder::RealFakeRealFake,
            FakeSeqMode::Duplicate,
            Some(TcpStrategyFamily::FakeDisorder),
            2,
            1,
        ),
        (
            payload,
            2,
            false,
            FakeOrder::AllRealsFirst,
            FakeSeqMode::Duplicate,
            Some(TcpStrategyFamily::FakeDisorder),
            2,
            1,
        ),
        (payload, 0, false, FakeOrder::AllFakesFirst, FakeSeqMode::Sequential, Some(TcpStrategyFamily::Fake), 1, 1),
    ] {
        let mut step = TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::absolute(offset as i64));
        let mut fake_payload = step.fake_payload().expect("fake step payload");
        fake_payload.ordering.order = order;
        fake_payload.ordering.seq_mode = seq_mode;
        step = step.with_fake_payload(fake_payload);
        let mut group = test_group();
        group.actions.tcp_chain.push(step);
        let (mut client, mut server) = connected_pair();
        client.set_ttl(37).expect("initial TTL");
        server.set_read_timeout(Some(Duration::from_secs(2))).expect("peer read timeout");
        let progress = OutboundProgress {
            round: 1,
            payload_size: bytes.len(),
            stream_start: 0,
            stream_end: bytes.len().saturating_sub(1),
        };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(bytes),
            None,
            None,
            Some(8),
            AdaptivePlannerHints::default(),
        );
        let platform = ReceiptTestPlatform { require_disorder_raw_original: true, ..ReceiptTestPlatform::default() };
        let outcome = send_prepared_with_group(
            &mut client,
            &platform,
            &RuntimeConfig::default(),
            &group,
            bytes,
            progress,
            context,
            Some(8),
            None,
            &AtomicBool::new(ttl_unavailable),
            None,
        )
        .expect("fake disorder execution");

        let mut received = vec![0; bytes.len()];
        server.read_exact(&mut received).expect("read original bytes");
        assert_eq!(received, bytes);
        assert_eq!(client.ttl().expect("restored socket TTL"), 37);
        let receipt = outcome.execution_receipt;
        assert_eq!(receipt.configured_family, Some(TcpStrategyFamily::FakeDisorder));
        assert_eq!(receipt.effective_family, expected_family, "offset={offset} order={order:?}");
        assert_eq!(receipt.real_writes_committed, writes);
        assert_eq!(receipt.completed_awaits, awaits);
        assert_eq!(receipt.payload_bytes_committed, bytes.len());
        assert_eq!(receipt.completed_actions, writes + awaits);
        let fallback = (expected_family != Some(TcpStrategyFamily::FakeDisorder))
            .then_some(TcpFallbackReason::StrategyFamilyFallback);
        assert_eq!(receipt.fallback_reason, fallback);
    }
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
    let unavailable = AtomicBool::new(true);

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

#[test]
fn flagged_fake_rst_lowers_the_socket_ttl_around_the_reset() {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group.actions.ttl = Some(5);
    let plain = TcpChainStep::new(TcpChainStepKind::FakeRst, OffsetExpr::host(1));
    // Fake flag overrides route the step to the plan executor.
    group.actions.tcp_chain.push(TcpChainStep::from_typed_step(ripdpi_config::TcpTypedChainStep::FakeRst {
        common: plain.typed_step().common(),
        fake_flags: ripdpi_config::TcpFlagOverrides { set: Some(0x08), unset: None },
    }));
    let (mut client, mut server) = connected_pair();
    let default_ttl = client.ttl().expect("read default ttl");
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
    let platform = ReceiptTestPlatform::default();

    send_prepared_with_group(
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
    .expect("fakerst step writes the payload");

    assert_eq!(*platform.fake_rst_ttls.lock().expect("fake rst ttl log"), vec![5]);
    assert_eq!(client.ttl().expect("read restored ttl"), default_ttl);
    let mut buf = vec![0u8; payload.len()];
    server.read_exact(&mut buf).expect("read relayed payload");
    assert_eq!(&buf, payload);
}

fn send_http_hostfake(platform: &ReceiptTestPlatform) -> (Vec<u8>, OutboundSendOutcome) {
    send_http_hostfake_chain(platform, None)
}

fn send_http_hostfake_chain(
    platform: &ReceiptTestPlatform,
    chain: Option<Vec<TcpChainStep>>,
) -> (Vec<u8>, OutboundSendOutcome) {
    let payload = b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n";
    let mut group = test_group();
    group
        .actions
        .tcp_chain
        .push(TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0)));
    if let Some(chain) = chain {
        group.actions.tcp_chain = chain;
    }
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
        platform,
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
    .expect("hostfake send");
    drop(client);
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read relayed payload");
    (received, outcome)
}

#[test]
fn hostfake_sends_fakes_beside_the_stream_and_the_payload_once() {
    let platform = ReceiptTestPlatform::default();

    let (received, outcome) = send_http_hostfake(&platform);

    assert_eq!(received, b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n");
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::HostFake));
    assert_eq!(*platform.ordered_fake_segments.lock().expect("ordered fake segment count"), 2);
}

#[test]
fn hostfake_decoys_differ_between_sends_of_the_same_round() {
    let first = ReceiptTestPlatform::default();
    let second = ReceiptTestPlatform::default();

    send_http_hostfake(&first);
    send_http_hostfake(&second);

    let first = first.ordered_fake_payloads.into_inner().expect("first fake payloads");
    let second = second.ordered_fake_payloads.into_inner().expect("second fake payloads");
    assert_eq!(first.len(), 2);
    assert_ne!(first, second, "two connections sent the same decoy bytes");
}

#[test]
fn hostfake_without_the_raw_path_sends_the_host_as_a_plain_split() {
    let platform = ReceiptTestPlatform { raw_unavailable: true, ..Default::default() };

    let (received, outcome) = send_http_hostfake(&platform);

    assert_eq!(received, b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n");
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
    assert_eq!(*platform.ordered_fake_segments.lock().expect("ordered fake segment count"), 0);
}

#[test]
fn hostfake_raw_refusal_keeps_later_steps_and_suffix() {
    let platform = ReceiptTestPlatform { raw_unavailable: true, ..Default::default() };
    let chain = vec![
        TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::EndHost, 0)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::PayloadEnd, 0)),
    ];
    let (received, outcome) = send_http_hostfake_chain(&platform, Some(chain));
    assert_eq!(received, b"GET /watch HTTP/1.1\r\nHost: youtube.com\r\n\r\n");
    assert_eq!(outcome.execution_receipt.configured_family, Some(TcpStrategyFamily::HostFake));
    assert_eq!(outcome.execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
    assert_eq!(outcome.execution_receipt.fallback_reason, Some(TcpFallbackReason::StrategyFamilyFallback));
    assert_eq!(outcome.execution_receipt.planned_steps, 2);
    assert_eq!(outcome.bytes_committed, received.len());
}

mod hostfake_late_error;
mod hostfake_terminal_noop;
mod oob_urgent;
