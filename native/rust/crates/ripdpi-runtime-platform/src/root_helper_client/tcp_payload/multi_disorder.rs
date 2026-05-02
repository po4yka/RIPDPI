use std::io;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{MultiDisorderParams, SegmentSpec, CMD_SEND_MULTI_DISORDER_TCP};

use crate::{TcpFlagOverrides, TcpPayloadSegment};

use super::super::{command_params, RootHelperClient};

impl RootHelperClient {
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
}
