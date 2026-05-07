use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::session::{SocketType, S_ATP_I4, S_ATP_I6};

pub(in crate::runtime::handshake) fn read_shadowsocks_request(
    client: &mut TcpStream,
    first_byte: u8,
    config: &RuntimeConfig,
    mut resolver: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> io::Result<(SocketAddr, Vec<u8>)> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 4096];
    loop {
        if let Some((target, header_len)) = parse_shadowsocks_target(&request, config, &mut resolver) {
            return Ok((target, request[header_len..].to_vec()));
        }
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during shadowsocks request"));
        }
        request.extend_from_slice(&chunk[..n]);
        if request.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "shadowsocks request too large"));
        }
    }
}

pub(in crate::runtime::handshake) fn parse_shadowsocks_target(
    packet: &[u8],
    config: &RuntimeConfig,
    mut resolver: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => parse_ipv4_target(packet),
        S_ATP_I6 => parse_ipv6_target(packet, config.network.ipv6),
        0x03 => parse_domain_target(packet, config.network.resolve, &mut resolver),
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
    mut resolver: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let len = *packet.get(1)? as usize;
    if packet.len() < 2 + len + 2 || !resolve_enabled {
        return None;
    }

    let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
    let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
    let resolved = resolver(host, SocketType::Stream)?;
    Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
}
