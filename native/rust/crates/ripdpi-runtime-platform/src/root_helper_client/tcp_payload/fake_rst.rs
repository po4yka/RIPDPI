use std::io;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{CMD_SEND_FAKE_RST, FakeRstParams};

use crate::TcpFlagOverrides;

use super::super::{RootHelperClient, command_params};

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
}
