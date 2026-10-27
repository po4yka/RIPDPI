use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::session_registry::{OwnedSession, Registered, SessionRegistry};
use crate::util::to_io_error;

pub use ripdpi_ssh::{SshAuth, SshChannelStream, SshConfig, SshHostKeyPolicy};

#[derive(Clone)]
pub struct SshSessionFactory {
    config: ripdpi_ssh::SshConfig,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    sessions: Arc<SessionRegistry<SshCarrier>>,
}

impl SshSessionFactory {
    pub fn new(config: SshConfig, socket_protection: ripdpi_native_protect::SocketProtectionPolicy) -> Self {
        Self { config, socket_protection, sessions: Arc::default() }
    }
}

/// A registry-owned carrier; callers hold it as [`SshSession`].
pub struct SshCarrier {
    client: ripdpi_ssh::SshClient,
}

/// Dropping the last handle aborts the carrier.
pub type SshSession = Registered<SshCarrier>;

impl OwnedSession for SshCarrier {
    fn abort(&self) {
        self.client.cancel();
    }
    async fn close(&self) -> io::Result<()> {
        self.client.close().await.map_err(to_io_error)
    }
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
    async fn shutdown(&self) -> Result<(), Self::Error> {
        self.sessions.shutdown().await
    }

    type Session = SshSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let session = self
            .sessions
            .create(async {
                let client = ripdpi_ssh::connect_with_socket_protection(&self.config, self.socket_protection)
                    .map_err(to_io_error)?;
                Ok(SshCarrier { client })
            })
            .await?;
        // The registry owns construction before this cancellable readiness wait.
        session.client.ready().await.map_err(|error| match error {
            ripdpi_ssh::SshError::Io(error) => error,
            other => to_io_error(other),
        })?;
        Ok(session)
    }
}
