use std::io;
use std::sync::{Arc, Once};

use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;

static RUSTLS_PROVIDER: Once = Once::new();

pub(crate) struct BufferedTlsStream {
    upstream: TlsStream<TcpStream>,
    pending_read: Vec<u8>,
}

impl BufferedTlsStream {
    pub(crate) fn new(upstream: TlsStream<TcpStream>, pending_read: Vec<u8>) -> Self {
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

pub(crate) fn default_tls_config() -> Arc<RustlsClientConfig> {
    ensure_rustls_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    Arc::new(RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth())
}

pub(crate) fn ensure_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}
