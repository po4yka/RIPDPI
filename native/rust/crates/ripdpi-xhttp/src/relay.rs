use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::StatusCode;
use rand::{Rng, RngExt};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::sync::{OwnedSemaphorePermit, mpsc};

use ripdpi_vless::addons::VlessFlow;

use crate::config::{XhttpMode, XhttpProtocolMode, normalize_path};
use crate::h2_body::{ChannelBody, build_get_request, build_post_request};
use crate::pool::PooledConnection;

const STREAM_BUFFER_SIZE: usize = 64 * 1024;
const BODY_CHUNK_SIZE: usize = 16 * 1024;
pub(crate) const HEADER_PADDING_MIN: usize = 100;
pub(crate) const HEADER_PADDING_MAX: usize = 1000;

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
    fn request_path(&self) -> String;
    fn host_header(&self) -> String;
    fn uuid(&self) -> &[u8; 16];
    fn flow(&self) -> VlessFlow;
    fn protocol_mode(&self) -> XhttpProtocolMode;
    fn base_path(&self) -> &str;
}

impl StreamMetadata for XhttpMode {
    /// Per-request path. Stream-up embeds a fresh random session id;
    /// stream-one uses the bare configured path (xray-core sets
    /// `sessionId = ""` for stream-one in `dialer.go`).
    fn request_path(&self) -> String {
        match self.protocol_mode() {
            XhttpProtocolMode::StreamUp => stream_up_path(self.base_path(), &random_session_id()),
            XhttpProtocolMode::StreamOne => stream_one_path(self.base_path()),
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

    fn flow(&self) -> VlessFlow {
        match self {
            // Reality-tunneled xHTTP reuses the inner VLESS profile's
            // flow so the server-side selection stays consistent with
            // direct VLESS+Reality connections.
            Self::Reality(config) => config.vless.flow,
            Self::Tls(config) => config.flow,
        }
    }

    fn protocol_mode(&self) -> XhttpProtocolMode {
        match self {
            Self::Reality(config) => config.protocol_mode,
            Self::Tls(config) => config.protocol_mode,
        }
    }

    fn base_path(&self) -> &str {
        match self {
            Self::Reality(config) => &config.path,
            Self::Tls(config) => &config.path,
        }
    }
}

impl PooledConnection {
    pub(crate) async fn open_stream_from_mode(
        &self,
        mode: &XhttpMode,
        target: &str,
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        match mode.protocol_mode() {
            XhttpProtocolMode::StreamUp => self.open_stream_up(mode, target, permit).await,
            XhttpProtocolMode::StreamOne => self.open_stream_one(mode, target, permit).await,
        }
    }

    /// `stream-up` wire shape: one GET (download) + one streaming POST
    /// (upload), session id embedded in the URL path. Matches xray-core's
    /// `dialer.go` stream-up branch.
    async fn open_stream_up(
        &self,
        mode: &XhttpMode,
        target: &str,
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        let stream_path = mode.request_path();
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

        let (mut user_upload, transport_upload) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (transport_download, user_download) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        spawn_upload_pump(transport_upload, outgoing_tx);
        spawn_download_pump(get_response.into_body(), transport_download, "xHTTP GET stream failed");

        let request = ripdpi_vless::wire::encode_request(mode.uuid(), mode.flow().as_addons_bytes(), target);
        user_upload.write_all(&request).await?;

        let mut stream = XhttpStream { reader: user_download, writer: user_upload, _permit: permit };
        ripdpi_vless::wire::read_response(&mut stream).await?;
        Ok(stream)
    }

    /// `stream-one` wire shape: one bidirectional HTTP/2 request — the
    /// request body carries the upload stream, the response body carries
    /// the download stream, no session id is embedded in the URL.
    /// Matches xray-core's `dialer.go` stream-one branch (upstream
    /// default for REALITY without `downloadSettings`).
    async fn open_stream_one(
        &self,
        mode: &XhttpMode,
        target: &str,
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        let stream_path = mode.request_path();
        let host_header = mode.host_header();
        let referer = referer_padding(&host_header, &stream_path);
        let header_padding = random_padding_value();

        let (outgoing_tx, outgoing_rx) = mpsc::channel::<io::Result<Bytes>>(64);
        let post_request =
            build_post_request(&stream_path, &host_header, &referer, &header_padding, ChannelBody::new(outgoing_rx))?;

        let mut sender = self.sender.lock().await;
        let post_response = sender.send_request(post_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP stream-one request failed: {error}"))
        })?;
        drop(sender);
        if !post_response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP stream-one rejected: {}", post_response.status()),
            ));
        }

        let (mut user_upload, transport_upload) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (transport_download, user_download) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        spawn_upload_pump(transport_upload, outgoing_tx);
        spawn_download_pump(post_response.into_body(), transport_download, "xHTTP stream-one body failed");

        let request = ripdpi_vless::wire::encode_request(mode.uuid(), mode.flow().as_addons_bytes(), target);
        user_upload.write_all(&request).await?;

        let mut stream = XhttpStream { reader: user_download, writer: user_upload, _permit: permit };
        ripdpi_vless::wire::read_response(&mut stream).await?;
        Ok(stream)
    }
}

fn spawn_upload_pump(mut transport_upload: DuplexStream, outgoing_tx: mpsc::Sender<io::Result<Bytes>>) {
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
}

fn spawn_download_pump<B>(mut body: B, mut transport_download: DuplexStream, error_label: &'static str)
where
    B: hyper::body::Body<Data = Bytes> + Send + Unpin + 'static,
    B::Error: std::fmt::Display + Send,
{
    tokio::spawn(async move {
        while let Some(frame) = body.frame().await {
            let frame = match frame {
                Ok(frame) => frame,
                Err(error) => {
                    tracing::debug!(error = %error, "{error_label}");
                    break;
                }
            };
            if let Ok(data) = frame.into_data()
                && transport_download.write_all(&data).await.is_err()
            {
                break;
            }
        }
        let _ = transport_download.shutdown().await;
    });
}

pub(crate) fn random_padding_value() -> String {
    // The xHTTP discussion spec calls for a random-looking padding value.
    // Repeating a single ASCII byte is a deterministic byte-run that any
    // entropy-based DPI heuristic flags; use uppercase-hex of random bytes
    // so the resulting string still passes through a URL/Referer header
    // (no escaping needed) while being entropic byte-for-byte.
    let padding_len = rand::rng().random_range(HEADER_PADDING_MIN..=HEADER_PADDING_MAX);
    // Two hex characters per random byte, so generate ceil(len / 2) bytes
    // and truncate.
    let byte_count = padding_len.div_ceil(2);
    let mut bytes = vec![0u8; byte_count];
    rand::rng().fill_bytes(&mut bytes);
    let mut out = String::with_capacity(padding_len);
    for byte in bytes {
        out.push_str(&format!("{byte:02X}"));
        if out.len() >= padding_len {
            break;
        }
    }
    out.truncate(padding_len);
    out
}

pub(crate) fn stream_up_path(path: &str, session_id: &str) -> String {
    let normalized = normalize_path(path);
    if normalized == "/" { format!("/{session_id}") } else { format!("{normalized}/{session_id}") }
}

/// Stream-one URL path: the bare configured path. xray-core's
/// `dialer.go` stream-one branch sets `sessionId = ""` and writes the
/// request directly to `<path>`, so callers must not append a session
/// segment.
pub(crate) fn stream_one_path(path: &str) -> String {
    normalize_path(path)
}

pub(crate) fn referer_padding(host: &str, path: &str) -> String {
    let normalized = normalize_path(path);
    let padding = random_padding_value();
    format!("https://{host}{normalized}?x_padding={padding}")
}

fn random_session_id() -> String {
    let mut rng = rand::rng();
    format!("{:016x}{:016x}", rng.random::<u64>(), rng.random::<u64>())
}
