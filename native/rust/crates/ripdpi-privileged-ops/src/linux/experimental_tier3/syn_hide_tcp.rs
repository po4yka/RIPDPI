use std::io;
use std::net::SocketAddr;

use etherparse::{Ipv4Header, Ipv6Header, TcpHeader, TcpHeaderSlice};

use crate::experimental_tier3::{SynHideMarkerKind, SynHideTcpSpec};

use super::raw_socket::send_ip_packet;

pub fn send_syn_hide_tcp(spec: &SynHideTcpSpec, protect_path: Option<&str>) -> io::Result<()> {
    let packet = build_syn_hide_tcp_packet(spec)?;
    send_ip_packet(spec.target, &packet, protect_path)
}

fn build_syn_hide_tcp_packet(spec: &SynHideTcpSpec) -> io::Result<Vec<u8>> {
    let mut tcp = TcpHeader::new(spec.source.port(), spec.target.port(), spec.sequence_number, spec.window_size);
    tcp.syn = false;
    tcp.ack = false;
    tcp.psh = false;
    tcp.urg = matches!(spec.marker_kind, SynHideMarkerKind::UrgentPtr);
    tcp.urgent_pointer =
        if matches!(spec.marker_kind, SynHideMarkerKind::UrgentPtr) { spec.marker_value as u16 } else { 0 };

    let mut options = Vec::new();
    if matches!(spec.marker_kind, SynHideMarkerKind::TimestampEcho) {
        options.extend_from_slice(&[1, 1, 8, 10]);
        options.extend_from_slice(&spec.sequence_number.to_be_bytes());
        options.extend_from_slice(&spec.marker_value.to_be_bytes());
        while !options.len().is_multiple_of(4) {
            options.push(0);
        }
        tcp.set_options_raw(&options)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide TCP options exceed supported size"))?;
    }

    match (spec.source, spec.target) {
        (SocketAddr::V4(source), SocketAddr::V4(target)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(source.ip().octets(), target.ip().octets(), &[])
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide IPv4 checksum overflow"))?;
            let payload_length = u16::try_from(tcp.header_len())
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide IPv4 payload too large"))?;
            let mut ip = Ipv4Header::new(
                payload_length,
                spec.ttl.max(1),
                etherparse::IpNumber::TCP,
                source.ip().octets(),
                target.ip().octets(),
            )
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid SYN-Hide IPv4 packet"))?;
            ip.identification =
                spec.ipv4_identification.unwrap_or(((spec.sequence_number ^ spec.marker_value) & 0xFFFF) as u16);
            ip.header_checksum = ip.calc_header_checksum();

            let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + tcp.header_len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            apply_syn_hide_marker(&mut bytes, spec.source, spec.target, spec.marker_kind)?;
            Ok(bytes)
        }
        (SocketAddr::V6(source), SocketAddr::V6(target)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(source.ip().octets(), target.ip().octets(), &[])
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide IPv6 checksum overflow"))?;
            let payload_length = u16::try_from(tcp.header_len())
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide IPv6 payload too large"))?;
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: etherparse::Ipv6FlowLabel::ZERO,
                payload_length,
                next_header: etherparse::IpNumber::TCP,
                hop_limit: spec.ttl.max(1),
                source: source.ip().octets(),
                destination: target.ip().octets(),
            };

            let mut bytes = Vec::with_capacity(Ipv6Header::LEN + tcp.header_len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            apply_syn_hide_marker(&mut bytes, spec.source, spec.target, spec.marker_kind)?;
            Ok(bytes)
        }
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "SYN-Hide requires matching source and destination IP families",
        )),
    }
}

fn apply_syn_hide_marker(
    packet: &mut [u8],
    source: SocketAddr,
    target: SocketAddr,
    marker_kind: SynHideMarkerKind,
) -> io::Result<()> {
    let tcp_offset = match source {
        SocketAddr::V4(_) => Ipv4Header::MIN_LEN,
        SocketAddr::V6(_) => Ipv6Header::LEN,
    };
    if packet.len() < tcp_offset + 20 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide raw packet too short"));
    }

    if matches!(marker_kind, SynHideMarkerKind::ReservedX2) {
        packet[tcp_offset + 12] |= 0x04;
    }

    packet[tcp_offset + 16] = 0;
    packet[tcp_offset + 17] = 0;
    let header = TcpHeaderSlice::from_slice(&packet[tcp_offset..]).map_err(io::Error::other)?;
    let checksum = match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => header
            .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), &[])
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide IPv4 checksum overflow"))?,
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => header
            .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), &[])
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SYN-Hide IPv6 checksum overflow"))?,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "SYN-Hide requires matching source and destination IP families",
            ));
        }
    };
    packet[tcp_offset + 16..tcp_offset + 18].copy_from_slice(&checksum.to_be_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use etherparse::{Ipv4Header, TcpHeaderSlice};

    use crate::experimental_tier3::{SynHideMarkerKind, SynHideTcpSpec};

    use super::build_syn_hide_tcp_packet;

    #[test]
    fn syn_hide_reserved_x2_packet_unsets_syn_and_sets_reserved_marker() {
        let spec = SynHideTcpSpec {
            source: "192.0.2.10:40000".parse().expect("source"),
            target: "198.51.100.20:443".parse().expect("target"),
            ttl: 61,
            sequence_number: 0x1122_3344,
            window_size: 4096,
            marker_kind: SynHideMarkerKind::ReservedX2,
            marker_value: 0xfeed_beef,
            ipv4_identification: Some(77),
        };

        let packet = build_syn_hide_tcp_packet(&spec).expect("build packet");
        let tcp = TcpHeaderSlice::from_slice(&packet[Ipv4Header::MIN_LEN..]).expect("tcp");
        assert!(!tcp.syn());
        assert!(!tcp.ack());
        assert_eq!(packet[Ipv4Header::MIN_LEN + 12] & 0x0f, 0x04);
    }
}
