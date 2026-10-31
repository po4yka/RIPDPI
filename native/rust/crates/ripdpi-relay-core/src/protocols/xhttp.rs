use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

#[derive(Clone)]
pub(crate) enum XhttpSessionMode {
    Reality(ripdpi_xhttp::XhttpRealityConfig),
    Tls(ripdpi_xhttp::XhttpTlsConfig),
}

#[derive(Clone)]
pub(crate) struct XhttpSessionFactory {
    pub(crate) mode: XhttpSessionMode,
    /// VLESS profiles opt into XUDP; Cloudflare tunnels never carry UDP.
    pub(crate) udp_enabled: bool,
}

pub(crate) struct XhttpSession {
    client: ripdpi_xhttp::XhttpClient,
    udp_enabled: bool,
}

impl RelaySession for XhttpSession {
    type Stream = ripdpi_xhttp::XhttpStream;
    type Datagram = ripdpi_vless::VlessXudpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        self.client.connect(target).await
    }

    /// # Cancel safety
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
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = XhttpSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: self.udp_enabled, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let mode = self.mode.clone();
        let client = match mode {
            XhttpSessionMode::Reality(config) => ripdpi_xhttp::XhttpClient::new_reality(config),
            XhttpSessionMode::Tls(config) => ripdpi_xhttp::XhttpClient::new_tls(config),
        };
        Ok(Arc::new(XhttpSession { client, udp_enabled: self.udp_enabled }))
    }
}
