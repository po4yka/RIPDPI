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
}

pub(crate) struct XhttpSession {
    client: ripdpi_xhttp::XhttpClient,
}

impl RelaySession for XhttpSession {
    type Stream = ripdpi_xhttp::XhttpStream;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        self.client.connect(target).await
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "xHTTP relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for XhttpSessionFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = XhttpSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let mode = self.mode.clone();
        let client = match mode {
            XhttpSessionMode::Reality(config) => ripdpi_xhttp::XhttpClient::new_reality(config),
            XhttpSessionMode::Tls(config) => ripdpi_xhttp::XhttpClient::new_tls(config),
        };
        Ok(Arc::new(XhttpSession { client }))
    }
}
