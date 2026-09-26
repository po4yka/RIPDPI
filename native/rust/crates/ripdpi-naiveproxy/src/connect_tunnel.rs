use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use hyper_util::rt::{TokioExecutor, TokioIo};
use rand::RngExt;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::lookup_host;
use tokio_rustls::TlsConnector;

use crate::config::NaiveProxyConfig;
use crate::h2_connect::{
    PayloadPaddingMode, build_h2_connect_request, generate_connect_padding_header,
    negotiate_payload_padding_from_response,
};
use crate::padding::{PaddingDecoder, PaddingEncoder};
use crate::socks5::SocksTarget;

pub trait AsyncTunnel: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncTunnel for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub(crate) async fn open_https_connect_tunnel(
    config: &NaiveProxyConfig,
    target: &SocksTarget,
) -> io::Result<Box<dyn AsyncTunnel>> {
    let upstream_socket = resolve_first(&config.server, config.server_port).await?;
    let tcp = ripdpi_subprocess_protect::protected_tcp_connect(upstream_socket, config.protect_socket_path.as_deref())
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

    let io = TokioIo::new(tls);
    let (mut sender, connection) =
        hyper::client::conn::http2::handshake(TokioExecutor::new(), io).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to negotiate NaiveProxy H2: {error}"))
        })?;
    tokio::spawn(async move {
        if let Err(error) = connection.await {
            eprintln!("naiveproxy h2 connection closed: {error}");
        }
    });

    let request = build_h2_connect_request(config, target, generate_connect_padding_header())?;
    let response = sender.send_request(request).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to send NaiveProxy H2 CONNECT: {error}"))
    })?;

    if response.status() != http::StatusCode::OK {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!("NaiveProxy upstream rejected CONNECT with status {}", response.status()),
        ));
    }
    let padding_mode = negotiate_payload_padding_from_response(true, response.headers())?;
    let upgraded = hyper::upgrade::on(response).await.map_err(|error| {
        io::Error::new(io::ErrorKind::ConnectionRefused, format!("failed to upgrade NaiveProxy H2 CONNECT: {error}"))
    })?;
    let stream = TokioIo::new(upgraded);

    match padding_mode {
        PayloadPaddingMode::Plain => Ok(Box::new(stream)),
        PayloadPaddingMode::Variant1 => Ok(Box::new(NaivePaddingStream::new(stream))),
    }
}

async fn resolve_first(host: &str, port: u16) -> io::Result<SocketAddr> {
    lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "NaiveProxy server resolved to no addresses"))
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
    // cancel-safe: the test owns its socket and channel resources.
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
