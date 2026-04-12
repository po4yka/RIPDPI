#![forbid(unsafe_code)]

mod finalmask;

use std::io;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};

use bytes::Bytes;
use http::header::{CONTENT_TYPE, HOST, REFERER};
use http::Request;
use http_body_util::{BodyExt, Empty};
use hyper::body::{Body, Frame};
use hyper::client::conn::http2;
use hyper::StatusCode;
use hyper_util::rt::{TokioExecutor, TokioIo};
use rand::Rng;
use ripdpi_vless::config::VlessRealityConfig;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::{mpsc, Mutex, OwnedSemaphorePermit, Semaphore};

const STREAM_BUFFER_SIZE: usize = 64 * 1024;
const BODY_CHUNK_SIZE: usize = 16 * 1024;
const HEADER_PADDING_MIN: usize = 100;
const HEADER_PADDING_MAX: usize = 1000;
const DEFAULT_XMUX_MAX_CONNECTIONS: usize = 8;
const DEFAULT_XMUX_MAX_CONCURRENT_STREAMS: usize = 32;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

type XhttpBody = http_body_util::combinators::BoxBody<Bytes, io::Error>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FinalmaskConfig {
    pub r#type: String,
    pub header_hex: String,
    pub trailer_hex: String,
    pub rand_range: String,
    pub sudoku_seed: String,
    pub fragment_packets: i32,
    pub fragment_min_bytes: i32,
    pub fragment_max_bytes: i32,
}

impl Default for FinalmaskConfig {
    fn default() -> Self {
        Self {
            r#type: "off".to_string(),
            header_hex: String::new(),
            trailer_hex: String::new(),
            rand_range: String::new(),
            sudoku_seed: String::new(),
            fragment_packets: 0,
            fragment_min_bytes: 0,
            fragment_max_bytes: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct XmuxConfig {
    pub max_connections: usize,
    pub max_concurrent_streams: usize,
}

impl Default for XmuxConfig {
    fn default() -> Self {
        Self {
            max_connections: DEFAULT_XMUX_MAX_CONNECTIONS,
            max_concurrent_streams: DEFAULT_XMUX_MAX_CONCURRENT_STREAMS,
        }
    }
}

#[derive(Debug, Clone)]
pub struct XhttpRealityConfig {
    pub vless: VlessRealityConfig,
    pub path: String,
    pub host: Option<String>,
    pub bind_ip: Option<IpAddr>,
    pub xmux: XmuxConfig,
    pub finalmask: FinalmaskConfig,
}

#[derive(Debug, Clone)]
pub struct XhttpTlsConfig {
    pub server: String,
    pub port: u16,
    pub server_name: String,
    pub uuid: [u8; 16],
    pub path: String,
    pub host: Option<String>,
    pub bind_ip: Option<IpAddr>,
    pub tls_fingerprint_profile: String,
    pub xmux: XmuxConfig,
    pub finalmask: FinalmaskConfig,
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),
    #[error("invalid port: {0}")]
    InvalidPort(i32),
}

#[derive(Clone)]
pub struct XhttpClient {
    inner: Arc<XhttpClientInner>,
}

struct XhttpClientInner {
    mode: XhttpMode,
    max_connections: usize,
    max_concurrent_streams: usize,
    state: Mutex<PoolState>,
}

struct PoolState {
    connections: Vec<Arc<PooledConnection>>,
    creating_connections: usize,
}

struct PooledConnection {
    sender: Mutex<http2::SendRequest<XhttpBody>>,
    permits: Arc<Semaphore>,
    closed: AtomicBool,
}

#[derive(Debug, Clone)]
enum XhttpMode {
    Reality(XhttpRealityConfig),
    Tls(XhttpTlsConfig),
}

pub async fn connect_reality(config: &XhttpRealityConfig, target: &str) -> io::Result<impl AsyncIo> {
    XhttpClient::new_reality(config.clone()).connect(target).await
}

pub async fn connect_tls(config: &XhttpTlsConfig, target: &str) -> io::Result<impl AsyncIo> {
    XhttpClient::new_tls(config.clone()).connect(target).await
}

impl XhttpClient {
    pub fn new_reality(config: XhttpRealityConfig) -> Self {
        Self::new(XhttpMode::Reality(config.clone()), config.xmux)
    }

    pub fn new_tls(config: XhttpTlsConfig) -> Self {
        Self::new(XhttpMode::Tls(config.clone()), config.xmux.clone())
    }

    pub async fn connect(&self, target: &str) -> io::Result<XhttpStream> {
        let (connection, permit) = self.acquire_connection().await?;
        connection.open_stream_from_mode(&self.inner.mode, target, permit).await
    }

    fn new(mode: XhttpMode, xmux: XmuxConfig) -> Self {
        Self {
            inner: Arc::new(XhttpClientInner {
                mode,
                max_connections: xmux.max_connections.max(1),
                max_concurrent_streams: xmux.max_concurrent_streams.max(1),
                state: Mutex::new(PoolState { connections: Vec::new(), creating_connections: 0 }),
            }),
        }
    }

    async fn acquire_connection(&self) -> io::Result<(Arc<PooledConnection>, OwnedSemaphorePermit)> {
        loop {
            if let Some((connection, permit)) = self.try_acquire_existing().await {
                return Ok((connection, permit));
            }

            let should_create = {
                let mut state = self.inner.state.lock().await;
                state.connections.retain(|connection| !connection.is_closed());
                if state.connections.len() + state.creating_connections < self.inner.max_connections {
                    state.creating_connections += 1;
                    true
                } else {
                    false
                }
            };

            if should_create {
                match self.create_connection().await {
                    Ok(connection) => {
                        let permit = connection
                            .permits
                            .clone()
                            .try_acquire_owned()
                            .map_err(|_| io::Error::other("xHTTP connection created without stream capacity"))?;
                        let mut state = self.inner.state.lock().await;
                        state.creating_connections = state.creating_connections.saturating_sub(1);
                        state.connections.push(connection.clone());
                        return Ok((connection, permit));
                    }
                    Err(error) => {
                        let mut state = self.inner.state.lock().await;
                        state.creating_connections = state.creating_connections.saturating_sub(1);
                        if state.connections.is_empty() {
                            return Err(error);
                        }
                    }
                }
            }

            let waiter = {
                let state = self.inner.state.lock().await;
                state
                    .connections
                    .iter()
                    .find(|connection| !connection.is_closed())
                    .map(|connection| (connection.clone(), connection.permits.clone()))
            };
            let Some((connection, permits)) = waiter else {
                tokio::task::yield_now().await;
                continue;
            };
            let permit =
                permits.acquire_owned().await.map_err(|_| io::Error::other("xHTTP stream permit channel closed"))?;
            if connection.is_closed() {
                drop(permit);
                continue;
            }
            return Ok((connection, permit));
        }
    }

    async fn try_acquire_existing(&self) -> Option<(Arc<PooledConnection>, OwnedSemaphorePermit)> {
        let state = self.inner.state.lock().await;
        state.connections.iter().find_map(|connection| {
            if connection.is_closed() {
                return None;
            }
            connection.permits.clone().try_acquire_owned().ok().map(|permit| (connection.clone(), permit))
        })
    }

    async fn create_connection(&self) -> io::Result<Arc<PooledConnection>> {
        let io = match &self.inner.mode {
            XhttpMode::Reality(config) => {
                let transport = finalmask::wrap_tcp_stream(
                    connect_tcp_stream(&config.vless.server, config.vless.port, config.bind_ip).await?,
                    &config.finalmask,
                )?;
                let tls = ripdpi_vless::reality::connect_reality_tls_over(transport, &config.vless).await?;
                TokioIo::new(tls)
            }
            XhttpMode::Tls(config) => {
                let transport = finalmask::wrap_tcp_stream(
                    connect_tcp_stream(&config.server, config.port, config.bind_ip).await?,
                    &config.finalmask,
                )?;
                let connector = ripdpi_tls_profiles::build_connector(&config.tls_fingerprint_profile, true)
                    .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
                let ssl = connector.configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
                let tls = tokio_boring::connect(ssl, &config.server_name, transport).await.map_err(|error| {
                    io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP TLS handshake: {error}"))
                })?;
                TokioIo::new(tls)
            }
        };

        let (sender, connection) = http2::handshake(TokioExecutor::new(), io).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP H2 handshake: {error}"))
        })?;

        let pooled = Arc::new(PooledConnection {
            sender: Mutex::new(sender),
            permits: Arc::new(Semaphore::new(self.inner.max_concurrent_streams)),
            closed: AtomicBool::new(false),
        });
        let pooled_for_task = pooled.clone();
        tokio::spawn(async move {
            if let Err(error) = connection.await {
                tracing::debug!(error = %error, "xHTTP H2 connection closed");
            }
            pooled_for_task.closed.store(true, Ordering::SeqCst);
            pooled_for_task.permits.close();
        });
        Ok(pooled)
    }
}

impl XhttpTlsConfig {
    pub fn from_strings(
        server: &str,
        port: i32,
        server_name: &str,
        uuid: &str,
        path: &str,
        host: &str,
        tls_fingerprint_profile: &str,
    ) -> Result<Self, ConfigError> {
        let port = u16::try_from(port).map_err(|_| ConfigError::InvalidPort(port))?;
        Ok(Self {
            server: server.to_owned(),
            port,
            server_name: server_name.to_owned(),
            uuid: parse_uuid(uuid).map_err(|_| ConfigError::InvalidUuid(uuid.to_owned()))?,
            path: normalize_path(path),
            host: if host.trim().is_empty() { None } else { Some(host.trim().to_owned()) },
            bind_ip: None,
            tls_fingerprint_profile: tls_fingerprint_profile.to_owned(),
            xmux: XmuxConfig::default(),
            finalmask: FinalmaskConfig::default(),
        })
    }
}

impl PooledConnection {
    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }
}

fn build_get_request(
    path: &str,
    host_header: &str,
    referer: &str,
    header_padding: &str,
) -> io::Result<Request<XhttpBody>> {
    let request = Request::builder()
        .method("GET")
        .uri(path)
        .header(HOST, host_header)
        .header(REFERER, referer)
        .header("x-padding", header_padding)
        .body(Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP GET request build: {error}")))?;
    Ok(request)
}

fn build_post_request(
    path: &str,
    host_header: &str,
    referer: &str,
    header_padding: &str,
    body: ChannelBody,
) -> io::Result<Request<XhttpBody>> {
    let request = Request::builder()
        .method("POST")
        .uri(path)
        .header(HOST, host_header)
        .header(REFERER, referer)
        .header("x-padding", header_padding)
        .header(CONTENT_TYPE, "application/grpc")
        .body(body.boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP POST request build: {error}")))?;
    Ok(request)
}

fn random_session_id() -> String {
    let mut rng = rand::rng();
    format!("{:016x}{:016x}", rng.random::<u64>(), rng.random::<u64>())
}

fn random_padding_value() -> String {
    let padding_len = rand::rng().random_range(HEADER_PADDING_MIN..=HEADER_PADDING_MAX);
    "X".repeat(padding_len)
}

fn normalize_path(path: &str) -> String {
    let trimmed = path.trim().trim_matches('/');
    if trimmed.is_empty() {
        "/".to_owned()
    } else {
        format!("/{}", trimmed)
    }
}

fn stream_up_path(path: &str, session_id: &str) -> String {
    let normalized = normalize_path(path);
    if normalized == "/" {
        format!("/{}", session_id)
    } else {
        format!("{normalized}/{session_id}")
    }
}

fn referer_padding(host: &str, path: &str) -> String {
    let normalized = normalize_path(path);
    let padding = random_padding_value();
    format!("https://{host}{normalized}?x_padding={padding}")
}

fn parse_uuid(value: &str) -> Result<[u8; 16], ()> {
    let hex_only: String = value.chars().filter(|character| *character != '-').collect();
    if hex_only.len() != 32 {
        return Err(());
    }
    let bytes = hex::decode(&hex_only).map_err(|_| ())?;
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}

async fn connect_tcp_stream(server: &str, port: u16, bind_ip: Option<IpAddr>) -> io::Result<TcpStream> {
    let target = resolve_server_addr(server, port, bind_ip)?;
    let socket = match target {
        SocketAddr::V4(_) => TcpSocket::new_v4()?,
        SocketAddr::V6(_) => TcpSocket::new_v6()?,
    };
    if let Some(bind_ip) = bind_ip {
        let bind_addr = match (bind_ip, target) {
            (IpAddr::V4(ip), SocketAddr::V4(_)) => SocketAddr::new(IpAddr::V4(ip), 0),
            (IpAddr::V6(ip), SocketAddr::V6(_)) => SocketAddr::new(IpAddr::V6(ip), 0),
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "outbound bind IP family does not match xHTTP server address family",
                ));
            }
        };
        socket.bind(bind_addr)?;
    }
    let stream = socket.connect(target).await?;
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn resolve_server_addr(server: &str, port: u16, bind_ip: Option<IpAddr>) -> io::Result<SocketAddr> {
    let mut candidates = (server, port)
        .to_socket_addrs()
        .map_err(|error| io::Error::new(error.kind(), format!("resolve {server}:{port}: {error}")))?;
    if let Some(bind_ip) = bind_ip {
        candidates.find(|address| address.is_ipv4() == bind_ip.is_ipv4()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "xHTTP server has no address matching outbound bind IP family")
        })
    } else {
        candidates
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "xHTTP server resolved to no addresses"))
    }
}

struct ChannelBody {
    receiver: mpsc::Receiver<io::Result<Bytes>>,
}

impl ChannelBody {
    fn new(receiver: mpsc::Receiver<io::Result<Bytes>>) -> Self {
        Self { receiver }
    }
}

impl Body for ChannelBody {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        match self.receiver.poll_recv(cx) {
            Poll::Ready(Some(Ok(bytes))) => Poll::Ready(Some(Ok(Frame::data(bytes)))),
            Poll::Ready(Some(Err(error))) => Poll::Ready(Some(Err(error))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}

pub struct XhttpStream {
    reader: DuplexStream,
    writer: DuplexStream,
    _permit: OwnedSemaphorePermit,
}

impl AsyncRead for XhttpStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.reader).poll_read(cx, buf)
    }
}

impl AsyncWrite for XhttpStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<Result<usize, io::Error>> {
        Pin::new(&mut self.writer).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        Pin::new(&mut self.writer).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        Pin::new(&mut self.writer).poll_shutdown(cx)
    }
}

trait StreamMetadata {
    fn session_path(&self) -> String;
    fn host_header(&self) -> String;
    fn uuid(&self) -> &[u8; 16];
}

impl StreamMetadata for XhttpMode {
    fn session_path(&self) -> String {
        match self {
            Self::Reality(config) => stream_up_path(&config.path, &random_session_id()),
            Self::Tls(config) => stream_up_path(&config.path, &random_session_id()),
        }
    }

    fn host_header(&self) -> String {
        match self {
            Self::Reality(config) => config.host.clone().unwrap_or_else(|| config.vless.server_name.clone()),
            Self::Tls(config) => config.host.clone().unwrap_or_else(|| config.server_name.clone()),
        }
    }

    fn uuid(&self) -> &[u8; 16] {
        match self {
            Self::Reality(config) => &config.vless.uuid,
            Self::Tls(config) => &config.uuid,
        }
    }
}

impl PooledConnection {
    async fn open_stream_from_mode(
        &self,
        mode: &XhttpMode,
        target: &str,
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        let stream_path = mode.session_path();
        let host_header = mode.host_header();
        let referer = referer_padding(&host_header, &stream_path);
        let header_padding = random_padding_value();

        let mut sender = self.sender.lock().await;
        let get_request = build_get_request(&stream_path, &host_header, &referer, &header_padding)?;
        let get_response = sender.send_request(get_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP GET request failed: {error}"))
        })?;
        if get_response.status() != StatusCode::OK {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP GET rejected: {}", get_response.status()),
            ));
        }

        let (outgoing_tx, outgoing_rx) = mpsc::channel::<io::Result<Bytes>>(64);
        let post_request =
            build_post_request(&stream_path, &host_header, &referer, &header_padding, ChannelBody::new(outgoing_rx))?;
        let post_response = sender.send_request(post_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP POST request failed: {error}"))
        })?;
        drop(sender);
        if !post_response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP POST rejected: {}", post_response.status()),
            ));
        }

        let (mut user_upload, mut transport_upload) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (mut transport_download, user_download) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        tokio::spawn(async move {
            let mut buffer = vec![0u8; BODY_CHUNK_SIZE];
            loop {
                match transport_upload.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => {
                        if outgoing_tx.send(Ok(Bytes::copy_from_slice(&buffer[..read]))).await.is_err() {
                            break;
                        }
                    }
                    Err(error) => {
                        let _ = outgoing_tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });

        tokio::spawn(async move {
            let mut body = get_response.into_body();
            while let Some(frame) = body.frame().await {
                let frame = match frame {
                    Ok(frame) => frame,
                    Err(error) => {
                        tracing::debug!(error = %error, "xHTTP GET stream failed");
                        break;
                    }
                };
                if let Ok(data) = frame.into_data() {
                    if transport_download.write_all(&data).await.is_err() {
                        break;
                    }
                }
            }
            let _ = transport_download.shutdown().await;
        });

        let request = ripdpi_vless::wire::encode_request(mode.uuid(), &ripdpi_vless::addons::VISION_ADDONS, target);
        user_upload.write_all(&request).await?;

        let mut stream = XhttpStream { reader: user_download, writer: user_upload, _permit: permit };
        ripdpi_vless::wire::read_response(&mut stream).await?;
        Ok(stream)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tls_config_normalizes_path_host_and_xmux_defaults() {
        let config = XhttpTlsConfig::from_strings(
            "edge.example",
            443,
            "edge.example",
            "550e8400-e29b-41d4-a716-446655440000",
            "api/v1/stream/",
            "origin.example",
            "chrome_stable",
        )
        .expect("config");

        assert_eq!("/api/v1/stream", config.path);
        assert_eq!(Some("origin.example".to_owned()), config.host);
        assert_eq!(XmuxConfig::default(), config.xmux);
    }

    #[test]
    fn stream_up_path_appends_session_id() {
        assert_eq!("/api/v1/stream/session123", stream_up_path("/api/v1/stream", "session123"));
        assert_eq!("/session123", stream_up_path("/", "session123"));
    }

    #[test]
    fn referer_padding_uses_expected_range() {
        let referer = referer_padding("cdn.example", "/api/v1/stream");
        let (_, padding) = referer.split_once("x_padding=").expect("padding");
        assert!((HEADER_PADDING_MIN..=HEADER_PADDING_MAX).contains(&padding.len()));
        assert!(padding.chars().all(|character| character == 'X'));
    }

    #[test]
    fn random_padding_value_uses_expected_range() {
        let value = random_padding_value();
        assert!((HEADER_PADDING_MIN..=HEADER_PADDING_MAX).contains(&value.len()));
        assert!(value.chars().all(|character| character == 'X'));
    }
}
