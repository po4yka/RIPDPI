use std::net::TcpStream;
use std::os::fd::{FromRawFd, IntoRawFd, RawFd};

use serde::Deserialize;
use tracing::{debug, error, info};

use ripdpi_runtime::platform::{self, TcpPayloadSegment};

use crate::protocol::HelperResponse;

fn adopt_tcp_stream(fd: RawFd) -> TcpStream {
    // SAFETY: root-helper command fds come from SCM_RIGHTS and ownership is
    // transferred into this process exactly once for the duration of a handler.
    unsafe { TcpStream::from_raw_fd(fd) }
}

fn adopt_udp_socket(fd: RawFd) -> std::net::UdpSocket {
    // SAFETY: root-helper command fds come from SCM_RIGHTS and ownership is
    // transferred into this process exactly once for the duration of a handler.
    unsafe { std::net::UdpSocket::from_raw_fd(fd) }
}

// ---------------------------------------------------------------------------
// probe_capabilities
// ---------------------------------------------------------------------------

pub fn handle_probe_capabilities() -> (HelperResponse, Option<RawFd>) {
    info!("probing capabilities");
    match platform::probe_ip_fragmentation_capabilities(None) {
        Ok(caps) => {
            let data = serde_json::json!({
                "raw_ipv4": caps.raw_ipv4,
                "raw_ipv6": caps.raw_ipv6,
                "tcp_repair": caps.tcp_repair,
            });
            info!(?caps, "capabilities probed");
            (HelperResponse::success(data), None)
        }
        Err(e) => {
            error!(%e, "probe_capabilities failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

// ---------------------------------------------------------------------------
// send_fake_rst
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct FakeRstParams {
    pub default_ttl: u8,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

pub fn handle_send_fake_rst(fd: RawFd, params: FakeRstParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, ttl = params.default_ttl, "send_fake_rst");
    let stream = adopt_tcp_stream(fd);
    match platform::send_fake_rst_reserved(
        &stream,
        params.default_ttl,
        None,
        platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        params.ipv4_identification,
    ) {
        Ok(()) => {
            // Return the fd back to the caller (don't let Drop close it).
            let _ = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), None)
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_fake_rst failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

#[derive(Deserialize)]
pub struct FlaggedTcpPayloadParams {
    pub payload: Vec<u8>,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

pub fn handle_send_flagged_tcp_payload(fd: RawFd, params: FlaggedTcpPayloadParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, len = params.payload.len(), "send_flagged_tcp_payload");
    let stream = adopt_tcp_stream(fd);
    match platform::send_flagged_tcp_payload_reserved(
        &stream,
        &params.payload,
        params.default_ttl,
        None,
        params.md5sig,
        platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        params.ipv4_identification,
    ) {
        Ok(()) => {
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_flagged_tcp_payload failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

// ---------------------------------------------------------------------------
// send_seqovl_tcp
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct SeqOvlParams {
    pub real_chunk: Vec<u8>,
    pub fake_prefix: Vec<u8>,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

pub fn handle_send_seqovl_tcp(fd: RawFd, params: SeqOvlParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, "send_seqovl_tcp");
    let stream = adopt_tcp_stream(fd);
    match platform::send_seqovl_tcp_reserved(
        &stream,
        &params.real_chunk,
        &params.fake_prefix,
        params.default_ttl,
        None,
        params.md5sig,
        platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        params.ipv4_identification,
    ) {
        Ok(()) => {
            // The stream fd may have been replaced via dup2 internally.
            // Return whatever fd the stream now holds.
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_seqovl_tcp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

// ---------------------------------------------------------------------------
// send_multi_disorder_tcp
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct MultiDisorderParams {
    pub payload: Vec<u8>,
    pub segments: Vec<SegmentSpec>,
    pub default_ttl: u8,
    #[serde(default)]
    pub inter_segment_delay_ms: u32,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
}

#[derive(Deserialize)]
pub struct SegmentSpec {
    pub start: usize,
    pub end: usize,
}

pub fn handle_send_multi_disorder_tcp(fd: RawFd, params: MultiDisorderParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, segments = params.segments.len(), "send_multi_disorder_tcp");
    let stream = adopt_tcp_stream(fd);
    let segments: Vec<TcpPayloadSegment> =
        params.segments.iter().map(|s| TcpPayloadSegment { start: s.start, end: s.end }).collect();

    match platform::send_multi_disorder_tcp_reserved(
        &stream,
        &params.payload,
        &segments,
        params.default_ttl,
        None,
        params.inter_segment_delay_ms,
        params.md5sig,
        platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        &params.ipv4_identifications,
    ) {
        Ok(()) => {
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_multi_disorder_tcp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

// ---------------------------------------------------------------------------
// send_ordered_tcp_segments
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct OrderedTcpSegmentParams {
    pub payload: Vec<u8>,
    pub ttl: u8,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    pub sequence_offset: usize,
    #[serde(default)]
    pub use_fake_timestamp: bool,
}

#[derive(Deserialize)]
pub struct OrderedTcpSegmentsParams {
    pub segments: Vec<OrderedTcpSegmentParams>,
    pub original_payload_len: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub timestamp_delta_ticks: Option<i32>,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
    #[serde(default)]
    pub wait_enabled: bool,
    #[serde(default)]
    pub wait_poll_ms: u64,
}

pub fn handle_send_ordered_tcp_segments(
    fd: RawFd,
    params: OrderedTcpSegmentsParams,
) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, segments = params.segments.len(), "send_ordered_tcp_segments");
    let stream = adopt_tcp_stream(fd);
    let segments: Vec<platform::OrderedTcpSegment<'_>> = params
        .segments
        .iter()
        .map(|segment| platform::OrderedTcpSegment {
            payload: segment.payload.as_slice(),
            ttl: segment.ttl,
            flags: platform::TcpFlagOverrides { set: segment.tcp_flags_set, unset: segment.tcp_flags_unset },
            sequence_offset: segment.sequence_offset,
            use_fake_timestamp: segment.use_fake_timestamp,
        })
        .collect();

    match platform::send_ordered_tcp_segments_reserved(
        &stream,
        &segments,
        params.original_payload_len,
        params.default_ttl,
        None,
        params.md5sig,
        params.timestamp_delta_ticks,
        &params.ipv4_identifications,
        (params.wait_enabled, std::time::Duration::from_millis(params.wait_poll_ms.max(1))),
    ) {
        Ok(()) => {
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_ordered_tcp_segments failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

// ---------------------------------------------------------------------------
// send_ip_fragmented_tcp
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct IpFragTcpParams {
    pub payload: Vec<u8>,
    pub split_offset: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

pub fn handle_send_ip_fragmented_tcp(fd: RawFd, params: IpFragTcpParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, split = params.split_offset, "send_ip_fragmented_tcp");
    let stream = adopt_tcp_stream(fd);
    match platform::send_ip_fragmented_tcp_reserved(
        &stream,
        &params.payload,
        params.split_offset,
        params.default_ttl,
        None,
        params.disorder,
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        params.ipv4_identification,
    ) {
        Ok(()) => {
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_ip_fragmented_tcp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

// ---------------------------------------------------------------------------
// send_ip_fragmented_udp
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct IpFragUdpParams {
    pub target_addr: String,
    pub payload: Vec<u8>,
    pub split_offset: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

pub fn handle_send_ip_fragmented_udp(fd: RawFd, params: IpFragUdpParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, split = params.split_offset, "send_ip_fragmented_udp");

    let target: std::net::SocketAddr = match params.target_addr.parse() {
        Ok(addr) => addr,
        Err(e) => return (HelperResponse::error(format!("invalid target_addr: {e}")), None),
    };

    let socket = adopt_udp_socket(fd);
    match platform::send_ip_fragmented_udp_reserved(
        &socket,
        target,
        &params.payload,
        params.split_offset,
        params.default_ttl,
        None,
        params.disorder,
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        params.ipv4_identification,
    ) {
        Ok(()) => {
            // Return fd to caller.
            let _ = socket.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), None)
        }
        Err(e) => {
            let _ = socket.into_raw_fd();
            error!(%e, "send_ip_fragmented_udp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}
