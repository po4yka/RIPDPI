use std::net::{IpAddr, Ipv4Addr, SocketAddr};

/// Classification of a raw IP packet before it reaches smoltcp.
///
/// Decision B: UDP packets are intercepted at the device layer.
/// smoltcp only ever receives `TcpOrOther` packets.
#[derive(Debug)]
pub enum IpClass {
    /// TCP or any non-UDP protocol — push to `device.rx_queue` for smoltcp.
    TcpOrOther,
    /// UDP with dst matching the mapdns network:port — route to DnsCache.
    UdpDns { src: SocketAddr, payload: Vec<u8> },
    /// UDP not destined for mapdns — spawn a UdpSession.
    Udp {
        src: SocketAddr,
        dst: SocketAddr,
        payload: Vec<u8>,
    },
}

/// Classify a raw IPv4 packet.
///
/// `mapdns_net`  — network address of the mapped-DNS range (e.g. `0xC612_0000` for 198.18.0.0).
/// `mapdns_mask` — network mask (e.g. `0xFFFE_0000` for /15).
/// `mapdns_port` — DNS intercept port (typically 53).
///
/// Returns `IpClass::TcpOrOther` for malformed packets so smoltcp can discard them.
pub fn classify_ip_packet(
    pkt: &[u8],
    mapdns_net: u32,
    mapdns_mask: u32,
    mapdns_port: u16,
) -> IpClass {
    // Minimum IPv4 header is 20 bytes.
    if pkt.len() < 20 {
        return IpClass::TcpOrOther;
    }

    let version = pkt[0] >> 4;
    if version != 4 {
        // Pass IPv6 through smoltcp (it handles it or discards).
        return IpClass::TcpOrOther;
    }

    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if pkt.len() < ihl {
        return IpClass::TcpOrOther;
    }

    let protocol = pkt[9];

    // Only intercept UDP (protocol 17).
    if protocol != 17 {
        return IpClass::TcpOrOther;
    }

    // Minimum: IP header + UDP header (8 bytes).
    if pkt.len() < ihl + 8 {
        return IpClass::TcpOrOther;
    }

    let src_ip = u32::from_be_bytes([pkt[12], pkt[13], pkt[14], pkt[15]]);
    let dst_ip = u32::from_be_bytes([pkt[16], pkt[17], pkt[18], pkt[19]]);

    let src_port = u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    let udp_length = u16::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5]]) as usize;

    // UDP length includes the 8-byte header; payload follows.
    let payload_start = ihl + 8;
    let payload_end = (ihl + udp_length).min(pkt.len());
    let payload = if payload_end > payload_start {
        pkt[payload_start..payload_end].to_vec()
    } else {
        Vec::new()
    };

    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(src_ip)), src_port);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(dst_ip)), dst_port);

    if dst_ip & mapdns_mask == mapdns_net && dst_port == mapdns_port {
        IpClass::UdpDns { src, payload }
    } else {
        IpClass::Udp { src, dst, payload }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Mapdns network: 198.18.0.0/15 (0xC612_0000 / 0xFFFE_0000), port 53.
    const MAPDNS_NET: u32 = 0xC612_0000; // 198.18.0.0
    const MAPDNS_MASK: u32 = 0xFFFE_0000; // /15
    const MAPDNS_PORT: u16 = 53;

    fn ipv4_udp(
        src_ip: [u8; 4],
        dst_ip: [u8; 4],
        src_port: u16,
        dst_port: u16,
        payload: &[u8],
    ) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let total_len = 20 + udp_len;
        let mut pkt = vec![0u8; total_len];
        // IPv4 header
        pkt[0] = 0x45;
        pkt[2] = (total_len >> 8) as u8;
        pkt[3] = total_len as u8;
        pkt[8] = 64; // TTL
        pkt[9] = 17; // UDP
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
        // UDP header at offset 20
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
        pkt[28..28 + payload.len()].copy_from_slice(payload);
        pkt
    }

    fn ipv4_tcp(src_ip: [u8; 4], dst_ip: [u8; 4], src_port: u16, dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[2] = 0;
        pkt[3] = 40;
        pkt[8] = 64;
        pkt[9] = 6; // TCP
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[32] = 0x50; // data offset
        pkt[33] = 0x02; // SYN
        pkt[34] = 0xff;
        pkt[35] = 0xff; // window
        pkt
    }

    /// U-03a: UDP dst=mapdns:53 → IpClass::UdpDns
    #[test]
    fn u03a_udp_to_mapdns_is_dns() {
        // 198.18.0.0:53 is in mapdns network
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 54321, 53, b"DNS query");
        let class = classify_ip_packet(&pkt, MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT);
        assert!(
            matches!(class, IpClass::UdpDns { .. }),
            "UDP to mapdns:53 must be IpClass::UdpDns"
        );
        if let IpClass::UdpDns { src, payload } = class {
            assert_eq!(src.port(), 54321);
            assert_eq!(payload, b"DNS query");
        }
    }

    /// U-03b: UDP dst=8.8.8.8:53 (not in mapdns network) → IpClass::Udp
    #[test]
    fn u03b_udp_to_external_dns_is_udp() {
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, b"query");
        let class = classify_ip_packet(&pkt, MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT);
        assert!(
            matches!(class, IpClass::Udp { .. }),
            "UDP to non-mapdns:53 must be IpClass::Udp"
        );
        if let IpClass::Udp { src, dst, payload } = class {
            assert_eq!(src, "10.0.0.1:12345".parse().unwrap());
            assert_eq!(dst, "8.8.8.8:53".parse().unwrap());
            assert_eq!(payload, b"query");
        }
    }

    /// U-03c: TCP → IpClass::TcpOrOther
    #[test]
    fn u03c_tcp_is_tcp_or_other() {
        let pkt = ipv4_tcp([10, 0, 0, 1], [1, 1, 1, 1], 12345, 80);
        let class = classify_ip_packet(&pkt, MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT);
        assert!(
            matches!(class, IpClass::TcpOrOther),
            "TCP packet must be IpClass::TcpOrOther"
        );
    }

    /// U-03d: UDP in mapdns network but wrong port → IpClass::Udp
    #[test]
    fn u03d_udp_mapdns_network_wrong_port_is_udp() {
        // 198.18.0.0:80 — right network, wrong port
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 12345, 80, b"data");
        let class = classify_ip_packet(&pkt, MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT);
        assert!(
            matches!(class, IpClass::Udp { .. }),
            "UDP to mapdns network but wrong port must be IpClass::Udp"
        );
    }

    /// Malformed packets (too short) pass through as TcpOrOther.
    #[test]
    fn malformed_short_packet_is_tcp_or_other() {
        let pkt = vec![0u8; 5];
        let class = classify_ip_packet(&pkt, MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT);
        assert!(matches!(class, IpClass::TcpOrOther));
    }
}
