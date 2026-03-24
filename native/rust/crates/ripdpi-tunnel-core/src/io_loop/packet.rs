use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use smoltcp::wire::IpAddress;

pub(super) fn ipv4_transport_offset(pkt: &[u8], protocol: u8) -> Option<usize> {
    if pkt.len() < 20 || pkt[0] >> 4 != 4 || pkt[9] != protocol {
        return None;
    }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if ihl < 20 || pkt.len() < ihl {
        return None;
    }
    Some(ihl)
}

pub(super) fn ipv6_transport_offset(pkt: &[u8], next_header: u8) -> Option<usize> {
    if pkt.len() < 40 || pkt[0] >> 4 != 6 || pkt[6] != next_header {
        return None;
    }
    Some(40)
}

pub(super) fn tcp_header_offset(pkt: &[u8]) -> Option<usize> {
    match pkt.first().map(|value| value >> 4) {
        Some(4) => {
            let offset = ipv4_transport_offset(pkt, 6)?;
            (pkt.len() >= offset + 14).then_some(offset)
        }
        Some(6) => {
            let offset = ipv6_transport_offset(pkt, 6)?;
            (pkt.len() >= offset + 14).then_some(offset)
        }
        _ => None,
    }
}

fn tcp_packet_endpoints(pkt: &[u8]) -> Option<(SocketAddr, SocketAddr)> {
    match pkt.first().map(|value| value >> 4) {
        Some(4) => {
            let offset = ipv4_transport_offset(pkt, 6)?;
            if pkt.len() < offset + 4 {
                return None;
            }
            let src_ip = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
            let dst_ip = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
            let src_port = u16::from_be_bytes([pkt[offset], pkt[offset + 1]]);
            let dst_port = u16::from_be_bytes([pkt[offset + 2], pkt[offset + 3]]);
            Some((SocketAddr::new(IpAddr::V4(src_ip), src_port), SocketAddr::new(IpAddr::V4(dst_ip), dst_port)))
        }
        Some(6) => {
            let offset = ipv6_transport_offset(pkt, 6)?;
            if pkt.len() < offset + 4 {
                return None;
            }
            let mut src_ip = [0u8; 16];
            src_ip.copy_from_slice(&pkt[8..24]);
            let mut dst_ip = [0u8; 16];
            dst_ip.copy_from_slice(&pkt[24..40]);
            let src_port = u16::from_be_bytes([pkt[offset], pkt[offset + 1]]);
            let dst_port = u16::from_be_bytes([pkt[offset + 2], pkt[offset + 3]]);
            Some((
                SocketAddr::new(IpAddr::V6(src_ip.into()), src_port),
                SocketAddr::new(IpAddr::V6(dst_ip.into()), dst_port),
            ))
        }
        _ => None,
    }
}

#[cfg(test)]
pub(super) fn tcp_dst_port(pkt: &[u8]) -> Option<u16> {
    Some(tcp_packet_endpoints(pkt)?.1.port())
}

pub(super) fn is_tcp_syn(pkt: &[u8]) -> bool {
    let Some(offset) = tcp_header_offset(pkt) else {
        return false;
    };
    pkt[offset + 13] & 0x12 == 0x02
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(super) struct TcpFlowKey {
    pub(super) src: SocketAddr,
    pub(super) dst: SocketAddr,
}

pub(super) fn tcp_syn_flow_key(pkt: &[u8]) -> Option<TcpFlowKey> {
    if !is_tcp_syn(pkt) {
        return None;
    }
    let (src, dst) = tcp_packet_endpoints(pkt)?;
    Some(TcpFlowKey { src, dst })
}

pub(super) fn is_injected_rst(pkt: &[u8]) -> bool {
    let Some(ihl) = ipv4_transport_offset(pkt, 6) else {
        return false;
    };
    if pkt.len() < ihl + 14 {
        return false;
    }
    if pkt[ihl + 13] & 0x04 == 0 {
        return false;
    }
    let ip_id = u16::from_be_bytes([pkt[4], pkt[5]]);
    ip_id <= 1
}

pub(super) fn checksum_sum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(last) = chunks.remainder().first() {
        sum += u32::from(*last) << 8;
    }
    sum
}

pub(super) fn finalize_checksum(mut sum: u32) -> u16 {
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn normalize_udp_checksum(checksum: u16) -> u16 {
    if checksum == 0 {
        0xFFFF
    } else {
        checksum
    }
}

fn udp_checksum_ipv4(src_ip: [u8; 4], dst_ip: [u8; 4], udp_packet: &[u8]) -> u16 {
    let udp_len = u16::try_from(udp_packet.len()).unwrap_or(u16::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += u32::from(17u16);
    sum += u32::from(udp_len);
    sum += checksum_sum(udp_packet);
    normalize_udp_checksum(finalize_checksum(sum))
}

fn udp_checksum_ipv6(src_ip: [u8; 16], dst_ip: [u8; 16], udp_packet: &[u8]) -> u16 {
    let udp_len = u32::try_from(udp_packet.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (udp_len >> 16) + (udp_len & 0xFFFF);
    sum += u32::from(17u16);
    sum += checksum_sum(udp_packet);
    normalize_udp_checksum(finalize_checksum(sum))
}

#[cfg(test)]
fn icmpv6_checksum(src_ip: [u8; 16], dst_ip: [u8; 16], payload: &[u8]) -> u16 {
    let payload_len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (payload_len >> 16) + (payload_len & 0xFFFF);
    sum += u32::from(58u16);
    sum += checksum_sum(payload);
    finalize_checksum(sum)
}

pub(super) fn build_udp_response(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let Ok(udp_len) = u16::try_from(8usize + payload.len()) else {
        return Vec::new();
    };

    match (src, dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let Ok(total_len) = u16::try_from(20usize + usize::from(udp_len)) else {
                return Vec::new();
            };
            let mut pkt = vec![0u8; usize::from(total_len)];
            let src_ip = src.ip().octets();
            let dst_ip = dst.ip().octets();

            pkt[0] = 0x45;
            pkt[2..4].copy_from_slice(&total_len.to_be_bytes());
            pkt[8] = 64;
            pkt[9] = 17;
            pkt[12..16].copy_from_slice(&src_ip);
            pkt[16..20].copy_from_slice(&dst_ip);

            pkt[20..22].copy_from_slice(&src.port().to_be_bytes());
            pkt[22..24].copy_from_slice(&dst.port().to_be_bytes());
            pkt[24..26].copy_from_slice(&udp_len.to_be_bytes());
            pkt[28..28 + payload.len()].copy_from_slice(payload);

            let header_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
            pkt[10..12].copy_from_slice(&header_checksum.to_be_bytes());

            let udp_checksum = udp_checksum_ipv4(src_ip, dst_ip, &pkt[20..]);
            pkt[26..28].copy_from_slice(&udp_checksum.to_be_bytes());

            pkt
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let total_len = 40usize + usize::from(udp_len);
            let mut pkt = vec![0u8; total_len];
            let src_ip = src.ip().octets();
            let dst_ip = dst.ip().octets();

            pkt[0] = 0x60;
            pkt[4..6].copy_from_slice(&udp_len.to_be_bytes());
            pkt[6] = 17;
            pkt[7] = 64;
            pkt[8..24].copy_from_slice(&src_ip);
            pkt[24..40].copy_from_slice(&dst_ip);

            pkt[40..42].copy_from_slice(&src.port().to_be_bytes());
            pkt[42..44].copy_from_slice(&dst.port().to_be_bytes());
            pkt[44..46].copy_from_slice(&udp_len.to_be_bytes());
            pkt[48..48 + payload.len()].copy_from_slice(payload);

            let udp_checksum = udp_checksum_ipv6(src_ip, dst_ip, &pkt[40..]);
            pkt[46..48].copy_from_slice(&udp_checksum.to_be_bytes());

            pkt
        }
        _ => Vec::new(),
    }
}

#[cfg(test)]
pub(super) fn build_udp_port_unreachable(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    const QUOTED_UDP_PAYLOAD_LEN: usize = 8;

    let original = build_udp_response(src, dst, payload);
    if original.is_empty() {
        return Vec::new();
    }

    match (src, dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let quoted_len = original.len().min(20 + 8 + QUOTED_UDP_PAYLOAD_LEN);
            let icmp_len = 8usize + quoted_len;
            let total_len = 20usize + icmp_len;
            let Ok(total_len_u16) = u16::try_from(total_len) else {
                return Vec::new();
            };
            let mut pkt = vec![0u8; total_len];
            let outer_src = dst.ip().octets();
            let outer_dst = src.ip().octets();

            pkt[0] = 0x45;
            pkt[2..4].copy_from_slice(&total_len_u16.to_be_bytes());
            pkt[8] = 64;
            pkt[9] = 1;
            pkt[12..16].copy_from_slice(&outer_src);
            pkt[16..20].copy_from_slice(&outer_dst);

            pkt[20] = 3;
            pkt[21] = 3;
            pkt[28..28 + quoted_len].copy_from_slice(&original[..quoted_len]);

            let icmp_checksum = finalize_checksum(checksum_sum(&pkt[20..]));
            pkt[22..24].copy_from_slice(&icmp_checksum.to_be_bytes());

            let header_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
            pkt[10..12].copy_from_slice(&header_checksum.to_be_bytes());

            pkt
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let quoted_len = original.len().min(40 + 8 + QUOTED_UDP_PAYLOAD_LEN);
            let icmp_len = 8usize + quoted_len;
            let Ok(icmp_len_u16) = u16::try_from(icmp_len) else {
                return Vec::new();
            };
            let mut pkt = vec![0u8; 40 + icmp_len];
            let outer_src = dst.ip().octets();
            let outer_dst = src.ip().octets();

            pkt[0] = 0x60;
            pkt[4..6].copy_from_slice(&icmp_len_u16.to_be_bytes());
            pkt[6] = 58;
            pkt[7] = 64;
            pkt[8..24].copy_from_slice(&outer_src);
            pkt[24..40].copy_from_slice(&outer_dst);

            pkt[40] = 1;
            pkt[41] = 4;
            pkt[48..48 + quoted_len].copy_from_slice(&original[..quoted_len]);

            let icmp_checksum = icmpv6_checksum(outer_src, outer_dst, &pkt[40..]);
            pkt[42..44].copy_from_slice(&icmp_checksum.to_be_bytes());

            pkt
        }
        _ => Vec::new(),
    }
}

pub(super) fn endpoint_to_socketaddr(ep: smoltcp::wire::IpEndpoint) -> SocketAddr {
    let ip: IpAddr = match ep.addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(v4),
        IpAddress::Ipv6(v6) => IpAddr::V6(v6),
    };
    SocketAddr::new(ip, ep.port)
}

// ── Test helpers shared with tcp_accept tests ────────────────────────────────

#[cfg(test)]
pub(super) fn build_ipv4_tcp_syn_packet(src_ip: Ipv4Addr, dst_ip: Ipv4Addr, src_port: u16, dst_port: u16) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45;
    pkt[3] = 40;
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[32] = 0x50;
    pkt[33] = 0x02; // SYN
    let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
    pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
    let tcp_checksum = {
        let mut sum = checksum_sum(&src_ip.octets());
        sum += checksum_sum(&dst_ip.octets());
        sum += u32::from(6u16);
        sum += u32::from((pkt.len() - 20) as u16);
        sum += checksum_sum(&pkt[20..]);
        finalize_checksum(sum)
    };
    pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}

#[cfg(test)]
pub(super) fn build_ipv6_tcp_syn_packet(
    src_ip: std::net::Ipv6Addr,
    dst_ip: std::net::Ipv6Addr,
    src_port: u16,
    dst_port: u16,
) -> Vec<u8> {
    let mut pkt = vec![0u8; 60];
    pkt[0] = 0x60;
    pkt[4..6].copy_from_slice(&20u16.to_be_bytes());
    pkt[6] = 6;
    pkt[7] = 64;
    pkt[8..24].copy_from_slice(&src_ip.octets());
    pkt[24..40].copy_from_slice(&dst_ip.octets());
    pkt[40..42].copy_from_slice(&src_port.to_be_bytes());
    pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
    pkt[52] = 0x50;
    pkt[53] = 0x02;
    let tcp_len = u32::try_from(pkt.len() - 40).expect("tcp length");
    let mut sum = checksum_sum(&src_ip.octets());
    sum += checksum_sum(&dst_ip.octets());
    sum += (tcp_len >> 16) + (tcp_len & 0xFFFF);
    sum += u32::from(6u16);
    sum += checksum_sum(&pkt[40..]);
    let tcp_checksum = finalize_checksum(sum);
    pkt[56..58].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

    fn ipv4_tcp_rst(ip_id: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45; // IPv4, IHL=5
        pkt[3] = 40; // total length
        pkt[4] = (ip_id >> 8) as u8; // IP ID high
        pkt[5] = (ip_id & 0xFF) as u8; // IP ID low
        pkt[8] = 64; // TTL
        pkt[9] = 6; // TCP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]); // src IP
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]); // dst IP
        pkt[32] = 0x50; // TCP data offset = 5
        pkt[33] = 0x04; // RST flag
        pkt
    }

    fn ipv4_tcp_syn() -> Vec<u8> {
        ipv4_tcp_syn_with_ports(12345, 443)
    }

    fn ipv4_tcp_syn_with_ports(src_port: u16, dst_port: u16) -> Vec<u8> {
        build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 1), Ipv4Addr::new(10, 0, 0, 2), src_port, dst_port)
    }

    fn ipv4_tcp_ack(dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        pkt[20..22].copy_from_slice(&12345u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[32] = 0x50;
        pkt[33] = 0x10; // ACK
        pkt
    }

    fn ipv6_tcp_syn(dst_port: u16) -> Vec<u8> {
        ipv6_tcp_syn_with_ports(12345, dst_port)
    }

    fn ipv6_tcp_syn_with_ports(src_port: u16, dst_port: u16) -> Vec<u8> {
        build_ipv6_tcp_syn_packet(Ipv6Addr::LOCALHOST, Ipv6Addr::LOCALHOST, src_port, dst_port)
    }

    #[test]
    fn injected_rst_with_ip_id_zero_is_detected() {
        assert!(is_injected_rst(&ipv4_tcp_rst(0x0000)));
    }

    #[test]
    fn injected_rst_with_ip_id_one_is_detected() {
        assert!(is_injected_rst(&ipv4_tcp_rst(0x0001)));
    }

    #[test]
    fn real_rst_with_normal_ip_id_is_not_injected() {
        assert!(!is_injected_rst(&ipv4_tcp_rst(0x1234)));
    }

    #[test]
    fn tcp_syn_is_not_injected_rst() {
        assert!(!is_injected_rst(&ipv4_tcp_syn()));
    }

    #[test]
    fn short_packet_is_not_injected_rst() {
        assert!(!is_injected_rst(&[0x45, 0x00, 0x00]));
    }

    #[test]
    fn packet_with_zero_ihl_is_not_injected_rst() {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x40; // IPv4, IHL=0 (malformed)
        pkt[3] = 40;
        pkt[9] = 6; // TCP
                    // IP ID = 0x0000 (would look like injected without the guard)
        pkt[33] = 0x04; // RST flag (at byte 33, which is IHL+13 only if IHL=20)
        assert!(!is_injected_rst(&pkt), "malformed IHL=0 packet should not be detected as injected RST");
    }

    #[test]
    fn tcp_syn_detects_ipv6_packets() {
        assert!(is_tcp_syn(&ipv6_tcp_syn(443)));
    }

    #[test]
    fn ipv4_transport_helpers_reject_wrong_protocol_and_extract_ports() {
        let mut udp_packet = ipv4_tcp_syn();
        udp_packet[9] = 17;

        assert_eq!(ipv4_transport_offset(&ipv4_tcp_syn(), 6), Some(20));
        assert_eq!(ipv4_transport_offset(&udp_packet, 6), None);
        assert_eq!(tcp_dst_port(&ipv4_tcp_ack(8443)), Some(8443));
        assert!(!is_tcp_syn(&ipv4_tcp_ack(8443)));
    }

    #[test]
    fn tcp_dst_port_extracts_ipv6_destination_port() {
        assert_eq!(tcp_dst_port(&ipv6_tcp_syn(8443)), Some(8443));
    }

    #[test]
    fn tcp_syn_flow_key_extracts_ipv4_endpoints() {
        let key = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51000, 443)).expect("ipv4 syn flow key");

        assert_eq!(key.src, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1)), 51000));
        assert_eq!(key.dst, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 443));
    }

    #[test]
    fn tcp_syn_flow_key_distinguishes_parallel_https_flows() {
        let first = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51000, 443)).expect("first flow");
        let second = tcp_syn_flow_key(&ipv4_tcp_syn_with_ports(51001, 443)).expect("second flow");

        assert_ne!(first, second);
    }

    #[test]
    fn tcp_syn_flow_key_extracts_ipv6_endpoints() {
        let key = tcp_syn_flow_key(&ipv6_tcp_syn_with_ports(51000, 443)).expect("ipv6 syn flow key");

        assert_eq!(key.src, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 51000));
        assert_eq!(key.dst, SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443));
    }

    #[test]
    fn build_udp_response_supports_ipv4() {
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 53);
        let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 5353);
        let payload = b"dns";

        let pkt = build_udp_response(src, dst, payload);

        assert_eq!(pkt.len(), 20 + 8 + payload.len());
        assert_eq!(pkt[0] >> 4, 4);
        assert_eq!(pkt[9], 17);
        assert_eq!(u16::from_be_bytes([pkt[20], pkt[21]]), 53);
        assert_eq!(u16::from_be_bytes([pkt[22], pkt[23]]), 5353);
        assert_ne!(u16::from_be_bytes([pkt[26], pkt[27]]), 0);
        assert_eq!(&pkt[28..], payload);
    }

    #[test]
    fn build_udp_response_supports_ipv6() {
        let src = SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 53);
        let dst = SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 5353);
        let payload = b"dns";

        let pkt = build_udp_response(src, dst, payload);

        assert_eq!(pkt.len(), 40 + 8 + payload.len());
        assert_eq!(pkt[0] >> 4, 6);
        assert_eq!(pkt[6], 17);
        assert_eq!(u16::from_be_bytes([pkt[40], pkt[41]]), 53);
        assert_eq!(u16::from_be_bytes([pkt[42], pkt[43]]), 5353);
        assert_ne!(u16::from_be_bytes([pkt[46], pkt[47]]), 0);
        assert_eq!(&pkt[48..], payload);
    }

    #[test]
    fn build_udp_response_rejects_oversized_payloads() {
        let payload = vec![0u8; usize::from(u16::MAX)];
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 53);
        let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 5353);

        assert!(build_udp_response(src, dst, &payload).is_empty());
    }

    #[test]
    fn build_udp_port_unreachable_supports_ipv4() {
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);
        let dst = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(157, 240, 229, 174)), 443);
        let expected_src = match dst.ip() {
            IpAddr::V4(value) => value.octets(),
            IpAddr::V6(_) => panic!("expected ipv4"),
        };
        let expected_dst = match src.ip() {
            IpAddr::V4(value) => value.octets(),
            IpAddr::V6(_) => panic!("expected ipv4"),
        };

        let pkt = build_udp_port_unreachable(src, dst, b"quic");

        assert_eq!(pkt[0] >> 4, 4);
        assert_eq!(pkt[9], 1);
        assert_eq!(pkt[20], 3);
        assert_eq!(pkt[21], 3);
        assert_eq!(&pkt[12..16], &expected_src);
        assert_eq!(&pkt[16..20], &expected_dst);
        assert!(!pkt[28..].is_empty());
    }

    #[test]
    fn build_udp_port_unreachable_supports_ipv6() {
        let src = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 53000);
        let dst = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443);

        let pkt = build_udp_port_unreachable(src, dst, b"quic");

        assert_eq!(pkt[0] >> 4, 6);
        assert_eq!(pkt[6], 58);
        assert_eq!(pkt[40], 1);
        assert_eq!(pkt[41], 4);
        assert!(!pkt[48..].is_empty());
    }
}
