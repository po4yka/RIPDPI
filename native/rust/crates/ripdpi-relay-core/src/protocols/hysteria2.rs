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

impl RelaySession for Hysteria2Session {
    type Stream = ripdpi_hysteria2::DuplexStream;
    type Datagram = ripdpi_hysteria2::UdpSession;
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        let stream = self.client.tcp_connect(target).await.map_err(to_io_error)?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        let session = self.client.udp_session().await.map_err(to_io_error)?;
        sync_quic_migration_state(&self.migration, self.client.quic_migration_snapshot());
        Ok(session)
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
