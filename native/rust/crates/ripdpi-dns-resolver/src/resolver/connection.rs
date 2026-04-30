use std::io;
use std::time::{Duration, Instant};

use tokio::net::TcpStream as TokioTcpStream;
use tokio::sync::Mutex as AsyncMutex;

pub(super) type DotTlsStream = tokio_boring::SslStream<TokioTcpStream>;
const MAX_POOLED_IDLE_DURATION: Duration = Duration::from_secs(20);

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

#[derive(Default)]
pub(super) struct ConnectionPool {
    idle: AsyncMutex<Option<IdlePooledConnection>>,
}

impl ConnectionPool {
    pub(super) async fn take(&self) -> Option<PooledConnection> {
        let pooled = self.idle.lock().await.take()?;
        if pooled.idle_since.elapsed() > MAX_POOLED_IDLE_DURATION {
            return None;
        }
        Some(pooled.connection)
    }

    pub(super) async fn put(&self, connection: PooledConnection) {
        *self.idle.lock().await = Some(IdlePooledConnection { connection, idle_since: Instant::now() });
    }

    #[cfg(test)]
    pub(super) async fn put_with_idle_since(&self, connection: PooledConnection, idle_since: Instant) {
        *self.idle.lock().await = Some(IdlePooledConnection { connection, idle_since });
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
