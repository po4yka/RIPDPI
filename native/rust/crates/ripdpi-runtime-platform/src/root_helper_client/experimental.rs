use std::io;

use ripdpi_root_helper_protocol::{CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_SYN_HIDE_TCP};

use super::RootHelperClient;
use crate::experimental::{IcmpWrappedUdpRecvFilter, IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideTcpSpec};

impl RootHelperClient {
    pub fn send_syn_hide_tcp(&self, spec: SynHideTcpSpec) -> io::Result<()> {
        let params = serde_json::to_value(spec)
            .map_err(|error| io::Error::other(format!("serialize syn hide spec: {error}")))?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_SYN_HIDE_TCP, params, None)?;
        Ok(())
    }

    pub fn send_icmp_wrapped_udp(&self, spec: &IcmpWrappedUdpSpec) -> io::Result<()> {
        let params = serde_json::to_value(spec)
            .map_err(|error| io::Error::other(format!("serialize ICMP-wrapped UDP spec: {error}")))?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_ICMP_WRAPPED_UDP, params, None)?;
        Ok(())
    }

    pub fn recv_icmp_wrapped_udp(&self, filter: IcmpWrappedUdpRecvFilter) -> io::Result<ReceivedIcmpWrappedUdp> {
        let params = serde_json::to_value(filter)
            .map_err(|error| io::Error::other(format!("serialize ICMP-wrapped UDP filter: {error}")))?;
        let (resp, _fd) = self.transport.send_command(CMD_RECV_ICMP_WRAPPED_UDP, params, None)?;
        serde_json::from_value(resp.data).map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidData, format!("invalid ICMP-wrapped UDP reply: {error}"))
        })
    }
}
