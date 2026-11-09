use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::backend::builder::builders::to_io_error;
use crate::telemetry::{QuicMigrationTelemetryState, sync_quic_migration_state};

#[derive(Clone)]
pub(crate) struct Hysteria2SessionFactory {
    pub(crate) config: ripdpi_hysteria2::Config,
    pub(crate) migration: QuicMigrationTelemetryState,
}

pub(crate) struct Hysteria2Session {
    client: ripdpi_hysteria2::HysteriaClient,
    pub(crate) migration: QuicMigrationTelemetryState,
}

/// Marker payload for a peer answer that is final for this request: a refused
/// target or a server without UDP. The shared QUIC carrier is healthy, so
/// evicting it would only repeat the refusal on a fresh handshake.
#[derive(Debug)]
struct ServerRefusal(String);

impl std::fmt::Display for ServerRefusal {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ServerRefusal {}

fn map_session_error(error: ripdpi_hysteria2::HysteriaError) -> io::Error {
    let kind = match &error {
        ripdpi_hysteria2::HysteriaError::TcpConnect(_) => io::ErrorKind::ConnectionRefused,
        ripdpi_hysteria2::HysteriaError::UdpNotSupported => io::ErrorKind::Unsupported,
        _ => return to_io_error(error),
    };
    io::Error::new(kind, ServerRefusal(error.to_string()))
}

impl RelaySession for Hysteria2Session {
    type Stream = ripdpi_hysteria2::DuplexStream;
    type Datagram = ripdpi_hysteria2::UdpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let stream = self.client.tcp_connect(target).await.map_err(map_session_error)?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let session = self.client.udp_session().await.map_err(map_session_error)?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(session)
    }

    fn is_carrier_failure(&self, error: &Self::Error) -> bool {
        !error.get_ref().is_some_and(<dyn std::error::Error + Send + Sync>::is::<ServerRefusal>)
    }
}

impl RelaySessionFactory for Hysteria2SessionFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = Hysteria2Session;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        let client = ripdpi_hysteria2::connect(&config).await.map_err(to_io_error)?;
        sync_quic_migration_state(&migration, client.quic_migration_snapshot());
        Ok(Arc::new(Hysteria2Session { client, migration }))
    }
}
