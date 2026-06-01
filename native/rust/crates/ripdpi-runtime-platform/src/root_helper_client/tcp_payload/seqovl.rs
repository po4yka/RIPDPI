use std::io;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{CMD_SEND_SEQOVL_TCP, SeqOvlParams};

use crate::TcpFlagOverrides;

use super::super::{RootHelperClient, command_params};

impl RootHelperClient {
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
}
