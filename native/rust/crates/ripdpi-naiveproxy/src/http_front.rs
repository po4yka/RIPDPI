use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::NaiveProxyConfig;
use crate::socks5::SocksTarget;
use base64::Engine;

/// Reads one authenticated local `CONNECT host:port` request and returns its target.
///
/// # Cancel safety:
/// NOT cancel-safe: partial headers consume bytes. The caller closes the socket on timeout.
pub(crate) async fn read_http_front_request(
    client: &mut TcpStream,
    config: &NaiveProxyConfig,
) -> io::Result<SocksTarget> {
    let headers = read_headers(client).await?;
    if !has_local_authorization(&headers, config) {
        client.write_all(b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"local\"\r\nConnection: close\r\n\r\n").await?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid local HTTP proxy credentials"));
    }
    let request_line =
        headers.lines().next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "empty HTTP request"))?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default();
    let authority = parts.next().unwrap_or_default();
    let version = parts.next().unwrap_or_default();

    if !version.starts_with("HTTP/1.") {
        return Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported HTTP proxy version {version}")));
    }
    // Every in-app client speaks SOCKS5; plain absolute-form forwarding had no production caller.
    if !method.eq_ignore_ascii_case("CONNECT") {
        client.write_all(b"HTTP/1.1 405 Method Not Allowed\r\nAllow: CONNECT\r\nConnection: close\r\n\r\n").await?;
        return Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported HTTP proxy method {method}")));
    }
    parse_authority(authority)
}

fn has_local_authorization(headers: &str, config: &NaiveProxyConfig) -> bool {
    let mut values = headers.lines().skip(1).filter_map(|line| {
        let (name, value) = line.split_once(':')?;
        name.eq_ignore_ascii_case("Proxy-Authorization").then_some(value.trim())
    });
    let Some(value) = values.next() else { return false };
    if values.next().is_some() {
        return false;
    }
    let Some((scheme, encoded)) = value.split_once(' ') else { return false };
    if !scheme.eq_ignore_ascii_case("Basic") {
        return false;
    }
    let Ok(decoded) = base64::engine::general_purpose::STANDARD.decode(encoded.trim()) else { return false };
    let Some(separator) = decoded.iter().position(|byte| *byte == b':') else { return false };
    config.accepts_local_credentials(&decoded[..separator], &decoded[separator + 1..])
}

pub(crate) async fn write_http_connect_success(client: &mut TcpStream) -> io::Result<()> {
    client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await
}

pub(crate) async fn write_http_connect_failure(client: &mut TcpStream) -> io::Result<()> {
    client.write_all(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n").await
}

async fn read_headers(client: &mut TcpStream) -> io::Result<String> {
    let mut bytes = Vec::new();
    let mut byte = [0u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        if bytes.len() >= 16 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "HTTP proxy request headers too large"));
        }
        client.read_exact(&mut byte).await?;
        bytes.push(byte[0]);
    }
    String::from_utf8(bytes).map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP proxy request headers: {error}"))
    })
}

fn parse_authority(authority: &str) -> io::Result<SocksTarget> {
    if let Ok(address) = authority.parse::<SocketAddr>() {
        return Ok(SocksTarget::Ip(address));
    }

    let (host, port) = authority
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "HTTP CONNECT authority is missing a port"))?;
    if !host.starts_with('[') && host.contains(':') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "HTTP CONNECT authority must use bracketed form for IPv6 literals",
        ));
    }
    let host = host.trim_matches(['[', ']']);
    if host.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "HTTP CONNECT authority host is empty"));
    }
    let port = port.parse::<u16>().map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP CONNECT authority port {port}: {error}"))
    })?;

    Ok(SocksTarget::Domain(host.to_owned(), port))
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::*;

    /// Regression test (audit H4 siblings): a bare IPv6 authority must be
    /// rejected instead of becoming a colon-containing "domain".
    #[test]
    fn parse_authority_rejects_bare_ipv6_authority() {
        assert!(parse_authority("2001:db8::1").is_err(), "bare IPv6 without port must be rejected");
        assert!(parse_authority("2001:db8::1:443").is_err(), "unbracketed IPv6 with port must be rejected");
    }

    #[test]
    fn parse_authority_accepts_bracketed_ipv6_and_domain() {
        let bracketed = parse_authority("[2001:db8::1]:443").expect("bracketed IPv6 authority parses");
        assert_eq!(bracketed, SocksTarget::Ip("[2001:db8::1]:443".parse().expect("socket addr")));
        let domain = parse_authority("example.com:443").expect("domain authority parses");
        assert_eq!(domain, SocksTarget::Domain("example.com".to_owned(), 443));
    }
}
