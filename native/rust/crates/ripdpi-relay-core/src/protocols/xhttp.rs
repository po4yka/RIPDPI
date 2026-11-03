use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

#[derive(Clone)]
pub(crate) enum XhttpSessionMode {
    Reality(ripdpi_xhttp::XhttpRealityConfig),
    Tls(ripdpi_xhttp::XhttpTlsConfig),
}

pub(crate) struct XhttpSessionFactory {
    client: tokio::sync::Mutex<Option<ripdpi_xhttp::XhttpClient>>,
    /// VLESS profiles opt into XUDP; Cloudflare tunnels never carry UDP.
    pub(crate) udp_enabled: bool,
}

impl XhttpSessionFactory {
    pub(crate) fn new(mode: XhttpSessionMode, udp_enabled: bool) -> Self {
        let client = match mode {
            XhttpSessionMode::Reality(config) => ripdpi_xhttp::XhttpClient::new_reality(config),
            XhttpSessionMode::Tls(config) => ripdpi_xhttp::XhttpClient::new_tls(config),
        };
        Self { client: tokio::sync::Mutex::new(Some(client)), udp_enabled }
    }
}

pub(crate) struct XhttpSession {
    client: ripdpi_xhttp::XhttpClient,
    udp_enabled: bool,
}

impl RelaySession for XhttpSession {
    type Stream = ripdpi_xhttp::XhttpStream;
    type Datagram = ripdpi_vless::VlessXudpSession;
    type Error = io::Error;

    /// # Cancel safety:
    /// cancel-safe: the client retires an interrupted carrier and restores its pool capacity.
    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        self.client.connect(target).await
    }

    /// # Cancel safety:
    ///
    /// cancel-safe: inherits `XhttpClient::connect_xudp`, which releases the
    /// pool permit and stream pumps when dropped before the session returns.
    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        if !self.udp_enabled {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "xHTTP relay UDP is disabled for this profile"));
        }
        self.client.connect_xudp().await
    }
}

impl RelaySessionFactory for XhttpSessionFactory {
    /// # Cancel safety:
    /// cancel-safe: the only await acquires the lock; removing the client is synchronous.
    async fn shutdown(&self) -> Result<(), Self::Error> {
        self.client.lock().await.take();
        Ok(())
    }

    type Session = XhttpSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: self.udp_enabled, reusable: true }
    }

    /// # Cancel safety:
    /// cancel-safe: the only await acquires the lock; cloning does not open sockets.
    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let client = self
            .client
            .lock()
            .await
            .clone()
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "xHTTP backend is shut down"))?;
        Ok(Arc::new(XhttpSession { client, udp_enabled: self.udp_enabled }))
    }
}
