use std::net::{IpAddr, Ipv4Addr, SocketAddr};

#[derive(Debug)]
pub enum IpClass {
    TcpOrOther,
    UdpDns { src: SocketAddr, payload: Vec<u8> },
    Udp { src: SocketAddr, dst: SocketAddr, payload: Vec<u8> },
}

/// Classify a raw IPv4 packet.
///
/// When `mapdns` is `None`, DNS interception is disabled and all UDP packets
/// are returned as `IpClass::Udp`.
pub fn classify_ip_packet(pkt: &[u8], mapdns: Option<(u32, u32, u16)>) -> IpClass {
    if pkt.len() < 20 {
        return IpClass::TcpOrOther;
    }

    let version = pkt[0] >> 4;
    if version != 4 {
        return IpClass::TcpOrOther;
    }

    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if pkt.len() < ihl {
        return IpClass::TcpOrOther;
    }

    if pkt[9] != 17 {
        return IpClass::TcpOrOther;
    }

    if pkt.len() < ihl + 8 {
        return IpClass::TcpOrOther;
    }

    let src_ip = u32::from_be_bytes([pkt[12], pkt[13], pkt[14], pkt[15]]);
    let dst_ip = u32::from_be_bytes([pkt[16], pkt[17], pkt[18], pkt[19]]);
    let src_port = u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    let udp_length = u16::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5]]) as usize;

    let payload_start = ihl + 8;
    let payload_end = (ihl + udp_length).min(pkt.len());
    let payload = if payload_end > payload_start { pkt[payload_start..payload_end].to_vec() } else { Vec::new() };

    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(src_ip)), src_port);
    let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::from(dst_ip)), dst_port);

    if let Some((mapdns_net, mapdns_mask, mapdns_port)) = mapdns {
        if dst_ip & mapdns_mask == mapdns_net && dst_port == mapdns_port {
            return IpClass::UdpDns { src, payload };
        }
    }

    IpClass::Udp { src, dst, payload }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MAPDNS_NET: u32 = 0xC612_0000;
    const MAPDNS_MASK: u32 = 0xFFFE_0000;
    const MAPDNS_PORT: u16 = 53;

    fn ipv4_udp(src_ip: [u8; 4], dst_ip: [u8; 4], src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let total_len = 20 + udp_len;
        let mut pkt = vec![0u8; total_len];
        pkt[0] = 0x45;
        pkt[2] = (total_len >> 8) as u8;
        pkt[3] = total_len as u8;
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
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
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x02;
        pkt[34] = 0xff;
        pkt[35] = 0xff;
        pkt
    }

    #[test]
    fn udp_to_mapdns_is_dns_when_enabled() {
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 54321, 53, b"query");
        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));
        assert!(matches!(class, IpClass::UdpDns { .. }));
    }

    #[test]
    fn udp_to_dns_is_plain_udp_when_intercept_disabled() {
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, b"query");
        let class = classify_ip_packet(&pkt, None);
        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn tcp_is_tcp_or_other() {
        let pkt = ipv4_tcp([10, 0, 0, 1], [1, 1, 1, 1], 12345, 80);
        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));
        assert!(matches!(class, IpClass::TcpOrOther));
    }

    #[test]
    fn wrong_port_does_not_trigger_dns_intercept() {
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 12345, 80, b"data");
        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));
        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn malformed_packet_is_tcp_or_other() {
        assert!(matches!(classify_ip_packet(&[0u8; 5], None), IpClass::TcpOrOther));
    }
}
