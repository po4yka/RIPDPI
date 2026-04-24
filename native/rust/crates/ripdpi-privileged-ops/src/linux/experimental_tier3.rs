use std::io;
use std::mem::MaybeUninit;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use etherparse::{Ipv4Header, Ipv6Header, TcpHeader, TcpHeaderSlice};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use crate::experimental_tier3::{
    decode_icmp_wrapped_udp_envelope, encode_icmp_wrapped_udp_envelope, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole,
    IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind, SynHideTcpSpec,
};

use super::setsockopt_raw;

pub fn send_syn_hide_tcp(spec: &SynHideTcpSpec, protect_path: Option<&str>) -> io::Result<()> {
    let packet = build_syn_hide_tcp_packet(spec)?;
    send_ip_packet(spec.target, &packet, protect_path)
}

pub fn send_icmp_wrapped_udp(spec: &IcmpWrappedUdpSpec, protect_path: Option<&str>) -> io::Result<()> {
    let envelope = encode_icmp_wrapped_udp_envelope(spec)?;
    let packet = build_icmp_echo_packet(spec.peer, spec.icmp_code, spec.ttl, spec.session_id, spec.role, &envelope)?;
    send_icmp_packet(spec.peer.ip(), spec.ttl, &packet, protect_path)
}

pub fn recv_icmp_wrapped_udp(
    filter: IcmpWrappedUdpRecvFilter,
    _protect_path: Option<&str>,
) -> io::Result<ReceivedIcmpWrappedUdp> {
    let socket = open_icmp_recv_socket(filter.bind_ip)?;
    socket.set_read_timeout(Some(filter.timeout()))?;
    let deadline = Instant::now() + filter.timeout();
    let mut buf = [MaybeUninit::<u8>::uninit(); 8192];

    loop {
        let now = Instant::now();
        if now >= deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "timed out waiting for ICMP-wrapped UDP payload"));
        }
        socket.set_read_timeout(Some(deadline.saturating_duration_since(now).max(Duration::from_millis(1))))?;

        let (received, addr) = match socket.recv_from(&mut buf) {
            Ok(result) => result,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted
                ) =>
            {
                continue;
            }
            Err(error) => return Err(error),
        };

        let peer_ip = sock_addr_ip(&addr).unwrap_or(filter.bind_ip);
        // SAFETY: `recv_from` initialized the first `received` bytes.
        let packet = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<u8>(), received) };
        let Some((role, code, payload)) = extract_icmp_envelope(peer_ip, packet) else {
            continue;
        };
        if filter.expected_code.is_some_and(|expected| expected != code) {
            continue;
        }
        if filter.expected_role.is_some_and(|expected| expected != role) {
            continue;
        }

        let decoded = decode_icmp_wrapped_udp_envelope(peer_ip, code, payload)?;
        if filter.session_id.is_some_and(|expected| expected != decoded.session_id) {
            continue;
        }
        return Ok(decoded);
    }
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

fn build_icmp_echo_packet(
    peer: SocketAddr,
    icmp_code: u8,
    ttl: u8,
    session_id: u32,
    role: IcmpWrappedUdpRole,
    payload: &[u8],
) -> io::Result<Vec<u8>> {
    match peer {
        SocketAddr::V4(_) => {
            let icmp_type = match role {
                IcmpWrappedUdpRole::ClientRequest => 8u8,
                IcmpWrappedUdpRole::ServerReply => 0u8,
            };
            let mut packet = Vec::with_capacity(8 + payload.len());
            packet.push(icmp_type);
            packet.push(icmp_code);
            packet.extend_from_slice(&0u16.to_be_bytes());
            packet.extend_from_slice(&(session_id as u16).to_be_bytes());
            packet.extend_from_slice(&((session_id >> 16) as u16).to_be_bytes());
            packet.extend_from_slice(payload);
            let checksum = finalize_checksum(checksum_sum(&packet));
            packet[2..4].copy_from_slice(&checksum.to_be_bytes());
            let _ = ttl;
            Ok(packet)
        }
        SocketAddr::V6(destination) => {
            let icmp_type = match role {
                IcmpWrappedUdpRole::ClientRequest => 128u8,
                IcmpWrappedUdpRole::ServerReply => 129u8,
            };
            let mut packet = Vec::with_capacity(8 + payload.len());
            packet.push(icmp_type);
            packet.push(icmp_code);
            packet.extend_from_slice(&0u16.to_be_bytes());
            packet.extend_from_slice(&(session_id as u16).to_be_bytes());
            packet.extend_from_slice(&((session_id >> 16) as u16).to_be_bytes());
            packet.extend_from_slice(payload);
            let checksum = icmpv6_checksum([0; 16], destination.ip().octets(), &packet);
            packet[2..4].copy_from_slice(&checksum.to_be_bytes());
            let _ = ttl;
            Ok(packet)
        }
    }
}

fn send_ip_packet(target: SocketAddr, packet: &[u8], protect_path: Option<&str>) -> io::Result<()> {
    let socket = match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: valid live socket fd and integer option payload.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            socket
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            crate::protect_socket(&socket, protect_path)?;
            // SAFETY: valid live socket fd and integer option payload.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            socket
        }
    };
    socket.send_to(packet, &SockAddr::from(target))?;
    Ok(())
}

fn send_icmp_packet(target: IpAddr, ttl: u8, packet: &[u8], protect_path: Option<&str>) -> io::Result<()> {
    let socket = match target {
        IpAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMP)))?;
            crate::protect_socket(&socket, protect_path)?;
            socket.set_ttl_v4(u32::from(ttl.max(1)))?;
            socket
        }
        IpAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMPV6)))?;
            crate::protect_socket(&socket, protect_path)?;
            socket.set_unicast_hops_v6(u32::from(ttl.max(1)))?;
            socket
        }
    };
    socket.send_to(packet, &SockAddr::from(SocketAddr::new(target, 0)))?;
    Ok(())
}

fn open_icmp_recv_socket(bind_ip: IpAddr) -> io::Result<Socket> {
    let socket = match bind_ip {
        IpAddr::V4(_) => Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMP)))?,
        IpAddr::V6(_) => Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_ICMPV6)))?,
    };
    socket.bind(&SockAddr::from(SocketAddr::new(bind_ip, 0)))?;
    Ok(socket)
}

fn extract_icmp_envelope(peer_ip: IpAddr, packet: &[u8]) -> Option<(IcmpWrappedUdpRole, u8, &[u8])> {
    match peer_ip {
        IpAddr::V4(_) => extract_icmpv4_envelope(packet),
        IpAddr::V6(_) => extract_icmpv6_envelope(packet),
    }
}

fn extract_icmpv4_envelope(packet: &[u8]) -> Option<(IcmpWrappedUdpRole, u8, &[u8])> {
    let icmp = if packet.first().is_some_and(|version| version >> 4 == 4) {
        let ihl = usize::from(packet[0] & 0x0f) * 4;
        if packet.len() < ihl + 8 || packet.get(9).copied()? != libc::IPPROTO_ICMP as u8 {
            return None;
        }
        &packet[ihl..]
    } else {
        if packet.len() < 8 {
            return None;
        }
        packet
    };
    let role = match icmp.first().copied()? {
        8 => IcmpWrappedUdpRole::ClientRequest,
        0 => IcmpWrappedUdpRole::ServerReply,
        _ => return None,
    };
    Some((role, icmp.get(1).copied()?, &icmp[8..]))
}

fn extract_icmpv6_envelope(packet: &[u8]) -> Option<(IcmpWrappedUdpRole, u8, &[u8])> {
    let icmp = if packet.first().is_some_and(|version| version >> 4 == 6) {
        if packet.len() < 48 || packet.get(6).copied()? != libc::IPPROTO_ICMPV6 as u8 {
            return None;
        }
        &packet[40..]
    } else {
        if packet.len() < 8 {
            return None;
        }
        packet
    };
    let role = match icmp.first().copied()? {
        128 => IcmpWrappedUdpRole::ClientRequest,
        129 => IcmpWrappedUdpRole::ServerReply,
        _ => return None,
    };
    Some((role, icmp.get(1).copied()?, &icmp[8..]))
}

fn sock_addr_ip(addr: &SockAddr) -> Option<IpAddr> {
    addr.as_socket().map(|socket| socket.ip())
}

fn checksum_sum(bytes: &[u8]) -> u32 {
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

fn finalize_checksum(mut sum: u32) -> u16 {
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

fn icmpv6_checksum(src_ip: [u8; 16], dst_ip: [u8; 16], payload: &[u8]) -> u16 {
    let payload_len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    let mut sum = checksum_sum(&src_ip);
    sum += checksum_sum(&dst_ip);
    sum += (payload_len >> 16) + (payload_len & 0xffff);
    sum += u32::from(58u16);
    sum += checksum_sum(payload);
    finalize_checksum(sum)
}

#[cfg(test)]
mod tests {
    use super::*;

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

    #[test]
    fn extract_icmpv4_envelope_accepts_ip_prefixed_packets() {
        let spec = IcmpWrappedUdpSpec {
            peer: "203.0.113.40:0".parse().expect("peer"),
            service_port: 53,
            payload: b"dns over icmp".to_vec(),
            session_id: 42,
            icmp_code: 199,
            ttl: 32,
            role: IcmpWrappedUdpRole::ClientRequest,
            xor_payload: false,
        };
        let envelope = encode_icmp_wrapped_udp_envelope(&spec).expect("encode");
        let icmp = build_icmp_echo_packet(spec.peer, spec.icmp_code, spec.ttl, spec.session_id, spec.role, &envelope)
            .expect("icmp");
        let total_len = u16::try_from(Ipv4Header::MIN_LEN + icmp.len()).expect("len");
        let mut ip =
            Ipv4Header::new(total_len, spec.ttl, etherparse::IpNumber::ICMP, [203, 0, 113, 40], [192, 0, 2, 10])
                .expect("ip");
        ip.header_checksum = ip.calc_header_checksum();
        let mut packet = Vec::new();
        ip.write(&mut packet).expect("write ip");
        packet.extend_from_slice(&icmp);

        let (role, code, payload) = extract_icmpv4_envelope(&packet).expect("extract");
        assert_eq!(role, spec.role);
        assert_eq!(code, spec.icmp_code);
        let decoded = decode_icmp_wrapped_udp_envelope(spec.peer.ip(), code, payload).expect("decode");
        assert_eq!(decoded.payload, spec.payload);
        assert_eq!(decoded.service_port, spec.service_port);
    }
}
