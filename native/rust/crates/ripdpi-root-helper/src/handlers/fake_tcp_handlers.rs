use std::os::fd::{IntoRawFd, RawFd};

use ripdpi_privileged_ops as platform;
use ripdpi_privileged_ops::TcpPayloadSegment;
use ripdpi_root_helper_protocol::{
    FakeRstParams, FakeTcpParams, FlaggedTcpPayloadParams, HelperResponse, MultiDisorderParams,
    OrderedTcpSegmentsParams, SeqOvlParams,
};
use tracing::{debug, error};

use super::fd_adoption::adopt_tcp_stream;

pub fn handle_send_fake_rst(fd: RawFd, params: FakeRstParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, ttl = params.default_ttl, "send_fake_rst");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
    match platform::send_fake_rst(
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

pub fn handle_send_fake_tcp(fd: RawFd, params: FakeTcpParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, len = params.original_prefix.len(), "send_fake_tcp");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
    let options = platform::FakeTcpOptions {
        secondary_fake_prefix: params.secondary_fake_prefix.as_deref(),
        timestamp_delta_ticks: params.timestamp_delta_ticks,
        protect_path: None,
        fake_flags: platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        orig_flags: platform::TcpFlagOverrides { set: params.tcp_flags_orig_set, unset: params.tcp_flags_orig_unset },
        require_raw_path: params.require_raw_path,
        force_raw_original: params.force_raw_original,
        ipv4_identifications: params.ipv4_identifications,
    };
    match platform::send_fake_tcp(
        &stream,
        &params.original_prefix,
        &params.fake_prefix,
        params.ttl,
        params.md5sig,
        params.default_ttl,
        options,
        (params.wait_enabled, std::time::Duration::from_millis(params.wait_poll_ms.max(1))),
    ) {
        Ok(()) => {
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_fake_tcp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}

pub fn handle_send_flagged_tcp_payload(fd: RawFd, params: FlaggedTcpPayloadParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, len = params.payload.len(), "send_flagged_tcp_payload");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
    match platform::send_flagged_tcp_payload(
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

pub fn handle_send_seqovl_tcp(fd: RawFd, params: SeqOvlParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, "send_seqovl_tcp");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
    match platform::send_seqovl_tcp(
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

pub fn handle_send_multi_disorder_tcp(fd: RawFd, params: MultiDisorderParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, segments = params.segments.len(), "send_multi_disorder_tcp");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
    let segments: Vec<TcpPayloadSegment> =
        params.segments.iter().map(|s| TcpPayloadSegment { start: s.start, end: s.end }).collect();

    match platform::send_multi_disorder_tcp(
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

pub fn handle_send_ordered_tcp_segments(
    fd: RawFd,
    params: OrderedTcpSegmentsParams,
) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, segments = params.segments.len(), "send_ordered_tcp_segments");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
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

    match platform::send_ordered_tcp_segments(
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
