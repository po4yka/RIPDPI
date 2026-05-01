use std::net::{IpAddr, SocketAddr};

use etherparse::{NetSlice, SlicedPacket, TransportSlice};

/// Parse a raw IP packet and extract the network and TCP slices.
pub(crate) fn parse_tcp_slices(pkt: &[u8]) -> Option<(NetSlice<'_>, etherparse::TcpSlice<'_>)> {
    let parsed = SlicedPacket::from_ip(pkt).ok()?;
    let net = parsed.net?;
    match parsed.transport? {
        TransportSlice::Tcp(tcp) => Some((net, tcp)),
        _ => None,
    }
}

pub(crate) fn tcp_packet_endpoints(pkt: &[u8]) -> Option<(SocketAddr, SocketAddr)> {
    let (net, tcp) = parse_tcp_slices(pkt)?;
    let (src_ip, dst_ip): (IpAddr, IpAddr) = match &net {
        NetSlice::Ipv4(v4) => (v4.header().source_addr().into(), v4.header().destination_addr().into()),
        NetSlice::Ipv6(v6) => (v6.header().source_addr().into(), v6.header().destination_addr().into()),
        NetSlice::Arp(_) => return None,
    };
    Some((SocketAddr::new(src_ip, tcp.source_port()), SocketAddr::new(dst_ip, tcp.destination_port())))
}

#[cfg(test)]
pub(crate) fn tcp_dst_port(pkt: &[u8]) -> Option<u16> {
    Some(tcp_packet_endpoints(pkt)?.1.port())
}
