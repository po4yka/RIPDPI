use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::util::to_io_error;

pub use ripdpi_ssh::{SshAuth, SshChannelStream, SshConfig, SshHostKeyPolicy};

#[derive(Clone)]
pub struct SshSessionFactory {
    pub config: ripdpi_ssh::SshConfig,
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}

pub struct SshSession {
    client: ripdpi_ssh::SshClient,
}

impl RelaySession for SshSession {
    type Stream = SshChannelStream;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        self.client.tcp_connect(target).await.map_err(to_io_error)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "SSH relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for SshSessionFactory {
    type Session = SshSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let client =
            ripdpi_ssh::connect_with_socket_protection(&config, self.socket_protection).await.map_err(|error| {
                match error {
                    ripdpi_ssh::SshError::Io(error) => error,
                    other => to_io_error(other),
                }
            })?;
        Ok(Arc::new(SshSession { client }))
    }
}
