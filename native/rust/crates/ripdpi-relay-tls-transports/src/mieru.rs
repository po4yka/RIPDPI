use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

pub use ripdpi_mieru::{MieruConfig, MieruMux, MieruProtocol};

#[derive(Clone)]
pub struct MieruSessionFactory {
    pub config: ripdpi_mieru::MieruConfig,
}

pub struct MieruSession {
    client: ripdpi_mieru::MieruClient,
}

impl RelaySession for MieruSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move { self.client.tcp_connect(target).await.map_err(to_io_error) })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            Err(io::Error::new(io::ErrorKind::Unsupported, "Mieru relay does not support UDP ASSOCIATE"))
        })
    }
}

impl RelaySessionFactory for MieruSessionFactory {
    type Session = MieruSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        Box::pin(async move {
            let client = ripdpi_mieru::connect(&config).await.map_err(to_io_error)?;
            Ok(Arc::new(MieruSession { client }))
        })
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
