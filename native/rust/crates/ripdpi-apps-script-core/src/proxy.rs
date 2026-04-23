use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tokio::task::JoinSet;
use tokio::time::timeout;
use tokio_rustls::rustls::server::Acceptor;
use tokio_rustls::{LazyConfigAcceptor, TlsAcceptor};

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::socks5::{read_target, write_reply};
use crate::telemetry::SharedTelemetryState;
use crate::AppsScriptRuntimeConfig;

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const FIRST_BYTES_TIMEOUT: Duration = Duration::from_millis(300);

pub struct ProxyServer {
    config: AppsScriptRuntimeConfig,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
}

impl ProxyServer {
    pub fn new(config: AppsScriptRuntimeConfig, telemetry: SharedTelemetryState) -> io::Result<Self> {
        let mitm = MitmCertManager::new_in(&config.data_dir).map_err(|error| io::Error::other(error.to_string()))?;
        Ok(Self {
            relay: Arc::new(AppsScriptDomainFronter::new(&config)),
            mitm: Arc::new(Mutex::new(mitm)),
            config,
            telemetry,
        })
    }

    pub async fn run(&self, stop_requested: Arc<AtomicBool>) -> io::Result<()> {
        let listener = TcpListener::bind(self.config.listener_address()).await?;
        let local_address = listener.local_addr()?.to_string();
        self.telemetry.mark_listener_bound(local_address.clone());
        tracing::info!(
            ring = "relay",
            subsystem = "relay",
            source = "relay",
            kind = "runtime_ready",
            "Apps Script relay listener started addr={local_address}"
        );

        let mut tasks = JoinSet::new();
        while !stop_requested.load(Ordering::Relaxed) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _peer))) => {
                    let relay = self.relay.clone();
                    let mitm = self.mitm.clone();
                    let telemetry = self.telemetry.clone();
                    let hosts = self.config.hosts.clone();
                    tasks.spawn(async move {
                        let _ = handle_socks5_client(stream, relay, mitm, telemetry, hosts).await;
                    });
                }
                Ok(Err(error)) => {
                    self.telemetry.record_error(format!("listener accept failed: {error}"));
                    return Err(error);
                }
                Err(_) => {}
            }
        }

        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
        tracing::info!(
            ring = "relay",
            subsystem = "relay",
            source = "relay",
            kind = "runtime_stopped",
            "Apps Script relay listener stopped"
        );
        Ok(())
    }
}

async fn handle_socks5_client(
    mut stream: TcpStream,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: std::collections::HashMap<String, String>,
) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Ok(());
    }

    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    stream.read_exact(&mut methods).await?;
    if !methods.contains(&0x00) {
        stream.write_all(&[0x05, 0xff]).await?;
        return Ok(());
    }
    stream.write_all(&[0x05, 0x00]).await?;

    let mut request = [0u8; 4];
    stream.read_exact(&mut request).await?;
    if request[0] != 0x05 {
        return Ok(());
    }
    if request[1] != 0x01 {
        write_reply(&mut stream, 0x07, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
        return Ok(());
    }

    let target = match read_target(&mut stream, request[3]).await {
        Ok(target) => target,
        Err(error) => {
            telemetry.record_error(format!("invalid SOCKS5 target: {error}"));
            write_reply(&mut stream, 0x08, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
            return Ok(());
        }
    };
    telemetry.record_target(&target.to_string());
    telemetry.session_opened();

    let result = async {
        write_reply(&mut stream, 0x00, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
        dispatch_tunnel(stream, &target.host(), target.port(), relay, mitm, telemetry.clone(), hosts).await
    }
    .await;

    telemetry.session_closed();
    result
}

async fn dispatch_tunnel(
    stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: std::collections::HashMap<String, String>,
) -> io::Result<()> {
    if hosts_override(&hosts, host).is_some() {
        return do_sni_rewrite_tunnel(stream, host, port, mitm, telemetry, hosts).await;
    }

    let mut peek = [0u8; 8];
    let peeked = match timeout(FIRST_BYTES_TIMEOUT, stream.peek(&mut peek)).await {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(error)) => return Err(error),
        Err(_) => {
            plain_tcp_passthrough(stream, host, port).await?;
            return Ok(());
        }
    };

    if peeked >= 1 && peek[0] == 0x16 {
        run_mitm_then_relay(stream, host, port, relay, mitm, telemetry).await?;
        return Ok(());
    }

    if peeked > 0 && looks_like_http(&peek[..peeked]) {
        relay_http_stream_raw(stream, host, port, relay, telemetry).await?;
        return Ok(());
    }

    plain_tcp_passthrough(stream, host, port).await
}

async fn plain_tcp_passthrough(mut inbound: TcpStream, host: &str, port: u16) -> io::Result<()> {
    let outbound = TcpStream::connect((host, port)).await?;
    outbound.set_nodelay(true)?;
    inbound.set_nodelay(true)?;
    let mut outbound = outbound;
    let _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await?;
    Ok(())
}

async fn run_mitm_then_relay(
    stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    let acceptor = LazyConfigAcceptor::new(Acceptor::default(), stream)
        .await
        .map_err(|error| io::Error::other(format!("client hello sniff failed: {error}")))?;
    let sni_host = acceptor.client_hello().server_name().filter(|value| !looks_like_ip(value)).map(ToOwned::to_owned);
    let effective_host = sni_host.as_deref().unwrap_or(host).to_string();
    telemetry.record_target(&format!("{effective_host}:{port}"));

    let server_config = {
        let mut manager = mitm.lock().await;
        manager.get_server_config(&effective_host).map_err(|error| io::Error::other(error.to_string()))?
    };
    let mut tls_stream = acceptor
        .into_stream(server_config)
        .await
        .map_err(|error| io::Error::other(format!("TLS accept failed: {error}")))?;

    loop {
        match handle_http_request(&mut tls_stream, &effective_host, port, "https", relay.as_ref()).await? {
            true => continue,
            false => return Ok(()),
        }
    }
}

async fn relay_http_stream_raw(
    mut stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    telemetry.record_target(&format!("{host}:{port}"));
    let scheme = if port == 443 { "https" } else { "http" };
    loop {
        match handle_http_request(&mut stream, host, port, scheme, relay.as_ref()).await? {
            true => continue,
            false => return Ok(()),
        }
    }
}

async fn do_sni_rewrite_tunnel(
    stream: TcpStream,
    host: &str,
    port: u16,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: std::collections::HashMap<String, String>,
) -> io::Result<()> {
    let Some(upstream) = hosts_override(&hosts, host) else {
        return plain_tcp_passthrough(stream, host, port).await;
    };

    let server_config = {
        let mut manager = mitm.lock().await;
        manager.get_server_config(host).map_err(|error| io::Error::other(error.to_string()))?
    };
    let inbound = TlsAcceptor::from(server_config)
        .accept(stream)
        .await
        .map_err(|error| io::Error::other(format!("TLS MITM accept failed: {error}")))?;
    let mut outbound = TcpStream::connect((upstream, port)).await?;
    let _ = outbound.set_nodelay(true);
    let mut inbound = inbound;
    let _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await?;
    telemetry.record_success(0);
    Ok(())
}

async fn handle_http_request<S>(
    stream: &mut S,
    host: &str,
    port: u16,
    scheme: &str,
    relay: &AppsScriptDomainFronter,
) -> io::Result<bool>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let Some((head, leftover)) = read_http_head(stream).await? else {
        return Ok(false);
    };
    let Some((method, path, _version, headers)) = parse_request_head(&head) else {
        return Ok(false);
    };
    let body = read_body(stream, &leftover, &headers).await?;

    if method.eq_ignore_ascii_case("OPTIONS") {
        let origin = header_value(&headers, "origin").unwrap_or("*");
        let request_method = header_value(&headers, "access-control-request-method")
            .unwrap_or("GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD");
        let request_headers = header_value(&headers, "access-control-request-headers").unwrap_or("*");
        let response = format!(
            "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: {origin}\r\nAccess-Control-Allow-Methods: {request_method}\r\nAccess-Control-Allow-Headers: {request_headers}\r\nAccess-Control-Allow-Credentials: true\r\nAccess-Control-Max-Age: 86400\r\nContent-Length: 0\r\n\r\n"
        );
        stream.write_all(response.as_bytes()).await?;
        stream.flush().await?;
        let close = header_value(&headers, "connection").is_some_and(|value| value.eq_ignore_ascii_case("close"));
        return Ok(!close);
    }

    let default_port = if scheme == "https" { 443 } else { 80 };
    let url = if path.starts_with("http://") || path.starts_with("https://") {
        path.clone()
    } else if port == default_port {
        format!("{scheme}://{host}{path}")
    } else {
        format!("{scheme}://{host}:{port}{path}")
    };
    let response = relay.relay(&method, &url, &headers, &body).await;
    stream.write_all(&response).await?;
    stream.flush().await?;
    let close = header_value(&headers, "connection").is_some_and(|value| value.eq_ignore_ascii_case("close"));
    Ok(!close)
}

async fn read_http_head<S>(stream: &mut S) -> io::Result<Option<(Vec<u8>, Vec<u8>)>>
where
    S: AsyncRead + Unpin,
{
    let mut buffer = Vec::with_capacity(4_096);
    let mut scratch = [0u8; 4_096];
    loop {
        let read = stream.read(&mut scratch).await?;
        if read == 0 {
            return if buffer.is_empty() {
                Ok(None)
            } else {
                Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF mid-header"))
            };
        }
        buffer.extend_from_slice(&scratch[..read]);
        if let Some(position) = find_headers_end(&buffer) {
            let head = buffer[..position].to_vec();
            let leftover = buffer[position..].to_vec();
            return Ok(Some((head, leftover)));
        }
        if buffer.len() > 1024 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "headers too large"));
        }
    }
}

fn parse_request_head(head: &[u8]) -> Option<(String, String, String, Vec<(String, String)>)> {
    let head = std::str::from_utf8(head).ok()?;
    let mut lines = head.split("\r\n");
    let first_line = lines.next()?;
    let mut parts = first_line.splitn(3, ' ');
    let method = parts.next()?.to_string();
    let target = parts.next()?.to_string();
    let version = parts.next().unwrap_or("HTTP/1.1").to_string();
    if !matches!(
        method.as_str(),
        "GET" | "POST" | "PUT" | "DELETE" | "HEAD" | "OPTIONS" | "PATCH" | "TRACE" | "CONNECT"
    ) {
        return None;
    }
    let headers = lines
        .take_while(|line| !line.is_empty())
        .filter_map(|line| line.split_once(':').map(|(key, value)| (key.trim().to_string(), value.trim().to_string())))
        .collect::<Vec<_>>();
    Some((method, target, version, headers))
}

async fn read_body<S>(stream: &mut S, leftover: &[u8], headers: &[(String, String)]) -> io::Result<Vec<u8>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let transfer_encoding = header_value(headers, "transfer-encoding");
    let is_chunked = transfer_encoding
        .map(|value| value.split(',').any(|part| part.trim().eq_ignore_ascii_case("chunked")))
        .unwrap_or(false);
    let content_length = header_value(headers, "content-length")
        .map(|value| value.parse::<usize>())
        .transpose()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid Content-Length"))?;

    if transfer_encoding.is_some() && !is_chunked {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported Transfer-Encoding"));
    }
    if is_chunked && content_length.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "request contains both chunked transfer-encoding and content-length",
        ));
    }

    if expects_100_continue(headers) && (is_chunked || content_length.is_some()) {
        stream.write_all(b"HTTP/1.1 100 Continue\r\n\r\n").await?;
        stream.flush().await?;
    }

    if is_chunked {
        return read_chunked_request_body(stream, leftover.to_vec()).await;
    }

    let Some(content_length) = content_length else {
        return Ok(Vec::new());
    };
    let mut body = Vec::with_capacity(content_length);
    body.extend_from_slice(&leftover[..leftover.len().min(content_length)]);
    let mut scratch = [0u8; 8_192];
    while body.len() < content_length {
        let read = stream.read(&mut scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF mid-body"));
        }
        let needed = content_length - body.len();
        body.extend_from_slice(&scratch[..read.min(needed)]);
    }
    Ok(body)
}

async fn read_chunked_request_body<S>(stream: &mut S, mut buffer: Vec<u8>) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    let mut output = Vec::new();
    let mut scratch = [0u8; 8_192];

    loop {
        let line = read_crlf_line(stream, &mut buffer, &mut scratch).await?;
        if line.is_empty() {
            continue;
        }
        let line = std::str::from_utf8(&line)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid chunk size line"))?
            .trim()
            .to_string();
        let size = usize::from_str_radix(line.split(';').next().unwrap_or_default(), 16)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid chunk size"))?;
        if size == 0 {
            loop {
                if read_crlf_line(stream, &mut buffer, &mut scratch).await?.is_empty() {
                    return Ok(output);
                }
            }
        }
        fill_buffer(stream, &mut buffer, &mut scratch, size + 2).await?;
        output.extend_from_slice(&buffer[..size]);
        buffer.drain(..size + 2);
    }
}

async fn read_crlf_line<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8]) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    loop {
        if let Some(position) = buffer.windows(2).position(|window| window == b"\r\n") {
            let line = buffer[..position].to_vec();
            buffer.drain(..position + 2);
            return Ok(line);
        }
        let read = stream.read(scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF in chunked body"));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
}

async fn fill_buffer<S>(stream: &mut S, buffer: &mut Vec<u8>, scratch: &mut [u8], wanted: usize) -> io::Result<()>
where
    S: AsyncRead + Unpin,
{
    while buffer.len() < wanted {
        let read = stream.read(scratch).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF in chunked body"));
        }
        buffer.extend_from_slice(&scratch[..read]);
    }
    Ok(())
}

fn find_headers_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n").map(|position| position + 4)
}

fn header_value<'a>(headers: &'a [(String, String)], name: &str) -> Option<&'a str> {
    headers.iter().find(|(key, _)| key.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
}

fn expects_100_continue(headers: &[(String, String)]) -> bool {
    header_value(headers, "expect")
        .map(|value| value.split(',').any(|part| part.trim().eq_ignore_ascii_case("100-continue")))
        .unwrap_or(false)
}

fn looks_like_http(bytes: &[u8]) -> bool {
    ["GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT ", "TRACE "]
        .iter()
        .any(|method| bytes.starts_with(method.as_bytes()))
}

fn looks_like_ip(value: &str) -> bool {
    value.parse::<IpAddr>().is_ok()
}

fn hosts_override<'a>(hosts: &'a std::collections::HashMap<String, String>, host: &str) -> Option<&'a str> {
    let host = host.to_ascii_lowercase();
    if let Some(address) = hosts.get(&host) {
        return Some(address.as_str());
    }
    let parts: Vec<&str> = host.split('.').collect();
    for start in 1..parts.len() {
        let parent = parts[start..].join(".");
        if let Some(address) = hosts.get(&parent) {
            return Some(address.as_str());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{duplex, AsyncWriteExt};

    #[tokio::test(flavor = "current_thread")]
    async fn read_chunked_request_body_decodes_chunks() {
        let (mut writer, mut reader) = duplex(256);
        let task = tokio::spawn(async move {
            writer.write_all(b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").await.expect("write chunked body");
        });

        let body = read_chunked_request_body(&mut reader, Vec::new()).await.expect("decode body");
        task.await.expect("writer task");
        assert_eq!(body, b"hello world");
    }
}
