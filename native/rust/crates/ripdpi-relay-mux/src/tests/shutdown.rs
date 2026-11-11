use std::sync::Arc;
use std::sync::atomic::AtomicUsize;
use std::time::Duration;

use tokio::sync::Notify;
use tokio::time::timeout;

use super::{ControlledCreateFactory, PendingOpenFactory, TestFactory, TestSession};
use crate::{RelayCapabilities, RelayMux, RelayOpenError, RelayPoolConfig, RelaySessionFactory};

/// # Cancel safety:
/// cancel-safe: the test drops every lease and waits for shutdown before returning.
#[tokio::test]
async fn terminal_shutdown_rejects_new_stream_and_datagram_leases() {
    let mux = RelayMux::new(
        TestFactory { creations: Arc::new(AtomicUsize::new(0)), reusable: true },
        RelayPoolConfig::default(),
    );
    let held = mux.open_stream("loopback:1").await.expect("established stream");
    timeout(Duration::from_secs(1), mux.shutdown())
        .await
        .expect("shutdown must not await an established lease")
        .expect("shutdown");
    assert!(matches!(mux.open_stream("loopback:1").await, Err(RelayOpenError::Closed)));
    assert!(matches!(mux.open_datagram().await, Err(RelayOpenError::Closed)));
    assert_eq!(mux.health().busy_streams, 1);
    drop(held);
    assert_eq!(mux.health().busy_streams, 0);
}

/// # Cancel safety:
/// cancel-safe: shutdown cancels the constructor and waiter; both futures are joined.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn shutdown_cancels_a_constructor_and_queued_lease_without_publishing_cache() {
    let entered = Arc::new(Notify::new());
    let mux = RelayMux::new(
        ControlledCreateFactory {
            creations: Arc::new(AtomicUsize::new(0)),
            first_entered: entered.clone(),
            release_first: Arc::new(Notify::new()),
            first_never_completes: true,
        },
        RelayPoolConfig { max_active_leases: 1, ..RelayPoolConfig::default() },
    );
    let owner_mux = mux.clone();
    let owner = tokio::spawn(async move { owner_mux.open_stream("loopback:1").await });
    entered.notified().await;
    let waiter_mux = mux.clone();
    let waiter = tokio::spawn(async move { waiter_mux.open_datagram().await });
    tokio::task::yield_now().await;

    timeout(Duration::from_secs(1), mux.shutdown())
        .await
        .expect("shutdown must cancel incomplete opens")
        .expect("shutdown");
    assert!(matches!(owner.await.expect("owner task"), Err(RelayOpenError::Closed)));
    assert!(matches!(waiter.await.expect("waiter task"), Err(RelayOpenError::Closed)));
    let health = mux.health();
    assert_eq!((health.busy_streams, health.idle_streams), (0, 0));
}

/// # Cancel safety:
/// cancel-safe: shutdown cancels and joins the pending open task.
#[tokio::test]
async fn shutdown_cancels_an_inflight_session_open() {
    let entered = Arc::new(Notify::new());
    let mux = RelayMux::new(PendingOpenFactory { entered_open: entered.clone() }, RelayPoolConfig::default());
    let opening_mux = mux.clone();
    let opening = tokio::spawn(async move { opening_mux.open_stream("loopback:1").await });
    entered.notified().await;
    timeout(Duration::from_secs(1), mux.shutdown()).await.expect("pending open drain").expect("shutdown");
    assert!(matches!(opening.await.expect("opening task"), Err(RelayOpenError::Closed)));
    assert_eq!(mux.health().busy_streams, 0);
}

#[derive(Clone)]
struct ClosingFactory {
    entered: Arc<Notify>,
    release: Arc<Notify>,
}

impl RelaySessionFactory for ClosingFactory {
    type Session = TestSession;
    type Error = std::convert::Infallible;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    /// # Cancel safety:
    /// cancel-safe: construction has no suspension point or owned task.
    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        Ok(Arc::new(TestSession))
    }

    /// # Cancel safety:
    /// cancel-safe: unfinished cleanup stays represented by this factory's release gate.
    async fn shutdown(&self) -> Result<(), Self::Error> {
        self.entered.notify_one();
        self.release.notified().await;
        Ok(())
    }
}

/// # Cancel safety:
/// cancel-safe: the first cleanup future is joined after abort and the retry is completed.
#[tokio::test]
async fn cancelled_shutdown_stays_closed_and_a_retry_completes_cleanup() {
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let mux = RelayMux::new(
        ClosingFactory { entered: entered.clone(), release: release.clone() },
        RelayPoolConfig::default(),
    );
    drop(mux.open_datagram().await.expect("warm cache"));
    let stopping_mux = mux.clone();
    let stopping = tokio::spawn(async move { stopping_mux.shutdown().await });
    entered.notified().await;
    stopping.abort();
    assert!(stopping.await.expect_err("cleanup was aborted").is_cancelled());
    assert!(matches!(mux.open_datagram().await, Err(RelayOpenError::Closed)));
    release.notify_one();
    timeout(Duration::from_secs(1), mux.shutdown()).await.expect("retry cleanup").expect("shutdown retry");
    assert_eq!(mux.health().idle_streams, 0);
}

struct HealthWake {
    checked: std::sync::atomic::AtomicBool,
    healthy: std::sync::atomic::AtomicBool,
    request: std::sync::mpsc::Sender<()>,
    response: std::sync::Mutex<std::sync::mpsc::Receiver<usize>>,
}

impl HealthWake {
    fn observe(&self) {
        use std::sync::atomic::Ordering;
        if !self.checked.swap(true, Ordering::SeqCst) {
            self.request.send(()).expect("health worker alive");
            let health = self.response.lock().expect("health response").recv_timeout(Duration::from_secs(2));
            self.healthy.store(health == Ok(1), Ordering::SeqCst);
        }
    }
}

impl std::task::Wake for HealthWake {
    fn wake(self: Arc<Self>) {
        self.observe();
    }
    fn wake_by_ref(self: &Arc<Self>) {
        self.observe();
    }
}

/// # Cancel safety:
/// cancel-safe: the pending open is dropped, shutdown is completed, and the health worker is joined.
#[tokio::test]
async fn shutdown_wakeup_allows_health_to_complete_before_the_waker_returns() {
    use std::future::Future;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::task::{Context, Poll, Waker};

    let mux = RelayMux::new(
        TestFactory { creations: Arc::new(AtomicUsize::new(0)), reusable: true },
        RelayPoolConfig { max_active_leases: 1, ..RelayPoolConfig::default() },
    );
    let held = mux.open_datagram().await.expect("occupy admission");
    let (request, requested) = std::sync::mpsc::channel();
    let (respond, response) = std::sync::mpsc::channel();
    let observed_mux = mux.clone();
    let health_worker = std::thread::spawn(move || {
        requested.recv().expect("wakeup requests health");
        let _ = respond.send(observed_mux.health().busy_streams);
    });
    let wake = Arc::new(HealthWake {
        checked: AtomicBool::new(false),
        healthy: AtomicBool::new(false),
        request,
        response: std::sync::Mutex::new(response),
    });
    let waker = Waker::from(wake.clone());
    let mut opening = Box::pin(mux.open_datagram());
    assert!(opening.as_mut().poll(&mut Context::from_waker(&waker)).is_pending());
    let mut stopping = Box::pin(mux.shutdown());
    std::future::poll_fn(|context| {
        assert!(stopping.as_mut().poll(context).is_pending());
        Poll::Ready(())
    })
    .await;
    drop(opening);
    stopping.await.expect("shutdown");
    health_worker.join().expect("health worker");
    assert!(wake.checked.load(Ordering::SeqCst), "pending open was woken");
    assert!(wake.healthy.load(Ordering::SeqCst), "health must complete before the wake callback returns");
    drop(held);
}
