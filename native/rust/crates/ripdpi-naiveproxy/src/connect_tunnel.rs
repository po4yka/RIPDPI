use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use base64::prelude::{Engine as _, BASE64_STANDARD};
use rustls::pki_types::ServerName;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{lookup_host, TcpStream};
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

use crate::config::NaiveProxyConfig;
use crate::socks5::SocksTarget;

pub(crate) async fn open_https_connect_tunnel(
    config: &NaiveProxyConfig,
    target: &SocksTarget,
) -> io::Result<(TlsStream<TcpStream>, Vec<u8>)> {
    let upstream_socket = resolve_first(&config.server, config.server_port).await?;
    let tcp = TcpStream::connect(upstream_socket).await?;
    tcp.set_nodelay(true)?;

    let server_name = ServerName::try_from(config.server_name.clone()).map_err(|error| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid NaiveProxy server name {}: {error}", config.server_name),
        )
    })?;
    let connector = TlsConnector::from(Arc::clone(&config.tls_config));
    let mut tls = connector.connect(server_name, tcp).await?;

    let request = build_connect_request(config, target);
    tls.write_all(request.as_bytes()).await?;
    tls.flush().await?;

    let (status_code, leftover) = read_connect_response(&mut tls).await?;
    if status_code != 200 {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!("NaiveProxy upstream rejected CONNECT with status {status_code}"),
        ));
    }

    Ok((tls, leftover))
}

pub(crate) fn build_connect_request(config: &NaiveProxyConfig, target: &SocksTarget) -> String {
    let authority = target.authority();
    let mut request = format!(
        "CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nProxy-Connection: Keep-Alive\r\nConnection: Keep-Alive\r\nUser-Agent: Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/123.0.0.0 Mobile Safari/537.36\r\n"
    );
    if let (Some(username), Some(password)) = (&config.username, &config.password) {
        let encoded = BASE64_STANDARD.encode(format!("{username}:{password}"));
        request.push_str(&format!("Proxy-Authorization: Basic {encoded}\r\n"));
    }
    if let Some(path) = &config.path {
        request.push_str(&format!("X-Naive-Path: {path}\r\n"));
    }
    request.push_str("\r\n");
    request
}

pub(crate) fn find_header_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n").map(|index| index + 4)
}

pub(crate) fn parse_status_code(header_block: &[u8]) -> io::Result<u16> {
    let status_line = header_block
        .split(|byte| *byte == b'\n')
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP status line"))?;
    let status_line = std::str::from_utf8(status_line)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP status line: {error}")))?;
    let mut parts = status_line.split_whitespace();
    let _http_version =
        parts.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP version"))?;
    let status = parts.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP status code"))?;
    status.parse::<u16>().map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP status code {status}: {error}"))
    })
}

async fn resolve_first(host: &str, port: u16) -> io::Result<SocketAddr> {
    lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "NaiveProxy server resolved to no addresses"))
}

async fn read_connect_response(stream: &mut TlsStream<TcpStream>) -> io::Result<(u16, Vec<u8>)> {
    let mut buffer = Vec::with_capacity(1024);
    let mut chunk = [0u8; 512];

    loop {
        let read = stream.read(&mut chunk).await?;
        if read == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "NaiveProxy upstream closed before CONNECT response completed",
            ));
        }
        buffer.extend_from_slice(&chunk[..read]);

        if let Some(header_end) = find_header_end(&buffer) {
            let status = parse_status_code(&buffer[..header_end])?;
            let leftover = buffer[header_end..].to_vec();
            return Ok((status, leftover));
        }

        if buffer.len() > 16 * 1024 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "NaiveProxy upstream CONNECT response headers are too large",
            ));
        }
    }
}
