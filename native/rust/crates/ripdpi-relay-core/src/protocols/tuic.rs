use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::telemetry::{QuicMigrationTelemetryState, sync_quic_migration_state};

#[derive(Clone)]
pub(crate) struct TuicSessionFactory {
    pub(crate) config: ripdpi_tuic::Config,
    pub(crate) migration: QuicMigrationTelemetryState,
}

pub(crate) struct TuicSession {
    client: ripdpi_tuic::TuicClient,
    pub(crate) migration: QuicMigrationTelemetryState,
}

impl RelaySession for TuicSession {
    type Stream = ripdpi_tuic::DuplexStream;
    type Datagram = ripdpi_tuic::UdpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let stream = self.client.tcp_connect(target).await?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let session = self.client.udp_session().await?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(session)
    }
}

impl RelaySessionFactory for TuicSessionFactory {
    type Session = TuicSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        let client = ripdpi_tuic::TuicClient::connect(config).await?;
        sync_quic_migration_state(&migration, client.quic_migration_snapshot());
        Ok(Arc::new(TuicSession { client, migration }))
    }
}
