use std::io;
use std::os::fd::{BorrowedFd, OwnedFd};

use ripdpi_root_helper_protocol::{CMD_SEND_FAKE_TCP, FakeTcpParams};

use crate::{FakeTcpOptions, TcpStageWait};

use super::super::{RootHelperClient, command_params};

impl RootHelperClient {
    #[allow(clippy::too_many_arguments)]
    pub fn send_fake_tcp(
        &self,
        stream_fd: BorrowedFd<'_>,
        original_prefix: &[u8],
        fake_prefix: &[u8],
        ttl: u8,
        md5sig: bool,
        default_ttl: u8,
        options: &FakeTcpOptions<'_>,
        wait: TcpStageWait,
    ) -> io::Result<Option<OwnedFd>> {
        let params = command_params(FakeTcpParams {
            original_prefix: original_prefix.to_vec(),
            fake_prefix: fake_prefix.to_vec(),
            ttl,
            default_ttl,
            md5sig,
            secondary_fake_prefix: options.secondary_fake_prefix.map(Vec::from),
            timestamp_delta_ticks: options.timestamp_delta_ticks,
            tcp_flags_set: options.fake_flags.set,
            tcp_flags_unset: options.fake_flags.unset,
            tcp_flags_orig_set: options.orig_flags.set,
            tcp_flags_orig_unset: options.orig_flags.unset,
            require_raw_path: options.require_raw_path,
            force_raw_original: options.force_raw_original,
            ipv4_identifications: options.ipv4_identifications.clone(),
            wait_enabled: wait.0,
            wait_poll_ms: wait.1.as_millis() as u64,
        })?;
        let (_resp, fd) = self.transport.send_command(CMD_SEND_FAKE_TCP, params, Some(stream_fd))?;
        Ok(fd)
    }
}
