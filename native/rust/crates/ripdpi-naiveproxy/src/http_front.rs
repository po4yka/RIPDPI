use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::NaiveProxyConfig;
use crate::socks5::SocksTarget;
use base64::Engine;

const MAX_HTTP_HEAD_BYTES: usize = 16 * 1024;
const BAD_REQUEST_RESPONSE: &[u8] = b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n";

/// Reads one authenticated local `CONNECT host:port` request and returns its target.
///
/// # Cancel safety:
/// NOT cancel-safe: partial headers consume bytes. The caller closes the socket on timeout.
pub(crate) async fn read_http_front_request(
    client: &mut TcpStream,
    config: &NaiveProxyConfig,
) -> io::Result<SocksTarget> {
    let bytes = read_headers(client).await?;
    // A field needs at least a name byte, a colon, and a newline.
    let mut headers = vec![httparse::EMPTY_HEADER; bytes.len() / 3];
    let mut request = httparse::Request::new(&mut headers);
    let parsed = request.parse(&bytes);
    let (method, authority) = match (parsed, request.method, request.path, request.version) {
        (Ok(httparse::Status::Complete(length)), Some(method), Some(authority), Some(0 | 1))
            if length == bytes.len() =>
        {
            (method, authority)
        }
        _ => {
            client.write_all(BAD_REQUEST_RESPONSE).await?;
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid HTTP proxy request head"));
        }
    };
    if !has_local_authorization(request.headers, config) {
        client.write_all(b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"local\"\r\nConnection: close\r\n\r\n").await?;
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid local HTTP proxy credentials"));
    }
    // Every in-app client speaks SOCKS5; plain absolute-form forwarding had no production caller.
    if !method.eq_ignore_ascii_case("CONNECT") {
        client.write_all(b"HTTP/1.1 405 Method Not Allowed\r\nAllow: CONNECT\r\nConnection: close\r\n\r\n").await?;
        return Err(io::Error::new(io::ErrorKind::Unsupported, "unsupported HTTP proxy method"));
    }
    match parse_authority(authority) {
        Ok(target) => Ok(target),
        Err(error) => {
            client.write_all(BAD_REQUEST_RESPONSE).await?;
            Err(error)
        }
    }
}

fn has_local_authorization(headers: &[httparse::Header<'_>], config: &NaiveProxyConfig) -> bool {
    let mut values = headers.iter().filter(|header| header.name.eq_ignore_ascii_case("Proxy-Authorization"));
    let Some(header) = values.next() else { return false };
    if values.next().is_some() {
        return false;
    }
    let Ok(value) = std::str::from_utf8(header.value) else { return false };
    let Some((scheme, encoded)) = value.trim().split_once(' ') else { return false };
    if !scheme.eq_ignore_ascii_case("Basic") {
        return false;
    }
    let Ok(decoded) = base64::engine::general_purpose::STANDARD.decode(encoded.trim()) else { return false };
    let Some(separator) = decoded.iter().position(|byte| *byte == b':') else { return false };
    config.accepts_local_credentials(&decoded[..separator], &decoded[separator + 1..])
}

/// # Cancel safety:
/// NOT cancel-safe in isolation: write_all may send a prefix; the owner closes the socket on cancellation.
pub(crate) async fn write_http_connect_success(client: &mut TcpStream) -> io::Result<()> {
    client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await
}

/// # Cancel safety:
/// NOT cancel-safe in isolation: write_all may send a prefix; the owner closes the socket on cancellation.
pub(crate) async fn write_http_connect_failure(client: &mut TcpStream) -> io::Result<()> {
    client.write_all(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n").await
}

/// # Cancel safety:
/// NOT cancel-safe: partial input is consumed; the owner closes the client if this future is dropped.
async fn read_headers(client: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    let mut byte = [0u8; 1];
    while !bytes.ends_with(b"\r\n\r\n") {
        if bytes.len() >= MAX_HTTP_HEAD_BYTES {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "HTTP proxy request headers too large"));
        }
        client.read_exact(&mut byte).await?;
        bytes.push(byte[0]);
    }
    std::str::from_utf8(&bytes)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid HTTP proxy request headers"))?;
    Ok(bytes)
}

fn parse_authority(authority: &str) -> io::Result<SocksTarget> {
    let invalid = || io::Error::new(io::ErrorKind::InvalidData, "invalid HTTP CONNECT authority");
    if authority.contains('@') {
        return Err(invalid());
    }
    if let Ok(address) = authority.parse::<SocketAddr>() {
        return Ok(SocksTarget::Ip(address));
    }
    let authority = authority.parse::<http::uri::Authority>().map_err(|_| invalid())?;
    let host = authority.host();
    if host.is_empty() || host.starts_with('[') {
        return Err(invalid());
    }
    let port = authority.port_u16().ok_or_else(invalid)?;
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
    #[test]
    fn invalid_authority_errors_do_not_echo_input() {
        for authority in [
            "example.com",
            ":443",
            "example.com:65536",
            "user:private@example.com:443",
            "example.com:private",
            "example.com/path:443",
            "[example.com]:443",
            "[::g]:443",
        ] {
            let error = parse_authority(authority).expect_err("invalid authority");
            assert_eq!(error.kind(), io::ErrorKind::InvalidData);
            assert_eq!(error.to_string(), "invalid HTTP CONNECT authority");
        }
    }
}
