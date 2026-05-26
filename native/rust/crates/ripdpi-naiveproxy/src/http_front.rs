use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::socks5::SocksTarget;

pub(crate) async fn read_http_connect_request(client: &mut TcpStream) -> io::Result<SocksTarget> {
    let headers = read_headers(client).await?;
    let request_line =
        headers.lines().next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "empty HTTP request"))?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default();
    let authority = parts.next().unwrap_or_default();
    let version = parts.next().unwrap_or_default();

    if !method.eq_ignore_ascii_case("CONNECT") {
        return Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported HTTP proxy method {method}")));
    }
    if !version.starts_with("HTTP/1.") {
        return Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported HTTP proxy version {version}")));
    }

    parse_authority(authority)
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
    let host = host.trim_matches(['[', ']']);
    if host.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "HTTP CONNECT authority host is empty"));
    }
    let port = port.parse::<u16>().map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP CONNECT authority port {port}: {error}"))
    })?;

    Ok(SocksTarget::Domain(host.to_owned(), port))
}
