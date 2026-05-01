use std::net::SocketAddr;

pub(crate) fn build_udp_response(src: SocketAddr, dst: SocketAddr, payload: &[u8]) -> Vec<u8> {
    match (src, dst) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            let Ok(udp_total) = u16::try_from(etherparse::UdpHeader::LEN + payload.len()) else {
                return Vec::new();
            };
            let Ok(ip) = etherparse::Ipv4Header::new(
                udp_total,
                64,
                etherparse::IpNumber::UDP,
                src.ip().octets(),
                dst.ip().octets(),
            ) else {
                return Vec::new();
            };
            let Ok(udp) = etherparse::UdpHeader::with_ipv4_checksum(src.port(), dst.port(), &ip, payload) else {
                return Vec::new();
            };
            let mut buf =
                Vec::with_capacity(etherparse::Ipv4Header::MIN_LEN + etherparse::UdpHeader::LEN + payload.len());
            let _ = ip.write(&mut buf);
            let _ = udp.write(&mut buf);
            buf.extend_from_slice(payload);
            buf
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            let udp_total = etherparse::UdpHeader::LEN + payload.len();
            let Ok(payload_length) = u16::try_from(udp_total) else {
                return Vec::new();
            };
            let ip = etherparse::Ipv6Header {
                traffic_class: 0,
                flow_label: etherparse::Ipv6FlowLabel::ZERO,
                payload_length,
                next_header: etherparse::IpNumber::UDP,
                hop_limit: 64,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };
            let Ok(udp) = etherparse::UdpHeader::with_ipv6_checksum(src.port(), dst.port(), &ip, payload) else {
                return Vec::new();
            };
            let mut buf = Vec::with_capacity(etherparse::Ipv6Header::LEN + etherparse::UdpHeader::LEN + payload.len());
            let _ = ip.write(&mut buf);
            let _ = udp.write(&mut buf);
            buf.extend_from_slice(payload);
            buf
        }
        _ => Vec::new(),
    }
}
