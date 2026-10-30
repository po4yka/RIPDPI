use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::task::{Context, Poll};
use std::time::Duration;

use hyper_util::rt::{TokioExecutor, TokioIo, TokioTimer};
use rand::RngExt;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio_rustls::TlsConnector;

use crate::config::NaiveProxyConfig;
use crate::h2_connect::{
    PayloadPaddingMode, build_h2_connect_request, generate_connect_padding_header,
    negotiate_payload_padding_from_response,
};
use crate::padding::{PaddingDecoder, PaddingEncoder};
use crate::session::{INITIAL_MAX_STREAMS, Sender, Session, SessionPool};
use crate::socks5::SocksTarget;

pub trait AsyncTunnel: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncTunnel for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// Opens an H2 CONNECT tunnel to `target` through the NaiveProxy server. Tunnels
/// share one pooled HTTP/2 connection, as upstream NaiveProxy does.
///
/// After a connection-level failure the open runs once more on the pool's
/// current connection. No payload is sent before the CONNECT succeeds, so
/// nothing replays; the server can see the CONNECT twice.
///
/// # Cancel safety
///
/// cancel-safe: a cancelled open drops its stream; it discards the session only
/// if that connection never answered a CONNECT, see [`DiscardOnDrop`].
pub(crate) async fn open_https_connect_tunnel(
    config: &NaiveProxyConfig,
    target: &SocksTarget,
) -> io::Result<Box<dyn AsyncTunnel>> {
    let request = || build_h2_connect_request(config, target, generate_connect_padding_header());
    let first = request()?;
    let session = config.sessions.session(|| connect_session(config)).await?;
    match open_stream(&config.sessions, session, first).await {
        Err(StreamFailure::Session(_)) => {
            let session = config.sessions.session(|| connect_session(config)).await?;
            open_stream(&config.sessions, session, request()?).await.map_err(StreamFailure::into_error)
        }
        other => other.map_err(StreamFailure::into_error),
    }
}

/// Keepalive runs while any stream is open, an unanswered CONNECT included: a
/// connection silent for the interval gets a ping, and one that misses it is
/// closed. A silently dead connection so fails an open within about 15 s of
/// its last read, which leaves the 20 s open budget room for the retry.
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(10);
const KEEPALIVE_TIMEOUT: Duration = Duration::from_secs(5);
/// One shared connection carries many tunnels, so the connection window is well
/// above hyper's 2 MiB stream window: a few tunnels whose clients stop reading
/// cannot use it all and stall the others (see the unread-streams test). It
/// also bounds the buffered bytes. shortcut: sized like Chromium's session
/// window; throughput and memory on devices are not measured yet.
const CONNECTION_WINDOW_BYTES: u32 = 16 * 1024 * 1024;

/// Why one stream open failed: the connection itself, or only this stream.
enum StreamFailure {
    /// The connection is unusable; the stream may retry on another one.
    Session(io::Error),
    /// The server answered for this stream (status, padding); the connection is fine.
    Stream(io::Error),
}

impl StreamFailure {
    fn into_error(self) -> io::Error {
        match self {
            Self::Session(error) | Self::Stream(error) => error,
        }
    }
}

/// On drop, discards a session that never answered a CONNECT: an abandoned
/// open on it is the only sign it works at all, and dropping the pool's sender
/// lets hyper close it. A verified session stays, because a slow target (a
/// server that dials before it answers) must not retire a working connection.
struct DiscardOnDrop<'a> {
    pool: &'a SessionPool,
    session: &'a Session,
}

impl Drop for DiscardOnDrop<'_> {
    fn drop(&mut self) {
        if !self.session.is_verified() {
            self.pool.discard(self.session.id);
        }
    }
}

/// Opens one CONNECT stream on `session`.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the stream (hyper resets it) and discards
/// `session` only if it is unverified, see [`DiscardOnDrop`].
async fn open_stream(
    pool: &SessionPool,
    session: Session,
    request: http::Request<http_body_util::Empty<bytes::Bytes>>,
) -> Result<Box<dyn AsyncTunnel>, StreamFailure> {
    let guard = DiscardOnDrop { pool, session: &session };
    let response = session.sender.clone().send_request(request).await.map_err(|error| {
        let message = format!("failed to send NaiveProxy H2 CONNECT: {error}");
        match classify_send_error(&error, session.sender.is_closed()) {
            SendFailure::Connection => {
                pool.discard(session.id);
                StreamFailure::Session(io::Error::new(io::ErrorKind::ConnectionAborted, message))
            }
            SendFailure::Refused => StreamFailure::Session(io::Error::new(io::ErrorKind::ConnectionRefused, message)),
            SendFailure::Stream => StreamFailure::Stream(io::Error::new(io::ErrorKind::ConnectionRefused, message)),
        }
    })?;
    session.mark_verified();
    if response.status() != http::StatusCode::OK {
        return Err(StreamFailure::Stream(connect_status_error(response.status())));
    }
    let padding_mode =
        negotiate_payload_padding_from_response(true, response.headers()).map_err(StreamFailure::Stream)?;
    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        StreamFailure::Stream(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("failed to upgrade NaiveProxy H2 CONNECT: {error}"),
        ))
    })?;
    drop(guard);
    let stream = session.into_tunnel(TokioIo::new(upgraded));
    match padding_mode {
        PayloadPaddingMode::Plain => Ok(Box::new(stream)),
        PayloadPaddingMode::Variant1 => Ok(Box::new(NaivePaddingStream::new(stream))),
    }
}

/// How a failed CONNECT send relates to its connection.
#[derive(Debug, PartialEq, Eq)]
enum SendFailure {
    /// The connection is gone (GOAWAY, I/O, closed): discard it and retry.
    Connection,
    /// The server refused the stream without processing it: retry, keep the connection.
    Refused,
    /// The server reset only this stream.
    Stream,
}

/// Classifies by the underlying HTTP/2 error, because hyper can report the
/// stream's error before its dispatcher notices that the connection closed.
fn classify_send_error(error: &hyper::Error, connection_closed: bool) -> SendFailure {
    let h2_error = std::iter::successors(std::error::Error::source(error), |source| source.source())
        .find_map(|source| source.downcast_ref::<h2::Error>());
    if let Some(failure) = h2_error.and_then(classify_h2_error) {
        return failure;
    }
    if error.is_closed() || error.is_canceled() || error.is_timeout() || connection_closed {
        SendFailure::Connection
    } else {
        SendFailure::Stream
    }
}

fn classify_h2_error(error: &h2::Error) -> Option<SendFailure> {
    if error.reason() == Some(h2::Reason::REFUSED_STREAM) {
        Some(SendFailure::Refused)
    } else if error.is_go_away() || error.is_io() {
        Some(SendFailure::Connection)
    } else if error.is_reset() {
        Some(SendFailure::Stream)
    } else {
        None
    }
}

/// The HTTP/2 client settings every upstream connection uses.
fn h2_client_builder() -> hyper::client::conn::http2::Builder<TokioExecutor> {
    let mut builder = hyper::client::conn::http2::Builder::new(TokioExecutor::new());
    builder
        .timer(TokioTimer::new())
        .keep_alive_interval(KEEPALIVE_INTERVAL)
        .keep_alive_timeout(KEEPALIVE_TIMEOUT)
        .initial_connection_window_size(CONNECTION_WINDOW_BYTES);
    builder
}

/// Drives a client connection's dispatcher and publishes the server's current
/// stream limit, which hyper exposes only on the connection future.
fn drive_connection<T>(
    mut connection: hyper::client::conn::http2::Connection<T, http_body_util::Empty<bytes::Bytes>, TokioExecutor>,
) -> Arc<AtomicUsize>
where
    T: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
{
    let max_streams = Arc::new(AtomicUsize::new(INITIAL_MAX_STREAMS));
    let published = Arc::clone(&max_streams);
    // This dispatcher ends when the pool and every open stream drop their senders.
    tokio::spawn(async move {
        let result = std::future::poll_fn(|cx| {
            let poll = Pin::new(&mut connection).poll(cx);
            // Refreshed when the dispatcher wakes (a dispatch, readiness), not on
            // every SETTINGS frame. Relaxed: a stale limit only spills a stream
            // early or queues it briefly.
            published.store(connection.current_max_send_streams(), Ordering::Relaxed);
            poll
        })
        .await;
        if let Err(error) = result {
            eprintln!("{}", crate::errors::connection_failure_line(&io::Error::other(error)));
        }
    });
    max_streams
}

/// Connects TCP, TLS and HTTP/2 to the server and starts the request dispatcher.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the partial TCP/TLS/HTTP/2 connection;
/// nothing awaits after the dispatcher starts.
async fn connect_session(config: &NaiveProxyConfig) -> io::Result<(Sender, Arc<AtomicUsize>)> {
    // The protected connector tries every resolved address; TLS keeps `server_name`.
    let addresses = resolve_upstream(config).await?;
    let tcp =
        ripdpi_subprocess_protect::protected_tcp_connect(addresses.as_slice(), config.protect_socket_path.as_deref())
            .await?;
    tcp.set_nodelay(true)?;

    let server_name = ServerName::try_from(config.server_name.clone()).map_err(|error| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid NaiveProxy server name {}: {error}", config.server_name),
        )
    })?;
    let connector = TlsConnector::from(Arc::clone(&config.tls_config));
    let tls = connector.connect(server_name, tcp).await?;
    // This helper speaks only H2: never send an H2 preface after an HTTP/1.1 or no-ALPN handshake.
    if tls.get_ref().1.alpn_protocol() != Some(b"h2".as_slice()) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "NaiveProxy server TLS did not negotiate h2 ALPN"));
    }

    let (sender, connection) = h2_client_builder().handshake(TokioIo::new(tls)).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate NaiveProxy H2: {error}"))
    })?;
    // hyper's own connection task owns the socket; this future is only the dispatcher.
    Ok((sender, drive_connection(connection)))
}

/// Server addresses for the upstream dial. While the VPN protects sockets, a
/// hostname goes through protected DoH like the in-process relays do, so the
/// proxy name never leaves as plain DNS on the underlying network.
///
/// # Cancel safety
///
/// cancel-safe: see [`ripdpi_vless::endpoint_resolver::resolve_server_addrs`].
async fn resolve_upstream(config: &NaiveProxyConfig) -> io::Result<Vec<SocketAddr>> {
    let protect = config.protect_socket_path.clone().map(|path| {
        Box::new(move |fd| ripdpi_subprocess_protect::protect_fd(fd, &path))
            as Box<ripdpi_vless::endpoint_resolver::SocketProtectFn>
    });
    ripdpi_vless::endpoint_resolver::resolve_server_addrs(&config.server, config.server_port, None, protect).await
}

/// Only 407 is a proxy-authentication failure; any other non-200 is a CONNECT rejection.
pub(crate) fn connect_status_error(status: http::StatusCode) -> io::Error {
    let kind = if status == http::StatusCode::PROXY_AUTHENTICATION_REQUIRED {
        io::ErrorKind::PermissionDenied
    } else {
        io::ErrorKind::Other
    };
    io::Error::new(kind, format!("NaiveProxy upstream rejected CONNECT with status {status}"))
}

struct NaivePaddingStream<S> {
    inner: S,
    decoder: PaddingDecoder,
    encoder: PaddingEncoder,
    pending_read: Vec<u8>,
    pending_write: Vec<u8>,
    pending_write_offset: usize,
}

impl<S> NaivePaddingStream<S> {
    fn new(inner: S) -> Self {
        Self {
            inner,
            decoder: PaddingDecoder::default(),
            encoder: PaddingEncoder::default(),
            pending_read: Vec::new(),
            pending_write: Vec::new(),
            pending_write_offset: 0,
        }
    }
}

impl<S> AsyncRead for NaivePaddingStream<S>
where
    S: AsyncRead + Unpin,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if !self.pending_read.is_empty() {
            let copy_len = buf.remaining().min(self.pending_read.len());
            let drained = self.pending_read.drain(..copy_len).collect::<Vec<_>>();
            buf.put_slice(&drained);
            return Poll::Ready(Ok(()));
        }

        loop {
            let mut raw = [0u8; 8192];
            let mut raw_buf = ReadBuf::new(&mut raw);
            match Pin::new(&mut self.inner).poll_read(cx, &mut raw_buf) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Ready(Ok(())) => {
                    let filled = raw_buf.filled();
                    if filled.is_empty() {
                        return Poll::Ready(Ok(()));
                    }
                    let mut decoded = Vec::new();
                    self.decoder.decode(filled, &mut decoded);
                    if decoded.is_empty() {
                        continue;
                    }
                    let copy_len = buf.remaining().min(decoded.len());
                    buf.put_slice(&decoded[..copy_len]);
                    if copy_len < decoded.len() {
                        self.pending_read.extend_from_slice(&decoded[copy_len..]);
                    }
                    return Poll::Ready(Ok(()));
                }
            }
        }
    }
}

impl<S> AsyncWrite for NaivePaddingStream<S>
where
    S: AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if buf.is_empty() {
            return Poll::Ready(Ok(0));
        }
        match self.as_mut().poll_drain_pending(cx) {
            Poll::Pending => return Poll::Pending,
            Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
            Poll::Ready(Ok(())) => {}
        }

        let padding_size = rand::rng().random::<u8>();
        let mut encoded = Vec::new();
        let consumed = self.encoder.encode_with_padding_size(buf, padding_size, &mut encoded);
        self.pending_write = encoded;
        self.pending_write_offset = 0;
        // The bytes are now owned by this buffered writer. A Pending result from
        // the inner writer must not make a later poll_write acknowledge stale input.
        if let Poll::Ready(Err(error)) = self.as_mut().poll_drain_pending(cx) {
            return Poll::Ready(Err(error));
        }
        Poll::Ready(Ok(consumed))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.as_mut().poll_drain_pending(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
            Poll::Ready(Ok(())) => Pin::new(&mut self.inner).poll_flush(cx),
        }
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.as_mut().poll_flush(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
            Poll::Ready(Ok(())) => Pin::new(&mut self.inner).poll_shutdown(cx),
        }
    }
}

impl<S: AsyncWrite + Unpin> NaivePaddingStream<S> {
    fn poll_drain_pending(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        while self.pending_write_offset < self.pending_write.len() {
            let this = self.as_mut().get_mut();
            let chunk = &this.pending_write[this.pending_write_offset..];
            let written = match Pin::new(&mut this.inner).poll_write(cx, chunk) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Ready(Ok(0)) => return Poll::Ready(Err(io::ErrorKind::WriteZero.into())),
                Poll::Ready(Ok(written)) => written,
            };
            self.pending_write_offset += written;
        }
        self.pending_write.clear();
        self.pending_write_offset = 0;
        Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use std::pin::Pin;
    use std::task::Poll;

    use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt};

    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::NaivePaddingStream;
    use crate::padding::PaddingDecoder;

    #[test]
    fn http2_errors_classify_by_their_scope() {
        use super::{SendFailure, classify_h2_error};

        let refused = h2::Error::from(h2::Reason::REFUSED_STREAM);
        let cancelled = h2::Error::from(h2::Reason::CANCEL);
        assert_eq!(classify_h2_error(&refused), Some(SendFailure::Refused));
        // A bare reason is neither a reset nor a GOAWAY: hyper's own flags decide.
        assert_eq!(classify_h2_error(&cancelled), None);
    }

    /// The pool's limit follows the server's SETTINGS_MAX_CONCURRENT_STREAMS,
    /// so a server that allows fewer than the default 100 streams gets no more.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns both pipe ends and the connection.
    #[tokio::test]
    async fn server_stream_limit_reaches_the_pool() {
        use bytes::Bytes;
        use http_body_util::Empty;
        use hyper_util::rt::{TokioExecutor, TokioIo};

        let (client_io, server_io) = tokio::io::duplex(64 * 1024);
        tokio::spawn(async move {
            let service = hyper::service::service_fn(|_request: http::Request<hyper::body::Incoming>| async {
                Ok::<_, std::convert::Infallible>(http::Response::new(Empty::<Bytes>::new()))
            });
            let mut server = hyper::server::conn::http2::Builder::new(TokioExecutor::new());
            server.max_concurrent_streams(2);
            let _ = server.serve_connection(TokioIo::new(server_io), service).await;
        });
        let (sender, connection) = super::h2_client_builder().handshake(TokioIo::new(client_io)).await.expect("h2");
        let max_streams = super::drive_connection(connection);
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        while max_streams.load(Ordering::Relaxed) != 2 {
            assert!(tokio::time::Instant::now() < deadline, "the server's SETTINGS limit never reached the pool");
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }

        let pool = crate::session::SessionPool::default();
        let connect = || {
            let result = (sender.clone(), Arc::clone(&max_streams));
            async move { Ok(result) }
        };
        let first = pool.session(connect).await.expect("first");
        let second = pool.session(connect).await.expect("second");
        assert_eq!(first.id, second.id);
        let third = pool.session(connect).await.expect("third");
        assert_ne!(third.id, first.id, "a stream past the server's limit goes to another connection");
    }

    /// Three streams whose readers stop fill their 2 MiB stream windows. With
    /// hyper's default 5 MB connection window that would stall every other
    /// stream on the connection; the helper's window leaves a sibling moving.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns both pipe ends and every stream.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn unread_streams_do_not_stall_a_sibling_on_the_shared_connection() {
        use bytes::Bytes;
        use http_body_util::Empty;
        use hyper_util::rt::{TokioExecutor, TokioIo};

        let (client_io, server_io) = tokio::io::duplex(1024 * 1024);
        let filled = Arc::new(AtomicUsize::new(0));
        let server_filled = Arc::clone(&filled);
        tokio::spawn(async move {
            let service = hyper::service::service_fn(move |request: http::Request<hyper::body::Incoming>| {
                let filled = Arc::clone(&server_filled);
                async move {
                    let fill = request.uri().host() == Some("fill.test");
                    let upgrade = hyper::upgrade::on(request);
                    tokio::spawn(async move {
                        let Ok(upgraded) = upgrade.await else { return };
                        let mut stream = TokioIo::new(upgraded);
                        if fill {
                            // More than one stream window in 64 KiB writes; the client never reads it.
                            let chunk = vec![0_u8; 64 * 1024];
                            while stream.write_all(&chunk).await.is_ok() {
                                filled.fetch_add(chunk.len(), Ordering::Relaxed);
                            }
                            std::future::pending::<()>().await;
                        }
                        let mut buffer = [0_u8; 64];
                        while let Ok(read @ 1..) = stream.read(&mut buffer).await {
                            if stream.write_all(&buffer[..read]).await.is_err() {
                                break;
                            }
                        }
                    });
                    Ok::<_, std::convert::Infallible>(http::Response::new(Empty::<Bytes>::new()))
                }
            });
            let mut server = hyper::server::conn::http2::Builder::new(TokioExecutor::new());
            server.enable_connect_protocol().initial_connection_window_size(64 * 1024 * 1024);
            let _ = server.serve_connection(TokioIo::new(server_io), service).await;
        });
        let (mut sender, connection) = super::h2_client_builder().handshake(TokioIo::new(client_io)).await.expect("h2");
        drop(super::drive_connection(connection));
        let connect = |host: &str| {
            http::Request::builder()
                .method(http::Method::CONNECT)
                .uri(format!("{host}:443"))
                .body(Empty::<Bytes>::new())
                .expect("CONNECT request")
        };

        let mut unread = Vec::new();
        for _ in 0..3 {
            let response = sender.send_request(connect("fill.test")).await.expect("fill CONNECT");
            unread.push(hyper::upgrade::on(response).await.expect("fill upgrade"));
        }
        // Wait until the unread streams hold 5 MiB, hyper's default connection
        // window: with that window the sibling below would get no data.
        let full = 5 * 1024 * 1024;
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);
        while filled.load(Ordering::Relaxed) < full {
            assert!(tokio::time::Instant::now() < deadline, "the unread streams never filled their windows");
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        let response = sender.send_request(connect("echo.test")).await.expect("echo CONNECT");
        let mut sibling = TokioIo::new(hyper::upgrade::on(response).await.expect("echo upgrade"));

        let echoed = tokio::time::timeout(std::time::Duration::from_secs(3), async {
            sibling.write_all(b"sibling").await.expect("write sibling");
            let mut echoed = [0_u8; 7];
            sibling.read_exact(&mut echoed).await.expect("read sibling");
            echoed
        })
        .await
        .expect("unread streams must not stall a sibling");
        assert_eq!(&echoed, b"sibling");
        drop(unread);
    }

    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns its socket and channel resources.
    async fn pending_inner_write_acknowledges_only_owned_input() {
        let (inner, mut peer) = tokio::io::duplex(1);
        let mut writer = NaivePaddingStream::new(inner);
        std::future::poll_fn(|cx| {
            assert!(matches!(Pin::new(&mut writer).poll_write(cx, b"A"), Poll::Ready(Ok(1))));
            Poll::Ready(())
        })
        .await;

        let reader = tokio::spawn(async move {
            let mut wire = Vec::new();
            peer.read_to_end(&mut wire).await.expect("read wire bytes");
            wire
        });
        writer.write_all(b"B").await.expect("write next payload");
        writer.shutdown().await.expect("flush and shut down");
        let wire = reader.await.expect("reader task");
        let mut decoded = Vec::new();
        PaddingDecoder::default().decode(&wire, &mut decoded);
        assert_eq!(decoded, b"AB");
    }
}
