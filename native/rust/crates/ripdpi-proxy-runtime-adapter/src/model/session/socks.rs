use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_socks5_core::validate_udp_rsv_frag;

use super::super::config::{RuntimeConfig, ShadowsocksTargetPolicy, ipv6_enabled, name_resolution_enabled};
use super::{S_ATP_I4, S_ATP_I6, S_ATP_ID, S_CMD_CONN, S_VER5, SocketType};

#[derive(Clone)]
pub struct UdpPacketParser {
    config: RuntimeConfig,
}

pub fn udp_packet_parser(config: &RuntimeConfig) -> UdpPacketParser {
    UdpPacketParser { config: config.clone() }
}

pub fn parse_socks5_udp_packet<'a>(
    packet: &'a [u8],
    config: &RuntimeConfig,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    if packet.len() < 4 || validate_udp_rsv_frag(packet).is_err() {
        return None;
    }
    let atyp = packet[3];
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 10 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
            let port = u16::from_be_bytes([packet[8], packet[9]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), &packet[10..]))
        }
        S_ATP_I6 => {
            if packet.len() < 22 || !ipv6_enabled(config) {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), &packet[22..]))
        }
        S_ATP_ID => {
            let len = *packet.get(4)? as usize;
            let offset = 5 + len;
            if packet.len() < offset + 2 || !name_resolution_enabled(config) {
                return None;
            }
            let host = std::str::from_utf8(&packet[5..offset]).ok()?;
            let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            let resolved = resolve_name(host, SocketType::Datagram)?;
            Some((SocketAddr::new(resolved.ip(), port), &packet[offset + 2..]))
        }
        _ => None,
    }
}

pub fn parse_socks5_udp_packet_with<'a>(
    parser: &UdpPacketParser,
    packet: &'a [u8],
    resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    parse_socks5_udp_packet(packet, &parser.config, resolve_name)
}

pub fn encode_socks5_udp_packet_into(out: &mut Vec<u8>, sender: SocketAddr, payload: &[u8]) {
    out.clear();
    out.extend_from_slice(&[0, 0, 0]);
    match sender {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out.extend_from_slice(payload);
}

pub fn encode_socks5_udp_packet(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    encode_socks5_udp_packet_into(&mut out, sender, payload);
    out
}

pub fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
    let mut out = vec![S_VER5, S_CMD_CONN, 0];
    match target {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out
}

pub fn read_upstream_socks_reply(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    reader.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            reader.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            reader.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_ID => {
            let mut len = [0u8; 1];
            reader.read_exact(&mut len)?;
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            reader.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
    }
    Ok(out)
}

pub fn parse_shadowsocks_target(
    packet: &[u8],
    policy: ShadowsocksTargetPolicy,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => parse_ipv4_target(packet),
        S_ATP_I6 => parse_ipv6_target(packet, policy.ipv6_enabled),
        S_ATP_ID => parse_domain_target(packet, policy.resolve_enabled, &mut resolve_name),
        _ => None,
    }
}

fn parse_ipv4_target(packet: &[u8]) -> Option<(SocketAddr, usize)> {
    if packet.len() < 7 {
        return None;
    }

    let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
    let port = u16::from_be_bytes([packet[5], packet[6]]);
    Some((SocketAddr::new(IpAddr::V4(ip), port), 7))
}

fn parse_ipv6_target(packet: &[u8], ipv6_enabled: bool) -> Option<(SocketAddr, usize)> {
    if packet.len() < 19 || !ipv6_enabled {
        return None;
    }

    let mut raw = [0u8; 16];
    raw.copy_from_slice(&packet[1..17]);
    let port = u16::from_be_bytes([packet[17], packet[18]]);
    Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), 19))
}

fn parse_domain_target(
    packet: &[u8],
    resolve_enabled: bool,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let len = *packet.get(1)? as usize;
    if packet.len() < 2 + len + 2 || !resolve_enabled {
        return None;
    }

    let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
    let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
    let resolved = resolve_name(host, SocketType::Stream)?;
    Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_into_matches_owned_v4() {
        let sender = SocketAddr::from(([203, 0, 113, 7], 443));
        let payload = b"hello world";
        let owned = encode_socks5_udp_packet(sender, payload);
        let mut buf = Vec::new();
        encode_socks5_udp_packet_into(&mut buf, sender, payload);
        assert_eq!(buf, owned);
    }

    #[test]
    fn encode_into_matches_owned_v6() {
        use std::net::{IpAddr, Ipv6Addr};
        let sender = SocketAddr::new(
            IpAddr::V6(Ipv6Addr::from([0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1])),
            8080,
        );
        let payload = b"quic datagram payload";
        let owned = encode_socks5_udp_packet(sender, payload);
        let mut buf = Vec::new();
        encode_socks5_udp_packet_into(&mut buf, sender, payload);
        assert_eq!(buf, owned);
    }

    #[test]
    fn encode_into_reuses_buffer() {
        let sender = SocketAddr::from(([192, 0, 2, 1], 53));
        // First call with longer payload.
        let mut buf = Vec::new();
        encode_socks5_udp_packet_into(&mut buf, sender, b"first payload that is longer");
        let first_len = buf.len();
        // Second call with shorter payload — no stale bytes from the first call.
        encode_socks5_udp_packet_into(&mut buf, sender, b"short");
        let expected = encode_socks5_udp_packet(sender, b"short");
        assert_eq!(buf, expected, "second encode must not carry stale tail bytes from the first call");
        assert!(buf.len() < first_len, "shorter payload must produce a shorter result");
    }

    #[test]
    fn udp_packet_parser_preserves_socks5_domain_resolution() {
        let config = RuntimeConfig::default();
        let parser = udp_packet_parser(&config);
        let packet = [0, 0, 0, S_ATP_ID, 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 0x01, 0xbb, b'x'];

        let parsed = parse_socks5_udp_packet_with(&parser, &packet, |host, socket_type| {
            assert_eq!(host, "example");
            assert_eq!(socket_type, SocketType::Datagram);
            Some(SocketAddr::from(([203, 0, 113, 7], 0)))
        });

        assert_eq!(parsed, Some((SocketAddr::from(([203, 0, 113, 7], 443)), &b"x"[..])));
    }

    #[test]
    fn udp_packet_parser_rejects_nonzero_reserved_bytes() {
        let config = RuntimeConfig::default();
        let packet = [0, 1, 0, S_ATP_I4, 192, 0, 2, 1, 0x01, 0xbb, b'x'];

        let parsed = parse_socks5_udp_packet(&packet, &config, |_, _| None);

        assert_eq!(parsed, None);
    }

    #[test]
    fn udp_packet_parser_rejects_nonzero_frag() {
        let config = RuntimeConfig::default();
        let packet = [0, 0, 1, S_ATP_I4, 192, 0, 2, 1, 0x01, 0xbb, b'x'];

        let parsed = parse_socks5_udp_packet(&packet, &config, |_, _| None);

        assert_eq!(parsed, None);
    }
}
