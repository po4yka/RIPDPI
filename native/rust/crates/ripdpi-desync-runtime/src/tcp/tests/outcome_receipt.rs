use super::*;

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use crate::platform::{
    FakeTcpOptions, OrderedTcpSegment, TcpActivationState, TcpFakeSender, TcpFlagOverrides, TcpFragmentSender,
    TcpPayloadSegment, TcpPayloadSender, TcpPlatformCapabilities, TcpSocketOptions, TcpStageWait,
};

struct ReceiptTestPlatform;

impl TcpPlatformCapabilities for ReceiptTestPlatform {
    fn detect_default_ttl(&self) -> Option<u8> {
        Some(64)
    }

    fn seqovl_supported(&self) -> bool {
        false
    }

    fn supports_fake_retransmit(&self) -> bool {
        false
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
        _ttl: u8,
        _md5sig: bool,
        _default_ttl: u8,
        _options: FakeTcpOptions<'_>,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
        _wait: TcpStageWait,
    ) -> io::Result<()> {
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
