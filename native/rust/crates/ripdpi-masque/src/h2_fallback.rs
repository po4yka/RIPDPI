use std::io;
use std::net::ToSocketAddrs;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use hyper::rt::{Read, Write};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use url::Url;

use crate::cloudflare;
use crate::config::MasqueConfig;

/// Trait alias for an async bidirectional stream.
pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// Open an HTTP/2 CONNECT tunnel to the target through the MASQUE proxy.
///
/// This is the fallback path when QUIC/HTTP3 is blocked. It uses a standard
/// HTTP/2 CONNECT method over TLS 1.3 TCP.
pub async fn h2_connect_tcp(config: &MasqueConfig, target: &str) -> io::Result<impl AsyncIo> {
    let parsed = Url::parse(&config.url)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid MASQUE URL: {e}")))?;

    let host = parsed
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL has no host"))?
        .to_owned();
    let port = parsed.port().unwrap_or(443);

    let addr = format!("{host}:{port}")
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "failed to resolve MASQUE server"))?;

    // TCP connect
    let tcp = TcpStream::connect(addr)
        .await
        .map_err(|e| io::Error::new(e.kind(), format!("H2 fallback TCP connect to {addr}: {e}")))?;
    tcp.set_nodelay(true)?;

    // TLS with ALPN h2
    let mut root_store = rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut tls_config = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_root_certificates(root_store)
        .with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h2".to_vec()];

    let connector = tokio_rustls::TlsConnector::from(Arc::new(tls_config));
    let server_name = rustls::pki_types::ServerName::try_from(host.clone())
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid server name: {e}")))?;
    let tls = connector
        .connect(server_name, tcp)
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 TLS handshake: {e}")))?;

    tracing::debug!(%addr, "H2 TLS connection established to MASQUE server");

    // HTTP/2 CONNECT via hyper
    let io_adapter = TokioIo::new(tls);
    let (mut sender, conn) = hyper::client::conn::http2::handshake(hyper_util::rt::TokioExecutor::new(), io_adapter)
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 handshake: {e}")))?;

    // Drive the HTTP/2 connection in the background
    tokio::spawn(async move {
        if let Err(e) = conn.await {
            tracing::warn!("H2 connection error: {e}");
        }
    });

    // Build CONNECT request with empty body
    let mut builder = hyper::Request::builder().method("CONNECT").uri(target);

    if config.cloudflare_mode {
        for (name, value) in cloudflare::cloudflare_auth_headers(config) {
            builder = builder.header(name, value);
        }
    } else if let Some(ref token) = config.auth_token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }

    let request = builder
        .body(http_body_util::Empty::<bytes::Bytes>::new())
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("H2 request build: {e}")))?;

    let response = sender
        .send_request(request)
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 CONNECT: {e}")))?;

    if response.status() != hyper::StatusCode::OK {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("H2 MASQUE server rejected CONNECT: {}", response.status()),
        ));
    }

    tracing::debug!(target, "H2 CONNECT tunnel established");

    // Upgrade the connection to get the raw bidirectional stream
    let upgraded = hyper::upgrade::on(response)
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H2 upgrade: {e}")))?;

    Ok(TokioIo::new(upgraded))
}

/// Adapter between tokio's `AsyncRead`/`AsyncWrite` and hyper's `Read`/`Write` traits.
///
/// hyper 1.x uses its own I/O traits; this shim makes tokio streams usable with hyper,
/// and also exposes tokio traits on hyper streams (like `Upgraded`).
pub(crate) struct TokioIo<T> {
    inner: T,
}

impl<T> TokioIo<T> {
    pub fn new(inner: T) -> Self {
        Self { inner }
    }
}

// hyper Read/Write impls for using tokio streams with hyper
impl<T: AsyncRead + Unpin> Read for TokioIo<T> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        mut buf: hyper::rt::ReadBufCursor<'_>,
    ) -> Poll<io::Result<()>> {
        let n = unsafe {
            let mut tbuf = ReadBuf::uninit(buf.as_mut());
            match Pin::new(&mut self.get_mut().inner).poll_read(cx, &mut tbuf) {
                Poll::Ready(Ok(())) => tbuf.filled().len(),
                other => return other,
            }
        };
        unsafe { buf.advance(n) };
        Poll::Ready(Ok(()))
    }
}

impl<T: AsyncWrite + Unpin> Write for TokioIo<T> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

// Tokio AsyncRead/AsyncWrite impls for using hyper `Upgraded` with tokio
impl<T: Read + Unpin> AsyncRead for TokioIo<T> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        // Safety: we only advance filled portion after reading
        unsafe {
            let mut hbuf = hyper::rt::ReadBuf::uninit(buf.unfilled_mut());
            let cursor = hbuf.unfilled();
            match Pin::new(&mut this.inner).poll_read(cx, cursor) {
                Poll::Ready(Ok(())) => {
                    let filled = hbuf.filled().len();
                    buf.assume_init(filled);
                    buf.advance(filled);
                    Poll::Ready(Ok(()))
                }
                Poll::Ready(Err(e)) => Poll::Ready(Err(e)),
                Poll::Pending => Poll::Pending,
            }
        }
    }
}

impl<T: Write + Unpin> AsyncWrite for TokioIo<T> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}
