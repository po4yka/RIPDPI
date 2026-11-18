use std::future::Future;
use std::io;
use std::sync::Arc;
use std::sync::atomic::AtomicUsize;
use std::time::Duration;

use ripdpi_vless::VlessXudpSession;
use tokio::sync::{Mutex, Notify, Semaphore};
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
    pub(crate) carrier_capacity: Arc<Semaphore>,
    pub(crate) max_concurrent_streams: usize,
    pub(crate) creating_connections: Arc<AtomicUsize>,
    pub(crate) pool_changed: Arc<Notify>,
    pub(crate) state: Mutex<PoolState>,
    split_admission: Mutex<()>,
}

/// # Cancel safety:
/// conditionally cancel-safe: as for [`XhttpClient::connect`], the peer may
/// receive a partial request before cancellation releases local resources.
pub async fn connect_reality(config: &XhttpRealityConfig, target: &str) -> io::Result<impl AsyncIo + use<>> {
    XhttpClient::new_reality(config.clone()).connect(target).await
}

/// # Cancel safety:
/// conditionally cancel-safe: as for [`XhttpClient::connect`], the peer may
/// receive a partial request before cancellation releases local resources.
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

    /// # Cancel safety:
    /// conditionally cancel-safe: cancellation releases local resources, but the
    /// peer may already have received a partial HTTP request.
    pub async fn connect(&self, target: &str) -> io::Result<XhttpStream> {
        self.open_stream(&self.inner.mode.tcp_request(target)?).await
    }

    /// Open one XUDP association on its own xHTTP stream, as Xray does for
    /// UDP over a non-Vision VLESS transport: a destination-less VLESS `Mux`
    /// request followed by XUDP frames. Xray limits UDP/443 only for Vision,
    /// which cannot run over xHTTP, so UDP/443 is allowed.
    ///
    /// # Cancel safety:
    /// conditionally cancel-safe: as for [`XhttpClient::connect`], a partial HTTP
    /// request may reach the peer. XUDP tasks start only after the stream returns.
    pub async fn connect_xudp(&self) -> io::Result<VlessXudpSession> {
        let request = self.inner.mode.xudp_request()?;
        VlessXudpSession::new(self.open_stream(&request).await?, true)
    }

    /// # Cancel safety:
    /// conditionally cancel-safe: cancellation while awaiting stream opening
    /// releases reservations, permits and partial pumps; the peer may already
    /// have received a partial HTTP request.
    ///
    /// A plain fn returning the future keeps async nesting shallow enough for
    /// clippy's layout query on the public connect helpers.
    fn open_stream<'a>(&'a self, request: &'a [u8]) -> impl Future<Output = io::Result<XhttpStream>> + 'a {
        establish_with_deadline(STREAM_ESTABLISHMENT_TIMEOUT, async move {
            let mut split = false;
            loop {
                // Reserve both carriers in one order. A competing opener must not
                // hold the last download carrier while waiting for its upload.
                let split_admission = if split { Some(self.inner.split_admission.lock().await) } else { None };
                let (connection, permit) = if split {
                    Box::pin(pool::acquire_carrier(&self.inner, true, None)).await?
                } else {
                    Box::pin(pool::acquire_connection(&self.inner)).await?
                };
                if !split && connection.needs_split() {
                    match connection.check_stream_up_capacity().await {
                        Err(error) if error.kind() == io::ErrorKind::Unsupported && self.inner.max_connections >= 2 => {
                            split = true;
                            continue;
                        }
                        result => result?,
                    }
                }
                let upload = if split {
                    Some(Box::pin(pool::acquire_carrier(&self.inner, true, Some(&connection))).await?)
                } else {
                    None
                };
                drop(split_admission);
                if connection.opening_started() {
                    let result = connection.open_stream_from_mode(&self.inner.mode, request, permit, upload).await;
                    if result.as_ref().is_err_and(|error| error.kind() == io::ErrorKind::WouldBlock) {
                        continue;
                    }
                    if !split
                        && result.as_ref().is_err_and(|error| error.kind() == io::ErrorKind::Unsupported)
                        && connection.needs_split()
                        && self.inner.max_connections >= 2
                    {
                        // The GET discovered the peer limit. It carried no user bytes.
                        split = true;
                        continue;
                    }
                    break result;
                }
            }
        })
    }

    fn new(mode: XhttpMode, xmux: XmuxConfig) -> Self {
        Self {
            inner: Arc::new(XhttpClientInner {
                mode,
                max_connections: xmux.max_connections.max(1),
                carrier_capacity: Arc::new(Semaphore::new(xmux.max_connections.max(1))),
                max_concurrent_streams: xmux.max_concurrent_streams.max(1),
                creating_connections: Arc::new(AtomicUsize::new(0)),
                pool_changed: Arc::new(Notify::new()),
                state: Mutex::new(PoolState::default()),
                split_admission: Mutex::new(()),
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
