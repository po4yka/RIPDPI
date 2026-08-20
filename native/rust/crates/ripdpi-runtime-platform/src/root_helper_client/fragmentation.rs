use std::io;
use std::net::SocketAddr;
use std::os::fd::{BorrowedFd, OwnedFd};

use ripdpi_root_helper_protocol::{
    CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, IpFragTcpParams, IpFragUdpParams,
};

use super::{RootHelperClient, command_params};
use crate::TcpFlagOverrides;

impl RootHelperClient {
    /// Send IP-fragmented TCP via the helper. Returns replacement fd.
    pub fn send_ip_fragmented_tcp(
        &self,
        stream_fd: BorrowedFd<'_>,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<OwnedFd>> {
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
        socket_fd: BorrowedFd<'_>,
        target: SocketAddr,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
        ipv4_identification: Option<u16>,
    ) -> io::Result<()> {
        self.send_ip_fragmented_udp_with_ipv6_ext(
            socket_fd,
            target,
            payload,
            split_offset,
            default_ttl,
            disorder,
            ripdpi_ipfrag::Ipv6ExtHeaders::default(),
            ipv4_identification,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn send_ip_fragmented_udp_with_ipv6_ext(
        &self,
        socket_fd: BorrowedFd<'_>,
        target: SocketAddr,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        ipv4_identification: Option<u16>,
    ) -> io::Result<()> {
        let params = command_params(IpFragUdpParams {
            target_addr: target.to_string(),
            payload: payload.to_vec(),
            split_offset,
            default_ttl,
            disorder,
            ipv4_identification,
            ipv6_hop_by_hop: ipv6_ext.hop_by_hop,
            ipv6_dest_opt: ipv6_ext.dest_opt,
            ipv6_dest_opt_fragmentable: ipv6_ext.dest_opt_fragmentable,
            ipv6_routing: ipv6_ext.routing,
            ipv6_second_frag_next_override: ipv6_ext.second_frag_next_override,
        })?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_IP_FRAGMENTED_UDP, params, Some(socket_fd))?;
        Ok(())
    }
}
