use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use ripdpi_socks5_core::validate_udp_rsv_frag;

use super::super::config::{RuntimeConfig, ShadowsocksTargetPolicy, ipv6_enabled, name_resolution_enabled};
use super::{S_ATP_I4, S_ATP_I6, S_ATP_ID, S_CMD_CONN, S_VER5, SocketType, TargetAddr};

const S_ATP_RESOLVED_ID: u8 = ripdpi_session::S_ATP_RESOLVED_ID;

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
    resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    parse_socks5_udp_packet_with_host(packet, config, resolve_name).map(|parsed| (parsed.target, parsed.payload))
}

pub struct ParsedSocks5UdpPacket<'a> {
    pub target: SocketAddr,
    pub host: Option<String>,
    pub preserve_host_in_response: bool,
    pub payload: &'a [u8],
}

pub fn parse_socks5_udp_packet_with_host<'a>(
    packet: &'a [u8],
    config: &RuntimeConfig,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<ParsedSocks5UdpPacket<'a>> {
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
            Some(ParsedSocks5UdpPacket {
                target: SocketAddr::new(IpAddr::V4(ip), port),
                host: None,
                preserve_host_in_response: false,
                payload: &packet[10..],
            })
        }
        S_ATP_I6 => {
            if packet.len() < 22 || !ipv6_enabled(config) {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[4..20]);
            let port = u16::from_be_bytes([packet[20], packet[21]]);
            Some(ParsedSocks5UdpPacket {
                target: SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port),
                host: None,
                preserve_host_in_response: false,
                payload: &packet[22..],
            })
        }
        S_ATP_ID => {
            let len = *packet.get(4)? as usize;
            let offset = 5 + len;
            if packet.len() < offset + 2 || !name_resolution_enabled(config) {
                return None;
            }
            let host = std::str::from_utf8(&packet[5..offset]).ok()?;
            let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
            let canonical_host = host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase();
            let resolved = resolve_name(&canonical_host, SocketType::Datagram)?;
            Some(ParsedSocks5UdpPacket {
                target: SocketAddr::new(resolved.ip(), port),
                host: Some(canonical_host),
                preserve_host_in_response: false,
                payload: &packet[offset + 2..],
            })
        }
        S_ATP_RESOLVED_ID if private_resolved_targets_allowed(config) => parse_resolved_udp_target(packet),
        _ => None,
    }
}

fn private_resolved_targets_allowed(config: &RuntimeConfig) -> bool {
    config.network.listen.listen_ip.is_loopback()
        && config.network.listen.listen_port == 0
        && config.network.listen.auth_token.is_some()
}

fn parse_resolved_udp_target(packet: &[u8]) -> Option<ParsedSocks5UdpPacket<'_>> {
    let len = *packet.get(4)? as usize;
    if len == 0 {
        return None;
    }
    let host_end = 5 + len;
    let host = std::str::from_utf8(packet.get(5..host_end)?).ok()?.to_ascii_lowercase();
    let ip_type = *packet.get(host_end)?;
    let (ip, port_offset) = match ip_type {
        S_ATP_I4 => {
            let raw = packet.get(host_end + 1..host_end + 5)?;
            (IpAddr::V4(Ipv4Addr::new(raw[0], raw[1], raw[2], raw[3])), host_end + 5)
        }
        S_ATP_I6 => {
            let raw = packet.get(host_end + 1..host_end + 17)?;
            let mut octets = [0; 16];
            octets.copy_from_slice(raw);
            (IpAddr::V6(Ipv6Addr::from(octets)), host_end + 17)
        }
        _ => return None,
    };
    let port = u16::from_be_bytes([*packet.get(port_offset)?, *packet.get(port_offset + 1)?]);
    Some(ParsedSocks5UdpPacket {
        target: SocketAddr::new(ip, port),
        host: Some(host),
        preserve_host_in_response: true,
        payload: packet.get(port_offset + 2..)?,
    })
}

pub fn parse_socks5_udp_packet_with<'a>(
    parser: &UdpPacketParser,
    packet: &'a [u8],
    resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    parse_socks5_udp_packet(packet, &parser.config, resolve_name)
}

pub fn parse_socks5_udp_packet_with_host_from_parser<'a>(
    parser: &UdpPacketParser,
    packet: &'a [u8],
    resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<ParsedSocks5UdpPacket<'a>> {
    parse_socks5_udp_packet_with_host(packet, &parser.config, resolve_name)
}

pub fn encode_socks5_udp_packet_into(out: &mut Vec<u8>, sender: SocketAddr, payload: &[u8]) {
    encode_socks5_udp_packet_with_host_into(out, sender, None, payload);
}

pub fn encode_socks5_udp_packet_with_host_into(
    out: &mut Vec<u8>,
    sender: SocketAddr,
    host: Option<&str>,
    payload: &[u8],
) {
    out.clear();
    out.extend_from_slice(&[0, 0, 0]);
    if let Some(host) = host.filter(|host| !host.is_empty() && host.len() <= u8::MAX as usize) {
        out.extend_from_slice(&[S_ATP_ID, host.len() as u8]);
        out.extend_from_slice(host.as_bytes());
        out.extend_from_slice(&sender.port().to_be_bytes());
        out.extend_from_slice(payload);
        return;
    }
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

pub fn encode_socks5_udp_packet_with_resolved_host_into(
    out: &mut Vec<u8>,
    sender: SocketAddr,
    host: &str,
    payload: &[u8],
) {
    out.clear();
    let canonical_host = host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase();
    if canonical_host.is_empty() || canonical_host.len() > u8::MAX as usize {
        encode_socks5_udp_packet_into(out, sender, payload);
        return;
    }
    out.extend_from_slice(&[0, 0, 0, S_ATP_RESOLVED_ID, canonical_host.len() as u8]);
    out.extend_from_slice(canonical_host.as_bytes());
    match sender {
        SocketAddr::V4(addr) => {
            out.push(S_ATP_I4);
            out.extend_from_slice(&addr.ip().octets());
        }
        SocketAddr::V6(addr) => {
            out.push(S_ATP_I6);
            out.extend_from_slice(&addr.ip().octets());
        }
    }
    out.extend_from_slice(&sender.port().to_be_bytes());
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
) -> Option<(TargetAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => parse_ipv4_target(packet),
        S_ATP_I6 => parse_ipv6_target(packet, policy.ipv6_enabled),
        S_ATP_ID => parse_domain_target(packet, policy.resolve_enabled, &mut resolve_name),
        _ => None,
    }
}

fn parse_ipv4_target(packet: &[u8]) -> Option<(TargetAddr, usize)> {
    if packet.len() < 7 {
        return None;
    }

    let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
    let port = u16::from_be_bytes([packet[5], packet[6]]);
    Some((TargetAddr { addr: SocketAddr::new(IpAddr::V4(ip), port), host: None }, 7))
}

fn parse_ipv6_target(packet: &[u8], ipv6_enabled: bool) -> Option<(TargetAddr, usize)> {
    if packet.len() < 19 || !ipv6_enabled {
        return None;
    }

    let mut raw = [0u8; 16];
    raw.copy_from_slice(&packet[1..17]);
    let port = u16::from_be_bytes([packet[17], packet[18]]);
    Some((TargetAddr { addr: SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), host: None }, 19))
}

fn parse_domain_target(
    packet: &[u8],
    resolve_enabled: bool,
    mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(TargetAddr, usize)> {
    let len = *packet.get(1)? as usize;
    if packet.len() < 2 + len + 2 || !resolve_enabled {
        return None;
    }

    let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
    let canonical_host = host.strip_suffix('.').unwrap_or(host).to_ascii_lowercase();
    let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
    let resolved = resolve_name(&canonical_host, SocketType::Stream)?;
    Some((TargetAddr { addr: SocketAddr::new(resolved.ip(), port), host: Some(canonical_host) }, 2 + len + 2))
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
    fn encode_response_with_host_preserves_logical_authority() {
        let mut buf = Vec::new();
        encode_socks5_udp_packet_with_host_into(
            &mut buf,
            SocketAddr::from(([203, 0, 113, 9], 443)),
            Some("one.example"),
            b"reply",
        );

        assert_eq!(&buf[..5], &[0, 0, 0, S_ATP_ID, 11]);
        assert_eq!(&buf[5..16], b"one.example");
        assert_eq!(&buf[16..18], &443u16.to_be_bytes());
        assert_eq!(&buf[18..], b"reply");
    }

    #[test]
    fn encode_resolved_response_preserves_canonical_host_and_pinned_ip() {
        let mut buf = Vec::new();
        encode_socks5_udp_packet_with_resolved_host_into(
            &mut buf,
            SocketAddr::from(([203, 0, 113, 9], 443)),
            "One.Example.",
            b"reply",
        );

        assert_eq!(&buf[..5], &[0, 0, 0, S_ATP_RESOLVED_ID, 11]);
        assert_eq!(&buf[5..16], b"one.example");
        assert_eq!(&buf[16..21], &[S_ATP_I4, 203, 0, 113, 9]);
        assert_eq!(&buf[21..23], &443u16.to_be_bytes());
        assert_eq!(&buf[23..], b"reply");
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
    fn udp_packet_parser_preserves_private_resolved_domain_without_resolver() {
        let mut config = RuntimeConfig::default();
        config.network.listen.listen_port = 0;
        config.network.listen.auth_token = Some("internal-token".to_string());
        let parser = udp_packet_parser(&config);
        let mut packet = vec![0, 0, 0, S_ATP_RESOLVED_ID, 11];
        packet.extend_from_slice(b"example.com");
        packet.extend_from_slice(&[S_ATP_I4, 203, 0, 113, 9]);
        packet.extend_from_slice(&443u16.to_be_bytes());
        packet.push(b'x');

        let parsed =
            parse_socks5_udp_packet_with_host_from_parser(&parser, &packet, |_, _| panic!("resolver must not run"))
                .expect("resolved domain packet");

        assert_eq!(parsed.target, SocketAddr::from(([203, 0, 113, 9], 443)));
        assert_eq!(parsed.host.as_deref(), Some("example.com"));
        assert_eq!(parsed.payload, b"x");
    }

    #[test]
    fn udp_packet_parser_rejects_private_resolved_domain_on_public_listener() {
        let config = RuntimeConfig::default();
        let parser = udp_packet_parser(&config);
        let mut packet = vec![0, 0, 0, S_ATP_RESOLVED_ID, 11];
        packet.extend_from_slice(b"example.com");
        packet.extend_from_slice(&[S_ATP_I4, 203, 0, 113, 9]);
        packet.extend_from_slice(&443u16.to_be_bytes());

        assert!(parse_socks5_udp_packet_with_host_from_parser(&parser, &packet, |_, _| None).is_none());
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
