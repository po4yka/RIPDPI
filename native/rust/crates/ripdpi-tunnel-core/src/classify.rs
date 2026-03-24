use std::net::{IpAddr, SocketAddr};

use etherparse::{NetSlice, SlicedPacket, TransportSlice};

#[derive(Debug)]
pub enum IpClass<'a> {
    TcpOrOther,
    UdpDns { src: SocketAddr, payload: &'a [u8] },
    Udp { src: SocketAddr, dst: SocketAddr, payload: &'a [u8] },
}

/// Classify a raw IPv4 or IPv6 packet.
///
/// When `mapdns` is `None`, DNS interception is disabled and all UDP packets
/// are returned as `IpClass::Udp`.
pub fn classify_ip_packet<'a>(pkt: &'a [u8], mapdns: Option<(u32, u32, u16)>) -> IpClass<'a> {
    let Ok(parsed) = SlicedPacket::from_ip(pkt) else {
        return IpClass::TcpOrOther;
    };

    let Some(TransportSlice::Udp(udp)) = parsed.transport else {
        return IpClass::TcpOrOther;
    };

    let Some(net) = parsed.net else {
        return IpClass::TcpOrOther;
    };

    let src_port = udp.source_port();
    let dst_port = udp.destination_port();
    let payload = udp.payload();

    match net {
        NetSlice::Ipv4(ipv4) => {
            let src_ip = ipv4.header().source_addr();
            let dst_ip = ipv4.header().destination_addr();
            let src = SocketAddr::new(IpAddr::V4(src_ip), src_port);
            let dst = SocketAddr::new(IpAddr::V4(dst_ip), dst_port);

            if let Some((mapdns_net, mapdns_mask, mapdns_port)) = mapdns {
                let dst_ip_u32 = u32::from(dst_ip);
                if dst_ip_u32 & mapdns_mask == mapdns_net && dst_port == mapdns_port {
                    return IpClass::UdpDns { src, payload };
                }
            }

            IpClass::Udp { src, dst, payload }
        }
        NetSlice::Ipv6(ipv6) => {
            let src_ip = ipv6.header().source_addr();
            let dst_ip = ipv6.header().destination_addr();

            IpClass::Udp {
                src: SocketAddr::new(IpAddr::V6(src_ip), src_port),
                dst: SocketAddr::new(IpAddr::V6(dst_ip), dst_port),
                payload,
            }
        }
        NetSlice::Arp(_) => IpClass::TcpOrOther,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv6Addr;

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

    fn ipv6_udp(src_ip: Ipv6Addr, dst_ip: Ipv6Addr, src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        let udp_len = 8 + payload.len();
        let payload_len = udp_len as u16;
        let mut pkt = vec![0u8; 40 + udp_len];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&payload_len.to_be_bytes());
        pkt[6] = 17;
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&src_ip.octets());
        pkt[24..40].copy_from_slice(&dst_ip.octets());
        pkt[40..42].copy_from_slice(&src_port.to_be_bytes());
        pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
        pkt[44..46].copy_from_slice(&payload_len.to_be_bytes());
        pkt[48..48 + payload.len()].copy_from_slice(payload);
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

    #[test]
    fn ipv6_udp_is_classified_as_udp() {
        let pkt = ipv6_udp(Ipv6Addr::LOCALHOST, Ipv6Addr::LOCALHOST, 5353, 443, b"quic");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn ipv4_udp_extracts_correct_addresses_and_ports() {
        let pkt = ipv4_udp([192, 168, 1, 100], [10, 0, 0, 1], 54321, 8080, b"data");

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { src, dst, .. } => {
                assert_eq!(src.ip(), IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 1, 100)));
                assert_eq!(src.port(), 54321);
                assert_eq!(dst.ip(), IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 1)));
                assert_eq!(dst.port(), 8080);
            }
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn ipv6_udp_extracts_correct_addresses_and_ports() {
        let src_ip = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);
        let dst_ip = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 2);
        let pkt = ipv6_udp(src_ip, dst_ip, 9000, 443, b"quic");

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { src, dst, .. } => {
                assert_eq!(src.ip(), IpAddr::V6(src_ip));
                assert_eq!(src.port(), 9000);
                assert_eq!(dst.ip(), IpAddr::V6(dst_ip));
                assert_eq!(dst.port(), 443);
            }
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn udp_payload_is_preserved_zero_copy() {
        let payload = b"hello world dns query";
        let pkt = ipv4_udp([10, 0, 0, 1], [8, 8, 8, 8], 12345, 53, payload);

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { payload: p, .. } => assert_eq!(p, payload),
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn dns_intercept_payload_is_preserved() {
        let payload = b"\x00\x01dns query data";
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 54321, 53, payload);

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        match class {
            IpClass::UdpDns { payload: p, .. } => assert_eq!(p, payload),
            other => panic!("expected IpClass::UdpDns, got {other:?}"),
        }
    }

    #[test]
    fn empty_payload_udp_is_classified() {
        let pkt = ipv4_udp([10, 0, 0, 1], [10, 0, 0, 2], 1234, 5678, b"");

        let class = classify_ip_packet(&pkt, None);

        match class {
            IpClass::Udp { payload, .. } => assert!(payload.is_empty()),
            other => panic!("expected IpClass::Udp, got {other:?}"),
        }
    }

    #[test]
    fn empty_input_is_tcp_or_other() {
        assert!(matches!(classify_ip_packet(&[], None), IpClass::TcpOrOther));
    }

    #[test]
    fn ipv6_tcp_is_tcp_or_other() {
        // IPv6 TCP packet: version=6, next_header=6 (TCP)
        let mut pkt = vec![0u8; 60];
        pkt[0] = 0x60;
        pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
        pkt[6] = 6; // TCP
        pkt[7] = 64;
        pkt[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
        pkt[52] = 0x50;
        pkt[53] = 0x02;

        assert!(matches!(classify_ip_packet(&pkt, None), IpClass::TcpOrOther));
    }

    #[test]
    fn icmp_packet_is_tcp_or_other() {
        let mut pkt = vec![0u8; 28];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 1; // ICMP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);

        assert!(matches!(classify_ip_packet(&pkt, None), IpClass::TcpOrOther));
    }

    #[test]
    fn wrong_netmask_does_not_trigger_dns_intercept() {
        // 172.16.0.1 is outside 198.18.0.0/15 (MAPDNS_NET/MAPDNS_MASK)
        let pkt = ipv4_udp([10, 0, 0, 1], [172, 16, 0, 1], 12345, 53, b"query");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn dns_intercept_requires_both_mask_and_port() {
        // Correct netmask range but wrong port
        let pkt = ipv4_udp([10, 0, 0, 1], [198, 18, 0, 0], 12345, 5353, b"query");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        assert!(matches!(class, IpClass::Udp { .. }));
    }

    #[test]
    fn dns_intercept_src_address_is_correct() {
        let pkt = ipv4_udp([10, 0, 0, 99], [198, 18, 0, 0], 54321, 53, b"q");

        let class = classify_ip_packet(&pkt, Some((MAPDNS_NET, MAPDNS_MASK, MAPDNS_PORT)));

        match class {
            IpClass::UdpDns { src, .. } => {
                assert_eq!(src.ip(), IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 99)));
                assert_eq!(src.port(), 54321);
            }
            other => panic!("expected IpClass::UdpDns, got {other:?}"),
        }
    }
}
