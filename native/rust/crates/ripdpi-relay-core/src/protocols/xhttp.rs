use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

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

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move { self.client.connect(target).await })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "xHTTP relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for XhttpSessionFactory {
    type Session = XhttpSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let mode = self.mode.clone();
        Box::pin(async move {
            let client = match mode {
                XhttpSessionMode::Reality(config) => ripdpi_xhttp::XhttpClient::new_reality(config),
                XhttpSessionMode::Tls(config) => ripdpi_xhttp::XhttpClient::new_tls(config),
            };
            Ok(Arc::new(XhttpSession { client }))
        })
    }
}
