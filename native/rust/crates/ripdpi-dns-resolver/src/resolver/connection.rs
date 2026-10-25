use std::io;
use std::time::{Duration, Instant};

use tokio::net::TcpStream as TokioTcpStream;
use tokio::sync::{Mutex as AsyncMutex, Semaphore, SemaphorePermit};

use crate::types::EncryptedDnsError;

pub(super) type DotTlsStream = tokio_boring::SslStream<TokioTcpStream>;
const MAX_POOLED_IDLE_DURATION: Duration = Duration::from_secs(20);
/// Upper bound on DoT/DNSCrypt connections per resolver. A burst of concurrent
/// lookups waits for a slot and reuses its connection instead of opening one
/// TLS session per lookup.
const MAX_POOLED_CONNECTIONS: usize = 4;

pub(super) trait TcpClientStream: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin {
    fn set_nodelay_if_supported(&self, enabled: bool) -> io::Result<()> {
        let _ = enabled;
        Ok(())
    }
}

impl TcpClientStream for TokioTcpStream {
    fn set_nodelay_if_supported(&self, enabled: bool) -> io::Result<()> {
        self.set_nodelay(enabled)
    }
}

#[cfg(test)]
impl TcpClientStream for turmoil::net::TcpStream {}

pub(super) enum PooledConnection {
    Dot(Box<DotTlsStream>),
    DnsCrypt(TokioTcpStream),
}

struct IdlePooledConnection {
    connection: PooledConnection,
    idle_since: Instant,
}

pub(super) struct ConnectionPool {
    idle: AsyncMutex<Vec<IdlePooledConnection>>,
    slots: Semaphore,
}

impl Default for ConnectionPool {
    fn default() -> Self {
        Self { idle: AsyncMutex::new(Vec::new()), slots: Semaphore::new(MAX_POOLED_CONNECTIONS) }
    }
}

impl ConnectionPool {
    /// Waits for a connection slot. Hold the permit from `take` through `put`, so at most
    /// `MAX_POOLED_CONNECTIONS` connections exist at once.
    ///
    /// # Cancel safety
    ///
    /// Cancel-safe: a cancelled wait holds no slot.
    pub(super) async fn slot(&self) -> Result<SemaphorePermit<'_>, EncryptedDnsError> {
        self.slots.acquire().await.map_err(|_| EncryptedDnsError::Request("connection pool closed".to_string()))
    }

    pub(super) async fn take(&self) -> Option<PooledConnection> {
        let mut idle = self.idle.lock().await;
        while let Some(pooled) = idle.pop() {
            if pooled.idle_since.elapsed() <= MAX_POOLED_IDLE_DURATION {
                return Some(pooled.connection);
            }
        }
        None
    }

    pub(super) async fn put(&self, connection: PooledConnection) {
        self.put_with_idle_since(connection, Instant::now()).await;
    }

    pub(super) async fn put_with_idle_since(&self, connection: PooledConnection, idle_since: Instant) {
        let mut idle = self.idle.lock().await;
        if idle.len() < MAX_POOLED_CONNECTIONS {
            idle.push(IdlePooledConnection { connection, idle_since });
        }
    }
}

impl std::fmt::Debug for PooledConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Dot(_) => f.write_str("PooledConnection::Dot(..)"),
            Self::DnsCrypt(_) => f.write_str("PooledConnection::DnsCrypt(..)"),
        }
    }
}

impl std::fmt::Debug for ConnectionPool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConnectionPool").finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, TcpListener};
    use std::time::{Duration, Instant};

    use tokio::net::TcpStream as TokioTcpStream;

    use super::*;

    #[tokio::test]
    async fn connection_pool_discards_expired_idle_entry() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let address = listener.local_addr().expect("local addr");

        let accept_handle = std::thread::spawn(move || listener.accept().expect("accept"));
        let stream = TokioTcpStream::connect(address).await.expect("connect");
        let _accepted = accept_handle.join().expect("accept thread");

        let pool = ConnectionPool::default();
        pool.put_with_idle_since(
            PooledConnection::DnsCrypt(stream),
            Instant::now() - MAX_POOLED_IDLE_DURATION - Duration::from_secs(1),
        )
        .await;

        assert!(pool.take().await.is_none(), "expired pooled entries should be discarded instead of reused",);
    }

    #[tokio::test]
    async fn connection_pool_reuses_fresh_idle_entry() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let address = listener.local_addr().expect("local addr");

        let accept_handle = std::thread::spawn(move || listener.accept().expect("accept"));
        let stream = TokioTcpStream::connect(address).await.expect("connect");
        let _accepted = accept_handle.join().expect("accept thread");

        let pool = ConnectionPool::default();
        pool.put_with_idle_since(
            PooledConnection::DnsCrypt(stream),
            Instant::now() - MAX_POOLED_IDLE_DURATION + Duration::from_secs(1),
        )
        .await;

        assert!(
            matches!(pool.take().await, Some(PooledConnection::DnsCrypt(_))),
            "fresh pooled entries should still be reused",
        );
    }
}
