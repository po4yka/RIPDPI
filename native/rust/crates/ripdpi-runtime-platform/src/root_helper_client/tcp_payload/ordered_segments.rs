use std::io;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{OrderedTcpSegmentParams, OrderedTcpSegmentsParams, CMD_SEND_ORDERED_TCP_SEGMENTS};

use crate::{OrderedTcpSegment, TcpStageWait};

use super::super::{command_params, RootHelperClient};

impl RootHelperClient {
    /// Send an ordered raw TCP batch via the helper. Returns replacement fd.
    #[allow(clippy::too_many_arguments)]
    pub fn send_ordered_tcp_segments(
        &self,
        stream_fd: RawFd,
        segments: &[OrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ipv4_identifications: &[u16],
        wait: TcpStageWait,
    ) -> io::Result<Option<RawFd>> {
        let segment_specs: Vec<OrderedTcpSegmentParams> = segments
            .iter()
            .map(|segment| OrderedTcpSegmentParams {
                payload: segment.payload.to_vec(),
                ttl: segment.ttl,
                tcp_flags_set: segment.flags.set,
                tcp_flags_unset: segment.flags.unset,
                sequence_offset: segment.sequence_offset,
                use_fake_timestamp: segment.use_fake_timestamp,
            })
            .collect();
        let params = command_params(OrderedTcpSegmentsParams {
            segments: segment_specs,
            original_payload_len,
            default_ttl,
            md5sig,
            timestamp_delta_ticks,
            ipv4_identifications: ipv4_identifications.to_vec(),
            wait_enabled: wait.0,
            wait_poll_ms: wait.1.as_millis() as u64,
        })?;
        let (_resp, fd) = self.transport.send_command(CMD_SEND_ORDERED_TCP_SEGMENTS, params, Some(stream_fd))?;
        Ok(fd)
    }
}
