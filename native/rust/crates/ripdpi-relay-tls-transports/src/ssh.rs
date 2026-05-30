use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

pub use ripdpi_ssh::{SshAuth, SshChannelStream, SshConfig, SshHostKeyPolicy};

#[derive(Clone)]
pub struct SshSessionFactory {
    pub config: ripdpi_ssh::SshConfig,
}

pub struct SshSession {
    client: ripdpi_ssh::SshClient,
}

impl RelaySession for SshSession {
    type Stream = SshChannelStream;
    type Datagram = ();
    type Error = io::Error;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move { self.client.tcp_connect(target).await.map_err(to_io_error) })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(
            async move { Err(io::Error::new(io::ErrorKind::Unsupported, "SSH relay does not support UDP ASSOCIATE")) },
        )
    }
}

impl RelaySessionFactory for SshSessionFactory {
    type Session = SshSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        Box::pin(async move {
            let client = ripdpi_ssh::connect(&config).await.map_err(to_io_error)?;
            Ok(Arc::new(SshSession { client }))
        })
    }
}

fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
