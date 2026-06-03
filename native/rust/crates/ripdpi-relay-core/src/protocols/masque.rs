use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::telemetry::{QuicMigrationTelemetryState, sync_quic_migration_state};

#[derive(Clone)]
pub(crate) struct MasqueSessionFactory {
    pub(crate) config: ripdpi_masque::config::MasqueConfig,
    pub(crate) migration: QuicMigrationTelemetryState,
}

pub(crate) struct MasqueSession {
    client: ripdpi_masque::MasqueClient,
    pub(crate) migration: QuicMigrationTelemetryState,
}

impl RelaySession for MasqueSession {
    type Stream = Box<dyn ripdpi_masque::AsyncIo>;
    type Datagram = ripdpi_masque::MasqueUdpRelay;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let stream = self.client.connect_tcp(target).await?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let session = self.client.udp_session();
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(session)
    }
}

impl RelaySessionFactory for MasqueSessionFactory {
    type Session = MasqueSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        let client = ripdpi_masque::MasqueClient::new(config)?;
        sync_quic_migration_state(&migration, client.quic_migration_snapshot());
        Ok(Arc::new(MasqueSession { client, migration }))
    }
}
