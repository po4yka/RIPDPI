use std::io;
use std::sync::Arc;

use ripdpi_relay_mux::{BoxFuture, RelayCapabilities, RelaySession, RelaySessionFactory};

use crate::telemetry::{sync_quic_migration_state, QuicMigrationTelemetryState};

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

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
        Box::pin(async move {
            let stream = self.client.tcp_connect(target).await?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(stream)
        })
    }

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
        Box::pin(async move {
            let session = self.client.udp_session().await?;
            sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
            Ok(session)
        })
    }
}

impl RelaySessionFactory for TuicSessionFactory {
    type Session = TuicSession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
        let config = self.config.clone();
        let migration = self.migration.clone();
        Box::pin(async move {
            let client = ripdpi_tuic::TuicClient::connect(config).await?;
            sync_quic_migration_state(&migration, client.quic_migration_snapshot());
            Ok(Arc::new(TuicSession { client, migration }))
        })
    }
}
