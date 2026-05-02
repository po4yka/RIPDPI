use std::io;
use std::net::SocketAddr;
use std::os::fd::RawFd;

use ripdpi_root_helper_protocol::{
    IpFragTcpParams, IpFragUdpParams, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP,
};

use super::{command_params, RootHelperClient};
use crate::TcpFlagOverrides;

impl RootHelperClient {
    /// Send IP-fragmented TCP via the helper. Returns replacement fd.
    pub fn send_ip_fragmented_tcp(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<RawFd>> {
        let params = command_params(IpFragTcpParams {
            payload: payload.to_vec(),
            split_offset,
            default_ttl,
            disorder,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, fd) = self.transport.send_command(CMD_SEND_IP_FRAGMENTED_TCP, params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send IP-fragmented UDP via the helper.
    pub fn send_ip_fragmented_udp(
        &self,
        socket_fd: RawFd,
        target: SocketAddr,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
        ipv4_identification: Option<u16>,
    ) -> io::Result<()> {
        let params = command_params(IpFragUdpParams {
            target_addr: target.to_string(),
            payload: payload.to_vec(),
            split_offset,
            default_ttl,
            disorder,
            ipv4_identification,
        })?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_IP_FRAGMENTED_UDP, params, Some(socket_fd))?;
        Ok(())
    }
}
