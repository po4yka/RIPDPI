use std::io;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{FlaggedTcpPayloadParams, CMD_SEND_FLAGGED_TCP_PAYLOAD};

use crate::TcpFlagOverrides;

use super::super::{command_params, RootHelperClient};

impl RootHelperClient {
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
}
