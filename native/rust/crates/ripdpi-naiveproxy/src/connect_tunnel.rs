use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
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
use crate::session::{Sender, Session, SessionPool};
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
/// cannot use it all and stall the others. It also bounds the buffered bytes.
/// shortcut: sized like Chromium's session window, not measured on devices;
/// tune after a throughput and memory comparison.
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
    let _guard = DiscardOnDrop { pool, session: &session };
    let response = session.sender.clone().send_request(request).await.map_err(|error| {
        let message = format!("failed to send NaiveProxy H2 CONNECT: {error}");
        // The error can arrive before the dispatcher notices the closed connection.
        if error.is_closed() || error.is_canceled() || error.is_timeout() || session.sender.is_closed() {
            pool.discard(session.id);
            StreamFailure::Session(io::Error::new(io::ErrorKind::ConnectionAborted, message))
        } else {
            StreamFailure::Stream(io::Error::new(io::ErrorKind::ConnectionRefused, message))
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
    let stream = TokioIo::new(upgraded);
    match padding_mode {
        PayloadPaddingMode::Plain => Ok(Box::new(stream)),
        PayloadPaddingMode::Variant1 => Ok(Box::new(NaivePaddingStream::new(stream))),
    }
}

/// Connects TCP, TLS and HTTP/2 to the server and starts the request dispatcher.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the partial TCP/TLS/HTTP/2 connection;
/// nothing awaits after the dispatcher starts.
async fn connect_session(config: &NaiveProxyConfig) -> io::Result<Sender> {
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

    let (sender, connection) = hyper::client::conn::http2::Builder::new(TokioExecutor::new())
        .timer(TokioTimer::new())
        .keep_alive_interval(KEEPALIVE_INTERVAL)
        .keep_alive_timeout(KEEPALIVE_TIMEOUT)
        .initial_connection_window_size(CONNECTION_WINDOW_BYTES)
        .handshake(TokioIo::new(tls))
        .await
        .map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate NaiveProxy H2: {error}"))
        })?;
    // hyper's own connection task owns the socket; this dispatcher ends when the
    // pool and every open stream drop their senders.
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            eprintln!("{}", crate::errors::connection_failure_line(&io::Error::other(error)));
        }
    });
    Ok(sender)
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

    use super::NaivePaddingStream;
    use crate::padding::PaddingDecoder;

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
