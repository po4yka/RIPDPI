use std::io;
use std::mem::MaybeUninit;
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

use crate::experimental_tier3::{
    decode_icmp_wrapped_udp_envelope, encode_icmp_wrapped_udp_envelope, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole,
    IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp,
};

use super::checksum::{checksum_sum, finalize_checksum, icmpv6_checksum};
use super::raw_socket::{open_icmp_recv_socket, send_icmp_packet, sock_addr_ip};

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

#[cfg(test)]
mod tests {
    use etherparse::Ipv4Header;

    use crate::experimental_tier3::{
        decode_icmp_wrapped_udp_envelope, encode_icmp_wrapped_udp_envelope, IcmpWrappedUdpRole, IcmpWrappedUdpSpec,
    };

    use super::{build_icmp_echo_packet, extract_icmpv4_envelope};

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
