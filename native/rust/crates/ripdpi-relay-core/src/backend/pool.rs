use std::io;

use ripdpi_relay_mux::{MuxLease, RelayMux, RelayPoolConfig, RelayPoolHealth, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite};

use crate::backend::udp::RelayUdpSession;
use crate::socks::RelayTargetAddr;
use crate::telemetry::QuicMigrationTelemetryState;

pub(crate) trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}
pub(crate) type BoxedIo = Box<dyn AsyncIo>;

pub(crate) struct PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
{
    mux: RelayMux<F>,
    migration: Option<QuicMigrationTelemetryState>,
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
{
    pub(crate) fn new(factory: F, config: RelayPoolConfig, migration: Option<QuicMigrationTelemetryState>) -> Self {
        Self { mux: RelayMux::new(factory, config), migration }
    }

    pub(crate) fn capabilities(&self) -> ripdpi_relay_mux::RelayCapabilities {
        self.mux.capabilities()
    }

    pub(crate) fn pool_health(&self) -> RelayPoolHealth {
        self.mux.health()
    }

    pub(crate) fn quic_migration_snapshot(&self) -> (Option<String>, Option<String>) {
        self.migration.as_ref().map_or((None, None), QuicMigrationTelemetryState::snapshot)
    }

    pub(crate) fn quic_migration_snapshot_state(&self) -> QuicMigrationTelemetryState {
        self.migration.clone().unwrap_or_default()
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
    <F::Session as RelaySession>::Stream: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: inherits `RelayMux::open_stream`; an
    /// incomplete logical stream must be reset without corrupting a reused
    /// carrier.
    pub(crate) async fn connect_tcp(&self, target: &RelayTargetAddr) -> io::Result<BoxedIo> {
        let stream = self.mux.open_stream(&target.to_connect_target()).await?;
        Ok(Box::new(stream))
    }
}

impl<F> PooledRelayBackend<F>
where
    F: RelaySessionFactory<Error = io::Error>,
    <F::Session as RelaySession>::Datagram: Send + 'static,
{
    pub(crate) async fn open_udp_session<M>(&self, map: M) -> io::Result<RelayUdpSession>
    where
        M: FnOnce(MuxLease<<F::Session as RelaySession>::Datagram, F::Session>) -> RelayUdpSession,
    {
        self.mux.open_datagram().await.map(map)
    }
}
