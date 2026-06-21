use std::convert::Infallible;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio::io::duplex;
use tokio::sync::Notify;
use tokio::time::sleep;

use crate::{RelayCapabilities, RelayMux, RelayPoolConfig, RelaySession, RelaySessionFactory};

#[derive(Clone)]
struct TestFactory {
    creations: Arc<AtomicUsize>,
    reusable: bool,
}

struct TestSession;

impl RelaySession for TestSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = usize;
    type Error = Infallible;

    async fn open_stream(&self, _target: &str) -> Result<Self::Stream, Self::Error> {
        Ok(duplex(64).0)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Ok(7)
    }
}

#[derive(Clone)]
struct PendingOpenFactory {
    entered_open: Arc<Notify>,
}

struct PendingOpenSession {
    entered_open: Arc<Notify>,
}

impl RelaySession for PendingOpenSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = usize;
    type Error = Infallible;

    async fn open_stream(&self, _target: &str) -> Result<Self::Stream, Self::Error> {
        self.entered_open.notify_one();
        std::future::pending().await
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Ok(7)
    }
}

impl RelaySessionFactory for PendingOpenFactory {
    type Session = PendingOpenSession;
    type Error = Infallible;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        Ok(Arc::new(PendingOpenSession { entered_open: Arc::clone(&self.entered_open) }))
    }
}

impl RelaySessionFactory for TestFactory {
    type Session = TestSession;
    type Error = Infallible;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: self.reusable }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        self.creations.fetch_add(1, Ordering::SeqCst);
        Ok(Arc::new(TestSession))
    }
}

#[tokio::test]
async fn reusable_mux_reuses_cached_session() {
    let creations = Arc::new(AtomicUsize::new(0));
    let mux =
        RelayMux::new(TestFactory { creations: Arc::clone(&creations), reusable: true }, RelayPoolConfig::default());

    drop(mux.open_stream("example.com:443").await.expect("first stream"));
    drop(mux.open_stream("example.com:443").await.expect("second stream"));

    assert_eq!(1, creations.load(Ordering::SeqCst));
    assert_eq!(1, mux.health().idle_streams);
}

#[tokio::test]
async fn non_reusable_mux_creates_fresh_session_per_open() {
    let creations = Arc::new(AtomicUsize::new(0));
    let mux =
        RelayMux::new(TestFactory { creations: Arc::clone(&creations), reusable: false }, RelayPoolConfig::default());

    drop(mux.open_stream("example.com:443").await.expect("first stream"));
    drop(mux.open_stream("example.com:443").await.expect("second stream"));

    assert_eq!(2, creations.load(Ordering::SeqCst));
    assert_eq!(0, mux.health().idle_streams);
}

#[tokio::test]
async fn mux_evicts_idle_reusable_session() {
    let mux = RelayMux::new(
        TestFactory { creations: Arc::new(AtomicUsize::new(0)), reusable: true },
        RelayPoolConfig { max_active_leases: 4, idle_timeout: Duration::from_millis(5) },
    );

    drop(mux.open_stream("example.com:443").await.expect("stream"));
    sleep(Duration::from_millis(10)).await;

    let health = mux.health();
    assert_eq!(0, health.idle_streams);
    assert_eq!(1, health.evictions);
}

#[tokio::test]
async fn mux_records_backpressure_when_limit_is_exhausted() {
    let mux = RelayMux::new(
        TestFactory { creations: Arc::new(AtomicUsize::new(0)), reusable: true },
        RelayPoolConfig { max_active_leases: 1, idle_timeout: Duration::from_secs(30) },
    );

    let first = mux.open_stream("example.com:443").await.expect("first stream");
    let waiter_mux = mux.clone();
    let waiter = tokio::spawn(async move { waiter_mux.open_stream("example.com:443").await.expect("queued stream") });

    sleep(Duration::from_millis(10)).await;
    assert_eq!(1, mux.health().backpressure_events);

    drop(first);
    drop(waiter.await.expect("waiter join"));
}

#[tokio::test]
async fn cancelling_pending_stream_open_releases_lease_accounting() {
    let entered_open = Arc::new(Notify::new());
    let mux = RelayMux::new(
        PendingOpenFactory { entered_open: Arc::clone(&entered_open) },
        RelayPoolConfig { max_active_leases: 1, idle_timeout: Duration::from_secs(30) },
    );

    let opener_mux = mux.clone();
    let opener = tokio::spawn(async move {
        let _ = opener_mux.open_stream("example.com:443").await;
    });
    entered_open.notified().await;
    assert_eq!(1, mux.health().busy_streams);

    opener.abort();
    assert!(opener.await.expect_err("aborted open task").is_cancelled());

    assert_eq!(0, mux.health().busy_streams);
    let datagram = mux.open_datagram().await.expect("cancelled open must release pool capacity");
    assert_eq!(7, *datagram.get_ref());
    assert_eq!(1, mux.health().busy_streams);
    drop(datagram);
    assert_eq!(0, mux.health().busy_streams);
}

#[tokio::test]
async fn lease_guard_drop_does_not_panic_when_mutex_is_poisoned() {
    // Reproduces the panic-in-Drop bug: a poisoned state mutex must not cause
    // `LeaseGuard::drop` to panic (which would abort the process during
    // unwinding on stable Rust). Lease accounting is advisory, so the poison
    // is recovered via `into_inner` rather than propagated.
    let mux = RelayMux::new(
        TestFactory { creations: Arc::new(AtomicUsize::new(0)), reusable: true },
        RelayPoolConfig::default(),
    );

    // Acquire a lease so `active_leases` becomes 1.
    let guard = mux.open_stream("example.com:443").await.expect("stream");

    // Poison the mutex while the guard is still live.
    mux.poison_for_test();

    // Dropping the guard over a poisoned mutex must not panic/abort.
    // If `LeaseGuard::drop` still used `.expect(...)` this line would abort.
    drop(guard);

    // The recovery paths in `health()` must also not panic after poison.
    let _ = mux.health();
}
