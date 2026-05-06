use std::io;
use std::net::TcpStream;

use ripdpi_desync_runtime::platform::{OrderedTcpSegment as DesyncOrderedTcpSegment, TcpStageWait};

use ripdpi_runtime_platform as runtime_platform;

use super::conversion::to_runtime_flags;

#[allow(clippy::too_many_arguments)]
pub(crate) fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[DesyncOrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<ripdpi_config::IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let runtime_segments = to_runtime_ordered_segments(segments);
    runtime_platform::raw_packet::send_ordered_tcp_segments(
        stream,
        &runtime_segments,
        original_payload_len,
        default_ttl,
        protect_path,
        md5sig,
        timestamp_delta_ticks,
        ip_id_mode,
        wait,
    )
}

fn to_runtime_ordered_segments<'a>(
    segments: &[DesyncOrderedTcpSegment<'a>],
) -> Vec<runtime_platform::raw_packet::OrderedTcpSegment<'a>> {
    segments
        .iter()
        .map(|segment| runtime_platform::raw_packet::OrderedTcpSegment {
            payload: segment.payload,
            ttl: segment.ttl,
            flags: to_runtime_flags(segment.flags),
            sequence_offset: segment.sequence_offset,
            use_fake_timestamp: segment.use_fake_timestamp,
        })
        .collect()
}
