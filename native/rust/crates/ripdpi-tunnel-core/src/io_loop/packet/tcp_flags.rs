use std::net::SocketAddr;

use etherparse::{NetSlice, SlicedPacket, TransportSlice};

use super::parse::{parse_tcp_slices, tcp_packet_endpoints};

pub(crate) fn is_tcp_syn(pkt: &[u8]) -> bool {
    let Some((_, tcp)) = parse_tcp_slices(pkt) else {
        return false;
    };
    tcp.syn() && !tcp.ack()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct TcpFlowKey {
    pub(crate) src: SocketAddr,
    pub(crate) dst: SocketAddr,
}

pub(crate) fn tcp_syn_flow_key(pkt: &[u8]) -> Option<TcpFlowKey> {
    if !is_tcp_syn(pkt) {
        return None;
    }
    let (src, dst) = tcp_packet_endpoints(pkt)?;
    Some(TcpFlowKey { src, dst })
}

pub(crate) fn is_injected_rst(pkt: &[u8]) -> bool {
    let Ok(parsed) = SlicedPacket::from_ip(pkt) else {
        return false;
    };
    let Some(NetSlice::Ipv4(ipv4)) = parsed.net else {
        return false;
    };
    let Some(TransportSlice::Tcp(tcp)) = parsed.transport else {
        return false;
    };
    tcp.rst() && ipv4.header().identification() <= 1
}
