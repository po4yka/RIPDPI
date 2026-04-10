#![forbid(unsafe_code)]

use std::io;
use std::pin::Pin;
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
use tokio::sync::mpsc;

const STREAM_BUFFER_SIZE: usize = 64 * 1024;
const BODY_CHUNK_SIZE: usize = 16 * 1024;
const HEADER_PADDING_MIN: usize = 100;
const HEADER_PADDING_MAX: usize = 1000;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

#[derive(Debug, Clone)]
pub struct XhttpRealityConfig {
    pub vless: VlessRealityConfig,
    pub path: String,
    pub host: Option<String>,
}

#[derive(Debug, Clone)]
pub struct XhttpTlsConfig {
    pub server: String,
    pub port: u16,
    pub server_name: String,
    pub uuid: [u8; 16],
    pub path: String,
    pub host: Option<String>,
    pub tls_fingerprint_profile: String,
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),
    #[error("invalid port: {0}")]
    InvalidPort(i32),
}

pub async fn connect_reality(config: &XhttpRealityConfig, target: &str) -> io::Result<impl AsyncIo> {
    let tls = ripdpi_vless::reality::connect_reality_tls(
        tokio::net::TcpStream::connect(format!("{}:{}", config.vless.server, config.vless.port)).await?,
        &config.vless,
    )
    .await?;
    connect_inner(
        TokioIo::new(tls),
        &config.vless.server_name,
        &config.path,
        config.host.as_deref(),
        &config.vless.uuid,
        target,
    )
    .await
}

pub async fn connect_tls(config: &XhttpTlsConfig, target: &str) -> io::Result<impl AsyncIo> {
    let tcp = tokio::net::TcpStream::connect(format!("{}:{}", config.server, config.port)).await?;
    tcp.set_nodelay(true)?;
    let connector = ripdpi_tls_profiles::build_connector(&config.tls_fingerprint_profile, true)
        .map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
    let ssl = connector.configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
    let tls = tokio_boring::connect(ssl, &config.server_name, tcp)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP TLS handshake: {error}")))?;
    connect_inner(TokioIo::new(tls), &config.server_name, &config.path, config.host.as_deref(), &config.uuid, target)
        .await
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
            tls_fingerprint_profile: tls_fingerprint_profile.to_owned(),
        })
    }
}

async fn connect_inner<T>(
    io: TokioIo<T>,
    authority_host: &str,
    path: &str,
    host_override: Option<&str>,
    uuid: &[u8; 16],
    target: &str,
) -> io::Result<impl AsyncIo>
where
    T: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    type XhttpBody = http_body_util::combinators::BoxBody<Bytes, io::Error>;

    let (mut sender, connection): (http2::SendRequest<XhttpBody>, _) = http2::handshake(TokioExecutor::new(), io)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP H2 handshake: {error}")))?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            tracing::debug!(error = %error, "xHTTP H2 connection closed");
        }
    });

    let session_id = random_session_id();
    let stream_path = stream_up_path(path, &session_id);
    let host_header = host_override.unwrap_or(authority_host);
    let referer = referer_padding(host_header, path);

    let get_request = build_get_request(&stream_path, host_header, &referer)?;
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
    let post_request = build_post_request(&stream_path, host_header, &referer, ChannelBody::new(outgoing_rx))?;
    let post_response = sender.send_request(post_request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP POST request failed: {error}"))
    })?;
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

    let request = ripdpi_vless::wire::encode_request(uuid, &ripdpi_vless::addons::VISION_ADDONS, target);
    user_upload.write_all(&request).await?;

    let mut stream = XhttpStream { reader: user_download, writer: user_upload };
    ripdpi_vless::wire::read_response(&mut stream).await?;
    Ok(stream)
}

fn build_get_request(
    path: &str,
    host_header: &str,
    referer: &str,
) -> io::Result<Request<http_body_util::combinators::BoxBody<Bytes, io::Error>>> {
    let request = Request::builder()
        .method("GET")
        .uri(path)
        .header(HOST, host_header)
        .header(REFERER, referer)
        .body(Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP GET request build: {error}")))?;
    Ok(request)
}

fn build_post_request(
    path: &str,
    host_header: &str,
    referer: &str,
    body: ChannelBody,
) -> io::Result<Request<http_body_util::combinators::BoxBody<Bytes, io::Error>>> {
    let request = Request::builder()
        .method("POST")
        .uri(path)
        .header(HOST, host_header)
        .header(REFERER, referer)
        .header(CONTENT_TYPE, "application/grpc")
        .body(body.boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP POST request build: {error}")))?;
    Ok(request)
}

fn random_session_id() -> String {
    let mut rng = rand::rng();
    format!("{:016x}{:016x}", rng.random::<u64>(), rng.random::<u64>())
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
    let padding_len = rand::rng().random_range(HEADER_PADDING_MIN..=HEADER_PADDING_MAX);
    let padding = "X".repeat(padding_len);
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

struct XhttpStream {
    reader: DuplexStream,
    writer: DuplexStream,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tls_config_normalizes_path_and_host() {
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
}
