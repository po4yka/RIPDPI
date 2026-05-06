use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{
    TcpFlagOverrides as DesyncTcpFlagOverrides, TcpPayloadSegment as DesyncTcpPayloadSegment,
};

use ripdpi_runtime_platform as runtime_platform;

use super::conversion::to_runtime_flags;

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[DesyncTcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    original_flags: DesyncTcpFlagOverrides,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
) -> io::Result<()> {
    let runtime_segments = to_runtime_payload_segments(segments);
    runtime_platform::raw_packet::send_multi_disorder_tcp(
        stream,
        payload,
        &runtime_segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        to_runtime_flags(original_flags),
        ip_id_mode,
    )
}

fn to_runtime_payload_segments(
    segments: &[DesyncTcpPayloadSegment],
) -> Vec<runtime_platform::raw_packet::TcpPayloadSegment> {
    segments
        .iter()
        .map(|segment| runtime_platform::raw_packet::TcpPayloadSegment { start: segment.start, end: segment.end })
        .collect()
}
