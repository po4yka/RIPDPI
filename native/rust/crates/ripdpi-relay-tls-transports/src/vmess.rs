use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

pub use ripdpi_vmess::{VmessConfig, VmessSecurity, VmessTransport};

#[derive(Clone)]
pub struct VmessSessionFactory {
    pub config: ripdpi_vmess::VmessConfig,
}

pub struct VmessSession {
    client: ripdpi_vmess::VmessClient,
}

impl RelaySession for VmessSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move { self.client.tcp_connect(target).await.map_err(to_io_error) })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "VMess relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for VmessSessionFactory {
    type Session = VmessSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        Box::pin(async move {
            let client = ripdpi_vmess::connect(&config).await.map_err(to_io_error)?;
            Ok(Arc::new(VmessSession { client }))
        })
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
