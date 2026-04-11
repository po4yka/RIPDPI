#![forbid(unsafe_code)]

use std::collections::HashMap;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::Once;

use base64::prelude::{Engine as _, BASE64_STANDARD};
use rustls::pki_types::ServerName;
use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};
use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::{lookup_host, TcpListener, TcpStream};
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

static RUSTLS_PROVIDER: Once = Once::new();

#[derive(Clone)]
struct NaiveProxyConfig {
    listen: String,
    server: String,
    server_port: u16,
    server_name: String,
    username: Option<String>,
    password: Option<String>,
    path: Option<String>,
    tls_config: Arc<RustlsClientConfig>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum SocksTarget {
    Domain(String, u16),
    Ip(SocketAddr),
}

impl SocksTarget {
    fn authority(&self) -> String {
        match self {
            Self::Domain(host, port) => format!("{host}:{port}"),
            Self::Ip(address) => address.to_string(),
        }
    }
}

struct BufferedTlsStream {
    upstream: TlsStream<TcpStream>,
    pending_read: Vec<u8>,
}

impl BufferedTlsStream {
    fn new(upstream: TlsStream<TcpStream>, pending_read: Vec<u8>) -> Self {
        Self { upstream, pending_read }
    }
}

impl AsyncRead for BufferedTlsStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> std::task::Poll<io::Result<()>> {
        if !self.pending_read.is_empty() {
            let copy_len = buf.remaining().min(self.pending_read.len());
            let drained = self.pending_read.drain(..copy_len).collect::<Vec<_>>();
            buf.put_slice(&drained);
            return std::task::Poll::Ready(Ok(()));
        }
        std::pin::Pin::new(&mut self.upstream).poll_read(cx, buf)
    }
}

impl AsyncWrite for BufferedTlsStream {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<io::Result<usize>> {
        std::pin::Pin::new(&mut self.upstream).poll_write(cx, buf)
    }

    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<io::Result<()>> {
        std::pin::Pin::new(&mut self.upstream).poll_flush(cx)
    }

    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<io::Result<()>> {
        std::pin::Pin::new(&mut self.upstream).poll_shutdown(cx)
    }
}

fn parse_args() -> HashMap<String, String> {
    let mut parsed = HashMap::new();
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        if !flag.starts_with("--") {
            continue;
        }
        let value = args.next().unwrap_or_default();
        parsed.insert(flag.trim_start_matches("--").to_owned(), value);
    }
    parsed
}

fn default_tls_config() -> Arc<RustlsClientConfig> {
    ensure_rustls_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    Arc::new(RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth())
}

fn ensure_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}

fn parse_config(args: HashMap<String, String>) -> io::Result<NaiveProxyConfig> {
    let listen = args.get("listen").cloned().unwrap_or_else(|| "127.0.0.1:11980".to_owned());
    let server_value = args
        .get("server")
        .cloned()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --server"))?;
    let (server, server_port) = parse_server_endpoint(&server_value, args.get("server-port"))?;
    let server_name = args
        .get("server-name")
        .cloned()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --server-name"))?;
    let username = normalize_optional(args.get("username"));
    let password = normalize_optional(args.get("password"));
    if username.is_some() ^ password.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "NaiveProxy requires both username and password when authentication is configured",
        ));
    }

    Ok(NaiveProxyConfig {
        listen,
        server,
        server_port,
        server_name,
        username,
        password,
        path: normalize_optional(args.get("path")),
        tls_config: default_tls_config(),
    })
}

fn normalize_optional(value: Option<&String>) -> Option<String> {
    value.and_then(|raw| {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_owned())
        }
    })
}

fn parse_server_endpoint(server_value: &str, server_port: Option<&String>) -> io::Result<(String, u16)> {
    if let Some(port) = server_port {
        return Ok((server_value.trim().to_owned(), parse_u16(Some(port), "--server-port")?));
    }

    if let Ok(address) = server_value.parse::<SocketAddr>() {
        return Ok((address.ip().to_string(), address.port()));
    }

    if let Some((host, port)) = split_host_port(server_value) {
        return Ok((host.to_owned(), port));
    }

    Err(io::Error::new(
        io::ErrorKind::InvalidInput,
        "NaiveProxy requires --server-port when --server is not a host:port authority",
    ))
}

fn parse_u16(value: Option<&String>, flag: &str) -> io::Result<u16> {
    let raw = value
        .map(|entry| entry.trim())
        .filter(|entry| !entry.is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("missing {flag}")))?;
    raw.parse::<u16>()
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid {flag} value {raw}: {error}")))
}

fn split_host_port(authority: &str) -> Option<(&str, u16)> {
    let (host, port) = authority.rsplit_once(':')?;
    let parsed_port = port.parse::<u16>().ok()?;
    if host.is_empty() {
        return None;
    }
    Some((host.trim_matches(['[', ']']), parsed_port))
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> io::Result<()> {
    let config = parse_config(parse_args())?;
    run(config).await
}

async fn run(config: NaiveProxyConfig) -> io::Result<()> {
    let listener = TcpListener::bind(&config.listen).await?;
    let config = Arc::new(config);

    loop {
        let (socket, _) = listener.accept().await?;
        let config = Arc::clone(&config);
        tokio::spawn(async move {
            if let Err(error) = handle_client(socket, config).await {
                eprintln!("naiveproxy connection failed: {error}");
            }
        });
    }
}

async fn handle_client(mut client: TcpStream, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    negotiate_socks5(&mut client).await?;
    let target = read_socks5_request(&mut client).await?;

    let upstream = match open_https_connect_tunnel(&config, &target).await {
        Ok(stream) => stream,
        Err(error) => {
            write_socks_reply(&mut client, 0x01).await?;
            return Err(error);
        }
    };

    write_socks_reply(&mut client, 0x00).await?;
    let mut upstream = BufferedTlsStream::new(upstream.0, upstream.1);
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

async fn negotiate_socks5(client: &mut TcpStream) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS version"));
    }
    let method_count = usize::from(greeting[1]);
    let mut methods = vec![0u8; method_count];
    client.read_exact(&mut methods).await?;
    if methods.contains(&0x00) {
        client.write_all(&[0x05, 0x00]).await?;
        Ok(())
    } else {
        client.write_all(&[0x05, 0xff]).await?;
        Err(io::Error::new(io::ErrorKind::Unsupported, "client does not support unauthenticated SOCKS5"))
    }
}

async fn read_socks5_request(client: &mut TcpStream) -> io::Result<SocksTarget> {
    let mut header = [0u8; 4];
    client.read_exact(&mut header).await?;
    if header[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS request version"));
    }
    if header[1] != 0x01 {
        return Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported SOCKS command {:#x}", header[1])));
    }
    if header[2] != 0x00 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS reserved byte"));
    }

    match header[3] {
        0x01 => {
            let mut address = [0u8; 4];
            client.read_exact(&mut address).await?;
            let port = read_port(client).await?;
            Ok(SocksTarget::Ip(SocketAddr::from((address, port))))
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).await?;
            let mut host = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut host).await?;
            let port = read_port(client).await?;
            let host = String::from_utf8(host)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid SOCKS host: {error}")))?;
            Ok(SocksTarget::Domain(host, port))
        }
        0x04 => {
            let mut address = [0u8; 16];
            client.read_exact(&mut address).await?;
            let port = read_port(client).await?;
            Ok(SocksTarget::Ip(SocketAddr::from((address, port))))
        }
        atyp => Err(io::Error::new(io::ErrorKind::Unsupported, format!("unsupported SOCKS address type {atyp:#x}"))),
    }
}

async fn read_port(client: &mut TcpStream) -> io::Result<u16> {
    let mut bytes = [0u8; 2];
    client.read_exact(&mut bytes).await?;
    Ok(u16::from_be_bytes(bytes))
}

async fn write_socks_reply(client: &mut TcpStream, reply: u8) -> io::Result<()> {
    let response = [0x05, reply, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
    client.write_all(&response).await
}

async fn open_https_connect_tunnel(
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

async fn resolve_first(host: &str, port: u16) -> io::Result<SocketAddr> {
    lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "NaiveProxy server resolved to no addresses"))
}

fn build_connect_request(config: &NaiveProxyConfig, target: &SocksTarget) -> String {
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

fn find_header_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|window| window == b"\r\n\r\n").map(|index| index + 4)
}

fn parse_status_code(header_block: &[u8]) -> io::Result<u16> {
    let status_line = header_block
        .split(|byte| *byte == b'\n')
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP status line"))?;
    let status_line = std::str::from_utf8(status_line)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP status line: {error}")))?;
    let mut parts = status_line.trim().split_whitespace();
    let _http_version =
        parts.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP version"))?;
    let status = parts.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP status code"))?;
    status.parse::<u16>().map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidData, format!("invalid HTTP status code {status}: {error}"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    use rcgen::{CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    use rustls::ServerConfig as RustlsServerConfig;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio::sync::oneshot;
    use tokio_rustls::TlsAcceptor;

    #[tokio::test]
    async fn socks5_tunnel_round_trip_reaches_target_via_https_proxy() {
        let target = start_echo_server().await;
        let (proxy_config, proxy_auth_seen) = start_test_https_proxy("naive-user", "naive-pass").await;
        let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
        let local_addr = local_listener.local_addr().expect("local addr");
        let mut config = proxy_config;
        config.listen = local_addr.to_string();

        let server = tokio::spawn(async move {
            serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
        });

        let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
        client.write_all(&[0x05, 0x01, 0x00]).await.expect("write greeting");
        let mut auth_reply = [0u8; 2];
        client.read_exact(&mut auth_reply).await.expect("read greeting reply");
        assert_eq!(auth_reply, [0x05, 0x00]);

        let connect_request = build_socks_connect_request("127.0.0.1", target.port());
        client.write_all(&connect_request).await.expect("write connect request");
        let mut connect_reply = [0u8; 10];
        client.read_exact(&mut connect_reply).await.expect("read connect reply");
        assert_eq!(connect_reply[1], 0x00);

        client.write_all(b"ping-through-naive").await.expect("write tunneled payload");
        let mut echoed = vec![0u8; "ping-through-naive".len()];
        client.read_exact(&mut echoed).await.expect("read echo");
        assert_eq!(&echoed, b"ping-through-naive");

        let seen_auth = proxy_auth_seen.await.expect("proxy auth seen");
        assert_eq!(seen_auth.as_deref(), Some("Basic bmFpdmUtdXNlcjpuYWl2ZS1wYXNz"));

        server.abort();
    }

    #[test]
    fn parse_status_code_accepts_200_response() {
        let status = parse_status_code(b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n")
            .expect("parse status");
        assert_eq!(status, 200);
    }

    #[test]
    fn build_connect_request_emits_basic_auth_header() {
        let config = NaiveProxyConfig {
            listen: "127.0.0.1:11980".to_owned(),
            server: "proxy.example".to_owned(),
            server_port: 443,
            server_name: "proxy.example".to_owned(),
            username: Some("user".to_owned()),
            password: Some("pass".to_owned()),
            path: Some("/proxy".to_owned()),
            tls_config: default_tls_config(),
        };

        let request = build_connect_request(&config, &SocksTarget::Domain("example.com".to_owned(), 443));

        assert!(request.contains("CONNECT example.com:443 HTTP/1.1"));
        assert!(request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz"));
        assert!(request.contains("X-Naive-Path: /proxy"));
    }

    async fn serve_listener(listener: TcpListener, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
        loop {
            let (socket, _) = listener.accept().await?;
            let config = Arc::clone(&config);
            tokio::spawn(async move {
                let _ = handle_client(socket, config).await;
            });
        }
    }

    async fn start_echo_server() -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind echo");
        let address = listener.local_addr().expect("echo addr");
        tokio::spawn(async move {
            loop {
                let Ok((mut socket, _)) = listener.accept().await else {
                    return;
                };
                tokio::spawn(async move {
                    let mut buf = [0u8; 2048];
                    loop {
                        let Ok(read) = socket.read(&mut buf).await else {
                            return;
                        };
                        if read == 0 {
                            return;
                        }
                        if socket.write_all(&buf[..read]).await.is_err() {
                            return;
                        }
                    }
                });
            }
        });
        address
    }

    async fn start_test_https_proxy(
        expected_username: &str,
        expected_password: &str,
    ) -> (NaiveProxyConfig, oneshot::Receiver<Option<String>>) {
        ensure_rustls_provider();
        let key_pair = KeyPair::generate().expect("generate keypair");
        let mut params = CertificateParams::new(vec!["proxy.test".to_owned()]).expect("params");
        params.is_ca = IsCa::NoCa;
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, "proxy.test");
        params.distinguished_name = dn;
        let cert = params.self_signed(&key_pair).expect("self sign");
        let cert_der = cert.der().clone();
        let key_der = key_pair.serialize_der();

        let mut roots = RootCertStore::empty();
        roots.add(cert_der.clone()).expect("add root");
        let client_tls = Arc::new(RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth());

        let server_config = RustlsServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(cert_der)],
                PrivateKeyDer::from(PrivatePkcs8KeyDer::from(key_der)),
            )
            .expect("server cert");
        let acceptor = TlsAcceptor::from(Arc::new(server_config));
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind proxy");
        let address = listener.local_addr().expect("proxy addr");
        let (auth_tx, auth_rx) = oneshot::channel();
        let expected_auth =
            format!("Basic {}", BASE64_STANDARD.encode(format!("{expected_username}:{expected_password}")));

        tokio::spawn(async move {
            let (socket, _) = listener.accept().await.expect("accept proxy");
            let mut tls = acceptor.accept(socket).await.expect("tls accept");
            let request = read_http_headers(&mut tls).await.expect("read connect request");
            let auth_header = extract_header(&request, "proxy-authorization");
            auth_tx.send(auth_header.clone()).ok();
            assert_eq!(auth_header.as_deref(), Some(expected_auth.as_str()));
            assert!(request.contains("CONNECT 127.0.0.1:"));

            let target = extract_connect_authority(&request).expect("target authority");
            let mut upstream = TcpStream::connect(target).await.expect("connect target");
            tls.write_all(b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n")
                .await
                .expect("write proxy reply");
            let _ = copy_bidirectional(&mut tls, &mut upstream).await;
        });

        (
            NaiveProxyConfig {
                listen: "127.0.0.1:0".to_owned(),
                server: "127.0.0.1".to_owned(),
                server_port: address.port(),
                server_name: "proxy.test".to_owned(),
                username: Some(expected_username.to_owned()),
                password: Some(expected_password.to_owned()),
                path: Some("/".to_owned()),
                tls_config: client_tls,
            },
            auth_rx,
        )
    }

    async fn read_http_headers<S>(stream: &mut S) -> io::Result<String>
    where
        S: AsyncRead + Unpin,
    {
        let mut buffer = Vec::new();
        let mut chunk = [0u8; 256];
        loop {
            let read = stream.read(&mut chunk).await?;
            if read == 0 {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "eof before headers"));
            }
            buffer.extend_from_slice(&chunk[..read]);
            if find_header_end(&buffer).is_some() {
                return String::from_utf8(buffer)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
            }
        }
    }

    fn extract_header(request: &str, name: &str) -> Option<String> {
        request.lines().find_map(|line| {
            let (key, value) = line.split_once(':')?;
            if key.trim().eq_ignore_ascii_case(name) {
                Some(value.trim().to_owned())
            } else {
                None
            }
        })
    }

    fn extract_connect_authority(request: &str) -> Option<String> {
        request.lines().next().and_then(|line| {
            let mut parts = line.split_whitespace();
            let method = parts.next()?;
            if method != "CONNECT" {
                return None;
            }
            parts.next().map(ToOwned::to_owned)
        })
    }

    fn build_socks_connect_request(host: &str, port: u16) -> Vec<u8> {
        let mut request = vec![0x05, 0x01, 0x00, 0x01];
        let address = host.parse::<Ipv4Addr>().expect("test helper only supports ipv4 literals");
        request.extend_from_slice(&address.octets());
        request.extend_from_slice(&port.to_be_bytes());
        request
    }
}
