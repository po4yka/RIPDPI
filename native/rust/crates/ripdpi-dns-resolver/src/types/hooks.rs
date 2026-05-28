use std::fmt;
use std::future::Future;
use std::io;
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use boring::ssl::SslConnectorBuilder;
use tokio::io::{AsyncRead, AsyncWrite};

pub trait DnsTcpStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> DnsTcpStream for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub type BoxedDnsTcpStream = Box<dyn DnsTcpStream>;
pub type DirectTcpConnectorFuture = Pin<Box<dyn Future<Output = io::Result<DirectTcpConnection>> + Send>>;
pub type DirectTcpConnector = dyn Fn(SocketAddr, Duration) -> DirectTcpConnectorFuture + Send + Sync;
pub type DirectUdpBinder = dyn Fn(SocketAddr) -> io::Result<UdpSocket> + Send + Sync;
pub type DotTlsConnectorBuilder = dyn Fn() -> Result<SslConnectorBuilder, String> + Send + Sync;

pub enum DirectTcpConnection {
    Std(TcpStream),
    Async(BoxedDnsTcpStream),
}

impl From<TcpStream> for DirectTcpConnection {
    fn from(stream: TcpStream) -> Self {
        Self::Std(stream)
    }
}

impl From<BoxedDnsTcpStream> for DirectTcpConnection {
    fn from(stream: BoxedDnsTcpStream) -> Self {
        Self::Async(stream)
    }
}

#[derive(Clone, Default)]
pub struct EncryptedDnsConnectHooks {
    pub direct_tcp_connector: Option<Arc<DirectTcpConnector>>,
    pub direct_udp_binder: Option<Arc<DirectUdpBinder>>,
    pub dot_tls_connector_builder: Option<Arc<DotTlsConnectorBuilder>>,
}

impl EncryptedDnsConnectHooks {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_direct_tcp_connector<F, Fut, S>(mut self, connector: F) -> Self
    where
        F: Fn(SocketAddr, Duration) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = io::Result<S>> + Send + 'static,
        S: Into<DirectTcpConnection> + 'static,
    {
        self.direct_tcp_connector = Some(Arc::new(move |target, timeout| {
            let future = connector(target, timeout);
            Box::pin(async move { future.await.map(Into::into) })
        }));
        self
    }

    pub fn with_direct_udp_binder<F>(mut self, binder: F) -> Self
    where
        F: Fn(SocketAddr) -> io::Result<UdpSocket> + Send + Sync + 'static,
    {
        self.direct_udp_binder = Some(Arc::new(binder));
        self
    }

    pub fn with_dot_tls_connector_builder<F>(mut self, builder: F) -> Self
    where
        F: Fn() -> Result<SslConnectorBuilder, String> + Send + Sync + 'static,
    {
        self.dot_tls_connector_builder = Some(Arc::new(builder));
        self
    }

    pub(crate) fn has_direct_tcp_connector(&self) -> bool {
        self.direct_tcp_connector.is_some()
    }

    #[cfg(feature = "hickory-backend")]
    pub(crate) fn has_dot_tls_connector_builder(&self) -> bool {
        self.dot_tls_connector_builder.is_some()
    }
}

impl fmt::Debug for EncryptedDnsConnectHooks {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EncryptedDnsConnectHooks")
            .field("direct_tcp_connector", &self.direct_tcp_connector.is_some())
            .field("direct_udp_binder", &self.direct_udp_binder.is_some())
            .field("dot_tls_connector_builder", &self.dot_tls_connector_builder.is_some())
            .finish()
    }
}
