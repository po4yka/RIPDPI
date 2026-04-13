use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};

use ripdpi_config::RuntimeConfig;
use ripdpi_session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, SocketType, S_ATP_I4, S_ATP_I6, S_AUTH_BAD,
    S_AUTH_NONE, S_AUTH_USERPASS, S_VER5,
};

use super::super::state::RuntimeState;

#[derive(Clone, Copy)]
pub(super) enum HandshakeKind {
    Socks4,
    Socks5,
    HttpConnect,
}

pub(super) fn negotiate_socks5(client: &mut TcpStream, auth_token: Option<&str>) -> io::Result<()> {
    let mut count = [0u8; 1];
    client.read_exact(&mut count)?;
    let mut methods = vec![0u8; count[0] as usize];
    client.read_exact(&mut methods)?;

    if let Some(token) = auth_token {
        let method = if methods.contains(&S_AUTH_USERPASS) { S_AUTH_USERPASS } else { S_AUTH_BAD };
        client.write_all(&[S_VER5, method])?;
        if method == S_AUTH_BAD {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks5 auth method"));
        }
        // RFC 1929 sub-negotiation
        let mut sub_ver = [0u8; 1];
        client.read_exact(&mut sub_ver)?;
        if sub_ver[0] != 0x01 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socks5 auth sub-negotiation version"));
        }
        let mut ulen = [0u8; 1];
        client.read_exact(&mut ulen)?;
        let mut username = vec![0u8; ulen[0] as usize];
        client.read_exact(&mut username)?;
        let mut plen = [0u8; 1];
        client.read_exact(&mut plen)?;
        let mut password = vec![0u8; plen[0] as usize];
        client.read_exact(&mut password)?;
        if password != token.as_bytes() {
            client.write_all(&[0x01, 0x01])?;
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid socks5 credentials"));
        }
        client.write_all(&[0x01, 0x00])?;
    } else {
        let method = if methods.contains(&S_AUTH_NONE) { S_AUTH_NONE } else { S_AUTH_BAD };
        client.write_all(&[S_VER5, method])?;
        if method == S_AUTH_BAD {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no supported socks auth method"));
        }
    }
    Ok(())
}

pub(super) fn read_socks5_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    client.read_exact(&mut header)?;
    let mut out = header.to_vec();
    match header[3] {
        S_ATP_I4 => {
            let mut tail = [0u8; 6];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        S_ATP_I6 => {
            let mut tail = [0u8; 18];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len)?;
            if len[0] == 0 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "empty SOCKS5 domain name"));
            }
            out.extend_from_slice(&len);
            let mut tail = vec![0u8; len[0] as usize + 2];
            client.read_exact(&mut tail)?;
            out.extend_from_slice(&tail);
        }
        _ => {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 address type"));
        }
    }
    Ok(out)
}

pub(super) fn read_socks4_request(client: &mut TcpStream, version: u8) -> io::Result<Vec<u8>> {
    let mut out = vec![version];
    let mut fixed = [0u8; 7];
    client.read_exact(&mut fixed)?;
    out.extend_from_slice(&fixed);

    read_until_nul(client, &mut out)?;
    let is_domain = out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] != 0;
    if is_domain {
        read_until_nul(client, &mut out)?;
    }
    Ok(out)
}

fn read_until_nul(client: &mut TcpStream, out: &mut Vec<u8>) -> io::Result<()> {
    loop {
        let mut byte = [0u8; 1];
        client.read_exact(&mut byte)?;
        out.push(byte[0]);
        if byte[0] == 0 {
            return Ok(());
        }
        if out.len() > 4096 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "request too large"));
        }
    }
}

/// Validate HTTP `Proxy-Authorization: Basic` header against the expected auth token.
///
/// Expected encoding: `Basic base64("ripdpi:<token>")`.
pub(super) fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
    use base64::engine::{general_purpose::STANDARD, Engine};
    let Ok(request_str) = std::str::from_utf8(request) else { return false };
    for line in request_str.lines() {
        if let Some(value) = line.strip_prefix("Proxy-Authorization:") {
            let value = value.trim();
            if let Some(encoded) = value.strip_prefix("Basic ") {
                let encoded = encoded.trim();
                if let Ok(decoded) = STANDARD.decode(encoded) {
                    let expected = format!("ripdpi:{token}");
                    return decoded == expected.as_bytes();
                }
            }
            return false;
        }
    }
    false
}

pub(super) fn read_http_connect_request(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut chunk = [0u8; 512];
    loop {
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during http connect request"));
        }
        out.extend_from_slice(&chunk[..n]);
        if out.windows(4).any(|window| window == b"\r\n\r\n") {
            return Ok(out);
        }
        if out.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "http connect request too large"));
        }
    }
}

pub(in crate::runtime) fn resolve_name(
    host: &str,
    _socket_type: SocketType,
    state: &RuntimeState,
) -> Option<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, 0));
    }
    if let Some(loopback) = resolve_localhost(host, state.config.network.ipv6) {
        return Some(loopback);
    }
    if !state.config.network.resolve {
        return None;
    }
    crate::ws_bootstrap::resolve_host_via_encrypted_dns(
        host,
        state.runtime_context.as_ref(),
        state.config.process.protect_path.as_deref(),
        state.config.network.ipv6,
    )
    .ok()
}

fn resolve_localhost(host: &str, ipv6_enabled: bool) -> Option<SocketAddr> {
    if !host.eq_ignore_ascii_case("localhost") && !host.eq_ignore_ascii_case("localhost.") {
        return None;
    }

    let ip = if ipv6_enabled { IpAddr::V6(Ipv6Addr::LOCALHOST) } else { IpAddr::V4(Ipv4Addr::LOCALHOST) };
    Some(SocketAddr::new(ip, 0))
}

pub(super) fn send_success_reply(client: &mut TcpStream, handshake: HandshakeKind) -> io::Result<()> {
    match handshake {
        HandshakeKind::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        HandshakeKind::Socks5 => {
            let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(0, addr).as_bytes())
        }
        HandshakeKind::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}

pub(super) fn read_shadowsocks_request(
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

pub(super) fn parse_shadowsocks_target(
    packet: &[u8],
    config: &RuntimeConfig,
    mut resolver: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, usize)> {
    let atyp = *packet.first()?;
    match atyp {
        S_ATP_I4 => {
            if packet.len() < 7 {
                return None;
            }
            let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
            let port = u16::from_be_bytes([packet[5], packet[6]]);
            Some((SocketAddr::new(IpAddr::V4(ip), port), 7))
        }
        S_ATP_I6 => {
            if packet.len() < 19 || !config.network.ipv6 {
                return None;
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&packet[1..17]);
            let port = u16::from_be_bytes([packet[17], packet[18]]);
            Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), 19))
        }
        0x03 => {
            let len = *packet.get(1)? as usize;
            if packet.len() < 2 + len + 2 || !config.network.resolve {
                return None;
            }
            let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
            let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
            let resolved = resolver(host, SocketType::Stream)?;
            Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
        }
        _ => None,
    }
}
