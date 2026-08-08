use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::AtomicUsize;
use std::time::Duration;

use tokio::sync::Mutex;
use tokio::time::timeout;

use crate::config::{AsyncIo, XhttpMode, XhttpRealityConfig, XhttpTlsConfig, XmuxConfig};
use crate::pool::{self, PoolState};
use crate::relay::XhttpStream;

const STREAM_ESTABLISHMENT_TIMEOUT: Duration = Duration::from_secs(30);
const STREAM_ESTABLISHMENT_TIMEOUT_MESSAGE: &str = "xHTTP stream establishment timed out";

#[derive(Clone)]
pub struct XhttpClient {
    pub(crate) inner: Arc<XhttpClientInner>,
}

pub(crate) struct XhttpClientInner {
    pub(crate) mode: XhttpMode,
    pub(crate) max_connections: usize,
    pub(crate) max_concurrent_streams: usize,
    pub(crate) creating_connections: Arc<AtomicUsize>,
    pub(crate) state: Mutex<PoolState>,
}

/// cancel-safe: cancelling drops the not-yet-returned stream and releases all
/// xHTTP establishment resources through the same guards as [`XhttpClient::connect`].
pub async fn connect_reality(config: &XhttpRealityConfig, target: &str) -> io::Result<impl AsyncIo + use<>> {
    XhttpClient::new_reality(config.clone()).connect(target).await
}

/// cancel-safe: cancelling drops the not-yet-returned stream and releases all
/// xHTTP establishment resources through the same guards as [`XhttpClient::connect`].
pub async fn connect_tls(config: &XhttpTlsConfig, target: &str) -> io::Result<impl AsyncIo + use<>> {
    XhttpClient::new_tls(config.clone()).connect(target).await
}

impl XhttpClient {
    pub fn new_reality(config: XhttpRealityConfig) -> Self {
        Self::new(XhttpMode::Reality(config.clone()), config.xmux)
    }

    pub fn new_tls(config: XhttpTlsConfig) -> Self {
        Self::new(XhttpMode::Tls(config.clone()), config.xmux.clone())
    }

    /// cancel-safe: cancellation or the internal deadline releases pool
    /// reservations and permits, drops partial carriers, and aborts stream-open pumps.
    pub async fn connect(&self, target: &str) -> io::Result<XhttpStream> {
        establish_with_deadline(STREAM_ESTABLISHMENT_TIMEOUT, async {
            let (connection, permit) = pool::acquire_connection(&self.inner).await?;
            connection.open_stream_from_mode(&self.inner.mode, target, permit).await
        })
        .await
    }

    fn new(mode: XhttpMode, xmux: XmuxConfig) -> Self {
        Self {
            inner: Arc::new(XhttpClientInner {
                mode,
                max_connections: xmux.max_connections.max(1),
                max_concurrent_streams: xmux.max_concurrent_streams.max(1),
                creating_connections: Arc::new(AtomicUsize::new(0)),
                state: Mutex::new(PoolState::default()),
            }),
        }
    }
}

/// NOT cancel-safe for an arbitrary `operation`: expiry drops it at its current
/// await point. The production caller supplies only xHTTP establishment, whose
/// partial carrier, pool, permit, and pump state all have cancellation guards.
async fn establish_with_deadline<T>(
    deadline: Duration,
    operation: impl Future<Output = io::Result<T>>,
) -> io::Result<T> {
    timeout(deadline, operation)
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, STREAM_ESTABLISHMENT_TIMEOUT_MESSAGE))?
}

#[cfg(test)]
mod tests {
    use std::future;
    use std::io;
    use std::time::Duration;

    use tokio::sync::oneshot;

    use super::establish_with_deadline;

    struct DropNotice(Option<oneshot::Sender<()>>);

    impl Drop for DropNotice {
        fn drop(&mut self) {
            if let Some(sender) = self.0.take() {
                let _ = sender.send(());
            }
        }
    }

    #[tokio::test]
    async fn stream_establishment_returns_timeout_instead_of_waiting_forever() {
        let (dropped_tx, dropped_rx) = oneshot::channel();
        let stalled = async move {
            let _drop_notice = DropNotice(Some(dropped_tx));
            future::pending::<io::Result<()>>().await
        };

        let error = establish_with_deadline(Duration::from_millis(10), stalled)
            .await
            .expect_err("a stalled xHTTP establishment must time out");

        assert_eq!(io::ErrorKind::TimedOut, error.kind());
        assert_eq!("xHTTP stream establishment timed out", error.to_string());
        dropped_rx.await.expect("timing out must drop the stalled establishment future");
    }

    #[tokio::test]
    async fn stream_establishment_preserves_completed_result() {
        let result = establish_with_deadline(Duration::from_secs(1), future::ready(Ok::<_, io::Error>(42)))
            .await
            .expect("a completed xHTTP establishment must pass through");

        assert_eq!(42, result);
    }
}
