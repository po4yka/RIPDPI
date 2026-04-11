use std::future::Future;
use std::time::Duration;

#[derive(Debug, Clone, Copy, Default)]
pub struct RelayCapabilities {
    pub tcp: bool,
    pub udp: bool,
    pub reusable: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct RelayPoolHealth {
    pub idle_streams: usize,
    pub busy_streams: usize,
    pub evictions: u64,
    pub idle_timeout: Duration,
}

impl Default for RelayPoolHealth {
    fn default() -> Self {
        Self { idle_streams: 0, busy_streams: 0, evictions: 0, idle_timeout: Duration::from_secs(30) }
    }
}

pub trait PooledRelayBackend: Send + Sync {
    type Stream: Send + 'static;
    type Datagram: Send + 'static;
    type Error: Send + Sync + 'static;

    fn capabilities(&self) -> RelayCapabilities;
    fn health(&self) -> RelayPoolHealth;

    fn open_stream(&self) -> impl Future<Output = Result<Self::Stream, Self::Error>> + Send;

    fn open_datagram(&self) -> impl Future<Output = Result<Self::Datagram, Self::Error>> + Send;
}

#[derive(Default)]
pub struct NoopRelayMux {
    health: tokio::sync::RwLock<RelayPoolHealth>,
}

impl NoopRelayMux {
    pub async fn health(&self) -> RelayPoolHealth {
        *self.health.read().await
    }
}
