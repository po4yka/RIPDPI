use std::io;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{
    FakeRstParams, FlaggedTcpPayloadParams, MultiDisorderParams, OrderedTcpSegmentParams, OrderedTcpSegmentsParams,
    SegmentSpec, SeqOvlParams, CMD_SEND_FAKE_RST, CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_MULTI_DISORDER_TCP,
    CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_SEQOVL_TCP,
};

use super::{command_params, RootHelperClient};
use crate::{OrderedTcpSegment, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait};

impl RootHelperClient {
    /// Send a fake RST packet via the helper.
    pub fn send_fake_rst(
        &self,
        stream_fd: RawFd,
        default_ttl: u8,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<()> {
        let params = command_params(FakeRstParams {
            default_ttl,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_FAKE_RST, params, Some(stream_fd))?;
        Ok(())
    }

    pub fn send_flagged_tcp_payload(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        default_ttl: u8,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<RawFd>> {
        let params = command_params(FlaggedTcpPayloadParams {
            payload: payload.to_vec(),
            default_ttl,
            md5sig,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, fd) = self.transport.send_command(CMD_SEND_FLAGGED_TCP_PAYLOAD, params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Perform TCP sequence overlap via the helper. Returns replacement fd.
    pub fn send_seqovl_tcp(
        &self,
        stream_fd: RawFd,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<RawFd>> {
        let params = command_params(SeqOvlParams {
            real_chunk: real_chunk.to_vec(),
            fake_prefix: fake_prefix.to_vec(),
            default_ttl,
            md5sig,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, fd) = self.transport.send_command(CMD_SEND_SEQOVL_TCP, params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send multi-disorder TCP segments via the helper. Returns replacement fd.
    #[allow(clippy::too_many_arguments)]
    pub fn send_multi_disorder_tcp(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        segments: &[TcpPayloadSegment],
        default_ttl: u8,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ipv4_identifications: &[u16],
    ) -> io::Result<Option<RawFd>> {
        let segments = segments.iter().map(|s| SegmentSpec { start: s.start, end: s.end }).collect();
        let params = command_params(MultiDisorderParams {
            payload: payload.to_vec(),
            segments,
            default_ttl,
            inter_segment_delay_ms,
            md5sig,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identifications: ipv4_identifications.to_vec(),
        })?;
        let (_resp, fd) = self.transport.send_command(CMD_SEND_MULTI_DISORDER_TCP, params, Some(stream_fd))?;
        Ok(fd)
    }

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
