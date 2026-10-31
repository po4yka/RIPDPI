use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::{Context, Poll, ready};

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::StatusCode;
use rand::{Rng, RngExt};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::sync::{OwnedSemaphorePermit, mpsc};
use tokio::task::JoinHandle;

use ripdpi_vless::addons::VlessFlow;
use ripdpi_vless::wire::{ResponseHeaderStream, VlessCommand};

use crate::config::{XhttpMode, XhttpProtocolMode, normalize_path};
use crate::h2_body::{ChannelBody, build_get_request, build_post_request};
use crate::pool::PooledConnection;

const STREAM_BUFFER_SIZE: usize = 64 * 1024;
const BODY_CHUNK_SIZE: usize = 16 * 1024;
pub(crate) const HEADER_PADDING_MIN: usize = 100;
pub(crate) const HEADER_PADDING_MAX: usize = 1000;

pub struct XhttpStream {
    reader: ResponseHeaderStream<DuplexStream>,
    /// Set by the download pump when the H2 body ended with an error
    /// (RST_STREAM, GOAWAY, connection loss) instead of END_STREAM.
    download_failed: Arc<AtomicBool>,
    writer: DuplexStream,
    _permit: OwnedSemaphorePermit,
}

impl AsyncRead for XhttpStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let filled_before = buf.filled().len();
        let wants_data = buf.remaining() > 0;
        ready!(Pin::new(&mut self.reader).poll_read(cx, buf))?;
        // The pump sets the flag before it closes the pipe, so this EOF is a
        // reset of the download stream, not a graceful end.
        if wants_data && buf.filled().len() == filled_before && self.download_failed.load(Ordering::Acquire) {
            return Poll::Ready(Err(io::Error::new(io::ErrorKind::ConnectionReset, "xHTTP download stream was reset")));
        }
        Poll::Ready(Ok(()))
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

impl XhttpMode {
    /// The VLESS request that opens a TCP stream to `target`.
    pub(crate) fn tcp_request(&self, target: &str) -> io::Result<Vec<u8>> {
        Ok(ripdpi_vless::wire::encode_request(self.uuid(), self.flow().as_addons_bytes(), target)?)
    }

    /// Xray's XUDP request: the VLESS `Mux` command without a destination.
    /// Vision cannot run over xHTTP, so only the empty flow is accepted.
    pub(crate) fn xudp_request(&self) -> io::Result<Vec<u8>> {
        if self.flow() != VlessFlow::None {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "VLESS xHTTP does not support XTLS Vision flow"));
        }
        Ok(ripdpi_vless::wire::encode_command_request(self.uuid(), &[], VlessCommand::Mux, None)?)
    }
}

impl PooledConnection {
    /// cancel-safe: the permit is owned by this future and stream-open pump
    /// tasks are aborted by [`OpenStreamTaskGuard`] until a stream is returned.
    pub(crate) async fn open_stream_from_mode(
        &self,
        mode: &XhttpMode,
        request: &[u8],
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        match mode.protocol_mode() {
            XhttpProtocolMode::StreamUp => self.open_stream_up(mode, request, permit).await,
            XhttpProtocolMode::StreamOne => self.open_stream_one(mode, request, permit).await,
        }
    }

    /// NOT cancel-safe at individual write/request awaits; cancellation is made
    /// safe at the operation boundary by [`OpenStreamTaskGuard`] and permit RAII.
    ///
    /// `stream-up` wire shape: one GET (download) + one streaming POST
    /// (upload), session id embedded in the URL path. Matches xray-core's
    /// `dialer.go` stream-up branch.
    async fn open_stream_up(
        &self,
        mode: &XhttpMode,
        request: &[u8],
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        let stream_path = mode.request_path();
        let host_header = mode.host_header();
        // Xray carries request padding only in the Referer query and draws it
        // per request; X-Padding is a response header.
        let get_request = build_get_request(&stream_path, &host_header, &referer_padding(&host_header, &stream_path))?;
        let get_response = self.send_request(get_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP GET request failed: {error}"))
        })?;
        if get_response.status() != StatusCode::OK {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP GET rejected: {}", get_response.status()),
            ));
        }

        let (outgoing_tx, outgoing_rx) = mpsc::channel::<io::Result<Bytes>>(64);
        let post_request = build_post_request(
            &stream_path,
            &host_header,
            &referer_padding(&host_header, &stream_path),
            ChannelBody::new(outgoing_rx),
        )?;
        let (mut user_upload, transport_upload) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let mut open_tasks = OpenStreamTaskGuard::new();
        open_tasks.push(spawn_upload_pump(transport_upload, outgoing_tx));
        user_upload.write_all(request).await?;

        let post_response = self.send_request(post_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP POST request failed: {error}"))
        })?;
        if !post_response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP POST rejected: {}", post_response.status()),
            ));
        }

        let (transport_download, user_download) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        let download_failed = Arc::new(AtomicBool::new(false));
        open_tasks.push(spawn_download_pump(
            get_response.into_body(),
            transport_download,
            Arc::clone(&download_failed),
            "xHTTP GET stream failed",
        ));
        open_tasks.push(spawn_body_drain(post_response.into_body(), "xHTTP POST stream failed"));

        // xray-core buffers the VLESS response header until the first
        // downstream payload. Return the writable stream immediately and
        // strip that header lazily on the caller's first read.
        let stream = XhttpStream {
            reader: ResponseHeaderStream::new(user_download),
            download_failed,
            writer: user_upload,
            _permit: permit,
        };
        open_tasks.disarm();
        Ok(stream)
    }

    /// NOT cancel-safe at individual write/request awaits; cancellation is made
    /// safe at the operation boundary by [`OpenStreamTaskGuard`] and permit RAII.
    ///
    /// `stream-one` wire shape: one bidirectional HTTP/2 request — the
    /// request body carries the upload stream, the response body carries
    /// the download stream, no session id is embedded in the URL.
    /// Matches xray-core's `dialer.go` stream-one branch (upstream
    /// default for REALITY without `downloadSettings`).
    async fn open_stream_one(
        &self,
        mode: &XhttpMode,
        request: &[u8],
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        let stream_path = mode.request_path();
        let host_header = mode.host_header();
        let referer = referer_padding(&host_header, &stream_path);

        let (outgoing_tx, outgoing_rx) = mpsc::channel::<io::Result<Bytes>>(64);
        let post_request = build_post_request(&stream_path, &host_header, &referer, ChannelBody::new(outgoing_rx))?;
        let (mut user_upload, transport_upload) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let mut open_tasks = OpenStreamTaskGuard::new();
        open_tasks.push(spawn_upload_pump(transport_upload, outgoing_tx));
        user_upload.write_all(request).await?;

        let post_response = self.send_request(post_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP stream-one request failed: {error}"))
        })?;
        if !post_response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP stream-one rejected: {}", post_response.status()),
            ));
        }

        let (transport_download, user_download) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        let download_failed = Arc::new(AtomicBool::new(false));
        open_tasks.push(spawn_download_pump(
            post_response.into_body(),
            transport_download,
            Arc::clone(&download_failed),
            "xHTTP stream-one body failed",
        ));

        let stream = XhttpStream {
            reader: ResponseHeaderStream::new(user_download),
            download_failed,
            writer: user_upload,
            _permit: permit,
        };
        open_tasks.disarm();
        Ok(stream)
    }
}

struct OpenStreamTaskGuard {
    handles: Vec<JoinHandle<()>>,
    abort_on_drop: bool,
}

impl OpenStreamTaskGuard {
    fn new() -> Self {
        Self { handles: Vec::new(), abort_on_drop: true }
    }

    fn push(&mut self, handle: JoinHandle<()>) {
        self.handles.push(handle);
    }

    fn disarm(mut self) {
        self.abort_on_drop = false;
    }
}

impl Drop for OpenStreamTaskGuard {
    fn drop(&mut self) {
        if self.abort_on_drop {
            for handle in &self.handles {
                handle.abort();
            }
        }
    }
}

fn spawn_upload_pump(
    mut transport_upload: DuplexStream,
    outgoing_tx: mpsc::Sender<io::Result<Bytes>>,
) -> JoinHandle<()> {
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
    })
}

fn spawn_download_pump<B>(
    mut body: B,
    mut transport_download: DuplexStream,
    download_failed: Arc<AtomicBool>,
    error_label: &'static str,
) -> JoinHandle<()>
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
                    download_failed.store(true, Ordering::Release);
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
    })
}

fn spawn_body_drain<B>(mut body: B, error_label: &'static str) -> JoinHandle<()>
where
    B: hyper::body::Body<Data = Bytes> + Send + Unpin + 'static,
    B::Error: std::fmt::Display + Send,
{
    tokio::spawn(async move {
        while let Some(frame) = body.frame().await {
            if let Err(error) = frame {
                tracing::debug!(error = %error, "{error_label}");
                break;
            }
        }
    })
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

/// Stream-one URL path: the configured path with a trailing `/` and no
/// session segment. xray-core's `dialer.go` stream-one branch sets
/// `sessionId = ""` and writes the request to `GetNormalizedPath()`, which
/// appends `/` under the default path placement; its hub answers 404 to a
/// path without that prefix.
pub(crate) fn stream_one_path(path: &str) -> String {
    let normalized = normalize_path(path);
    if normalized == "/" { normalized } else { format!("{normalized}/") }
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

#[cfg(test)]
mod tests {
    use std::convert::Infallible;
    use std::future;
    use std::sync::Arc;
    use std::time::Duration;

    use bytes::Bytes;
    use http_body_util::{BodyExt, Full};
    use hyper::service::service_fn;
    use hyper_util::rt::{TokioExecutor, TokioIo};
    use tokio::sync::{Semaphore, oneshot};

    use super::OpenStreamTaskGuard;
    use crate::config::{XhttpMode, XhttpProtocolMode, XhttpTlsConfig};
    use crate::pool::PooledConnection;

    fn tcp_request(mode: &XhttpMode) -> Vec<u8> {
        mode.tcp_request("example.com:443").expect("VLESS request")
    }

    struct DropSignal(Option<oneshot::Sender<()>>);

    impl Drop for DropSignal {
        fn drop(&mut self) {
            if let Some(sender) = self.0.take() {
                let _ = sender.send(());
            }
        }
    }

    #[tokio::test]
    async fn open_stream_task_guard_aborts_pumps_when_stream_open_is_cancelled() {
        let (started_tx, started_rx) = oneshot::channel();
        let (dropped_tx, dropped_rx) = oneshot::channel();
        let handle = tokio::spawn(async move {
            let _drop_signal = DropSignal(Some(dropped_tx));
            let _ = started_tx.send(());
            future::pending::<()>().await;
        });
        let mut guard = OpenStreamTaskGuard::new();
        guard.push(handle);
        started_rx.await.expect("pump task started");

        drop(guard);

        tokio::time::timeout(Duration::from_secs(1), dropped_rx)
            .await
            .expect("cancelled stream open must abort owned xHTTP pump tasks")
            .expect("pump task drop signal");
    }

    #[tokio::test]
    async fn open_stream_task_guard_detaches_pumps_after_stream_is_returned() {
        let (started_tx, started_rx) = oneshot::channel();
        let (dropped_tx, dropped_rx) = oneshot::channel();
        let handle = tokio::spawn(async move {
            let _drop_signal = DropSignal(Some(dropped_tx));
            let _ = started_tx.send(());
            future::pending::<()>().await;
        });
        let mut guard = OpenStreamTaskGuard::new();
        guard.push(handle);
        started_rx.await.expect("pump task started");

        guard.disarm();

        assert!(
            tokio::time::timeout(Duration::from_millis(50), dropped_rx).await.is_err(),
            "returned xHTTP streams keep their pump tasks alive until stream halves close",
        );
    }

    /// Request heads as the server saw them, in arrival order.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: test-owned H2 tasks and duplex streams close on runtime drop.
    async fn received_request_heads(protocol_mode: XhttpProtocolMode) -> Vec<http::request::Parts> {
        let (client_io, server_io) = tokio::io::duplex(64 * 1024);
        let (sender, connection) = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
            .await
            .expect("client H2 handshake");
        let driver = tokio::spawn(async move { connection.await.expect("client H2 connection") });
        let (heads_tx, mut heads_rx) = tokio::sync::mpsc::unbounded_channel();
        let server = tokio::spawn(async move {
            let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                let heads_tx = heads_tx.clone();
                async move {
                    let (head, _body) = request.into_parts();
                    let _ = heads_tx.send(head);
                    Ok::<_, Infallible>(hyper::Response::new(Full::new(Bytes::new())))
                }
            });
            let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(server_io), service)
                .await;
        });
        let mode = XhttpMode::Tls(
            XhttpTlsConfig::from_strings(
                "192.0.2.1",
                443,
                "example.com",
                "550e8400-e29b-41d4-a716-446655440000",
                "/xhttp",
                "cdn.example.com",
                "chrome_stable",
            )
            .expect("mode")
            .with_protocol_mode(protocol_mode),
        );
        let pooled = PooledConnection::new(sender, 2);
        let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("permit");
        let _stream = pooled.open_stream_from_mode(&mode, &tcp_request(&mode), permit).await.expect("open stream");
        let expected = match protocol_mode {
            XhttpProtocolMode::StreamUp => 2,
            XhttpProtocolMode::StreamOne => 1,
        };
        let mut heads = Vec::new();
        while heads.len() < expected {
            heads.push(heads_rx.recv().await.expect("request head"));
        }
        driver.abort();
        server.abort();
        heads
    }

    #[tokio::test]
    async fn requests_carry_https_scheme_and_authority_pseudo_headers() {
        for protocol_mode in [XhttpProtocolMode::StreamUp, XhttpProtocolMode::StreamOne] {
            for head in received_request_heads(protocol_mode).await {
                assert_eq!(Some("https"), head.uri.scheme_str(), "{protocol_mode:?} {} :scheme", head.method);
                assert_eq!(
                    Some("cdn.example.com"),
                    head.uri.authority().map(http::uri::Authority::as_str),
                    "{protocol_mode:?} {} :authority",
                    head.method
                );
                assert!(!head.headers.contains_key(http::header::HOST), "{protocol_mode:?} sends a Host header");
            }
        }
    }

    /// One data frame, then a stream error such as RST_STREAM.
    struct ResetAfterFirstFrame(Option<Bytes>);

    impl hyper::body::Body for ResetAfterFirstFrame {
        type Data = Bytes;
        type Error = std::io::Error;

        fn poll_frame(
            mut self: std::pin::Pin<&mut Self>,
            _cx: &mut std::task::Context<'_>,
        ) -> std::task::Poll<Option<Result<hyper::body::Frame<Bytes>, std::io::Error>>> {
            std::task::Poll::Ready(Some(match self.0.take() {
                Some(data) => Ok(hyper::body::Frame::data(data)),
                None => Err(std::io::Error::other("stream reset by peer")),
            }))
        }
    }

    #[tokio::test]
    async fn download_reset_surfaces_as_an_error_not_a_clean_eof() {
        let (transport_download, user_download) = tokio::io::duplex(1024);
        let download_failed = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let pump = super::spawn_download_pump(
            ResetAfterFirstFrame(Some(Bytes::from_static(b"\x00\x00partial"))),
            transport_download,
            Arc::clone(&download_failed),
            "test download",
        );
        let (_user_upload, writer) = tokio::io::duplex(1024);
        let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("permit");
        let mut stream = super::XhttpStream {
            reader: ripdpi_vless::wire::ResponseHeaderStream::new(user_download),
            download_failed,
            writer,
            _permit: permit,
        };
        pump.await.expect("download pump");

        let mut received = Vec::new();
        let error = tokio::io::AsyncReadExt::read_to_end(&mut stream, &mut received)
            .await
            .expect_err("a reset download must not read as a complete body");

        assert_eq!(std::io::ErrorKind::ConnectionReset, error.kind());
        assert_eq!(b"partial", received.as_slice());
    }

    #[tokio::test]
    async fn request_padding_rides_only_in_a_fresh_referer_per_request() {
        let heads = received_request_heads(XhttpProtocolMode::StreamUp).await;

        for head in &heads {
            assert!(!head.headers.contains_key("x-padding"), "{} carries an x-padding request header", head.method);
            assert!(head.headers.contains_key(http::header::REFERER), "{} lost its padded Referer", head.method);
        }
        assert_ne!(
            heads[0].headers.get(http::header::REFERER),
            heads[1].headers.get(http::header::REFERER),
            "GET and POST must draw independent padding"
        );
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: test-owned H2 tasks and duplex streams close on runtime drop.
    async fn post_body_starts_before_server_response_in_both_modes() {
        for protocol_mode in [XhttpProtocolMode::StreamUp, XhttpProtocolMode::StreamOne] {
            let (client_io, server_io) = tokio::io::duplex(64 * 1024);
            let (sender, connection) =
                hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
                    .await
                    .expect("client H2 handshake");
            let driver = tokio::spawn(async move { connection.await.expect("client H2 connection") });
            let server = tokio::spawn(async move {
                let service = service_fn(|request: hyper::Request<hyper::body::Incoming>| async move {
                    if request.method() == hyper::Method::POST {
                        let mut body = request.into_body();
                        let frame = body.frame().await.expect("POST body frame").expect("valid POST frame");
                        assert!(!frame.into_data().expect("POST data frame").is_empty());
                    }
                    Ok::<_, Infallible>(hyper::Response::new(Full::new(Bytes::new())))
                });
                hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                    .serve_connection(TokioIo::new(server_io), service)
                    .await
                    .expect("server H2 connection");
            });
            let mode = XhttpMode::Tls(
                XhttpTlsConfig::from_strings(
                    "example.com",
                    443,
                    "example.com",
                    "550e8400-e29b-41d4-a716-446655440000",
                    "/xhttp",
                    "",
                    "chrome_stable",
                )
                .expect("mode")
                .with_protocol_mode(protocol_mode),
            );
            let pooled = PooledConnection::new(sender, 1);
            let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("permit");
            let result = tokio::time::timeout(
                Duration::from_millis(500),
                pooled.open_stream_from_mode(&mode, &tcp_request(&mode), permit),
            )
            .await;
            assert!(result.is_ok(), "{protocol_mode:?} waited for a POST response before sending its body");
            result.expect("deadline").expect("open stream");
            driver.abort();
            server.abort();
        }
    }

    /// XUDP over xHTTP, derived from Xray v26.10.29 (`proxy/vless/encoding`
    /// `EncodeRequestHeader`, `common/xudp` `PacketWriter`): the upload body
    /// carries a destination-less VLESS `Mux` request, then the first XUDP
    /// frame; the download body carries the VLESS response header and XUDP
    /// replies. Holds for both stream modes.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: test-owned H2 tasks and duplex streams close on runtime drop.
    #[tokio::test]
    async fn xudp_rides_xhttp_bodies_in_both_modes() {
        for protocol_mode in [XhttpProtocolMode::StreamUp, XhttpProtocolMode::StreamOne] {
            let (client_io, server_io) = tokio::io::duplex(64 * 1024);
            let (sender, connection) =
                hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(client_io))
                    .await
                    .expect("client H2 handshake");
            let driver = tokio::spawn(async move { connection.await.expect("client H2 connection") });
            let (down_tx, down_rx) = tokio::sync::mpsc::channel::<std::io::Result<Bytes>>(8);
            let download = Arc::new(std::sync::Mutex::new(Some(down_rx)));
            let (upload_tx, mut upload_rx) = tokio::sync::mpsc::unbounded_channel::<Bytes>();
            let server = tokio::spawn(async move {
                let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                    let download = Arc::clone(&download);
                    let upload_tx = upload_tx.clone();
                    async move {
                        let carries_download =
                            request.method() == hyper::Method::GET || protocol_mode == XhttpProtocolMode::StreamOne;
                        if request.method() == hyper::Method::POST {
                            let mut body = request.into_body();
                            tokio::spawn(async move {
                                while let Some(Ok(frame)) = body.frame().await {
                                    if let Ok(data) = frame.into_data() {
                                        let _ = upload_tx.send(data);
                                    }
                                }
                            });
                        }
                        let receiver = if carries_download {
                            download.lock().expect("download slot").take().expect("one download response")
                        } else {
                            tokio::sync::mpsc::channel(1).1
                        };
                        Ok::<_, Infallible>(hyper::Response::new(crate::h2_body::ChannelBody::new(receiver)))
                    }
                });
                let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new())
                    .serve_connection(TokioIo::new(server_io), service)
                    .await;
            });
            let mode = XhttpMode::Tls(
                XhttpTlsConfig::from_strings(
                    "192.0.2.1",
                    443,
                    "example.com",
                    "550e8400-e29b-41d4-a716-446655440000",
                    "/xhttp",
                    "",
                    "chrome_stable",
                )
                .expect("mode")
                .with_protocol_mode(protocol_mode),
            );
            let pooled = PooledConnection::new(sender, 1);
            let permit = Arc::new(Semaphore::new(1)).acquire_owned().await.expect("permit");
            let request = mode.xudp_request().expect("XUDP request");
            let stream = pooled.open_stream_from_mode(&mode, &request, permit).await.expect("open XUDP stream");
            let mut session = ripdpi_vless::VlessXudpSession::new(stream, true).expect("XUDP session");
            session.send_to("192.0.2.7:443", b"ping").await.expect("send XUDP datagram");

            let uuid = [0x55, 0x0e, 0x84, 0x00, 0xe2, 0x9b, 0x41, 0xd4, 0xa7, 0x16, 0x44, 0x66, 0x55, 0x44, 0x00, 0x00];
            let mut expected = vec![0x00];
            expected.extend_from_slice(&uuid);
            // No addons, command Mux, no destination.
            expected.extend_from_slice(&[0x00, 0x03]);
            // Metadata length 20, session 0, New, Data, UDP, port 443, IPv4 192.0.2.7.
            expected.extend_from_slice(&[0x00, 20, 0x00, 0x00, 0x01, 0x01, 0x02, 0x01, 0xbb, 0x01, 192, 0, 2, 7]);
            let global_id_at = expected.len();
            let mut upload = Vec::new();
            while upload.len() < global_id_at + 8 + 2 + 4 {
                upload.extend_from_slice(&upload_rx.recv().await.expect("upload bytes"));
            }
            assert_eq!(&upload[..global_id_at], expected.as_slice(), "{protocol_mode:?} upload prefix");
            assert_eq!(&upload[global_id_at + 8..], &[0x00, 0x04, b'p', b'i', b'n', b'g'], "{protocol_mode:?} payload");

            // VLESS response header, then a Keep frame naming the source.
            let mut reply = vec![0x00, 0x00];
            reply.extend_from_slice(&[0x00, 12, 0x00, 0x00, 0x02, 0x01, 0x02, 0x01, 0xbb, 0x01, 192, 0, 2, 7]);
            reply.extend_from_slice(&[0x00, 0x04, b'p', b'o', b'n', b'g']);
            down_tx.send(Ok(Bytes::from(reply))).await.expect("send downlink");
            let (source, payload) = session.recv_from().await.expect("receive XUDP datagram");
            assert_eq!(("192.0.2.7:443", b"pong".as_slice()), (source.as_str(), payload.as_slice()));
            drop(session);
            driver.abort();
            server.abort();
        }
    }
}
