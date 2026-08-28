use std::convert::Infallible;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio::io::duplex;
use tokio::sync::Notify;
use tokio::time::{sleep, timeout};

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

#[derive(Clone)]
struct ControlledCreateFactory {
    creations: Arc<AtomicUsize>,
    first_entered: Arc<Notify>,
    release_first: Arc<Notify>,
    first_never_completes: bool,
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
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

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
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

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

impl RelaySessionFactory for ControlledCreateFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = TestSession;
    type Error = Infallible;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let creation = self.creations.fetch_add(1, Ordering::SeqCst);
        if creation == 0 {
            self.first_entered.notify_one();
            if self.first_never_completes {
                std::future::pending::<()>().await;
            } else {
                self.release_first.notified().await;
            }
        }
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
async fn concurrent_cache_misses_share_one_session_creation() {
    let creations = Arc::new(AtomicUsize::new(0));
    let first_entered = Arc::new(Notify::new());
    let release_first = Arc::new(Notify::new());
    let mux = RelayMux::new(
        ControlledCreateFactory {
            creations: Arc::clone(&creations),
            first_entered: Arc::clone(&first_entered),
            release_first: Arc::clone(&release_first),
            first_never_completes: false,
        },
        RelayPoolConfig::default(),
    );

    let first_mux = mux.clone();
    let first = tokio::spawn(async move { first_mux.open_stream("first.example:443").await });
    first_entered.notified().await;
    let second_mux = mux.clone();
    let second = tokio::spawn(async move { second_mux.open_stream("second.example:443").await });
    tokio::task::yield_now().await;
    assert_eq!(creations.load(Ordering::SeqCst), 1);

    release_first.notify_one();
    drop(first.await.expect("first opener task").expect("first stream"));
    drop(second.await.expect("second opener task").expect("second stream"));

    assert_eq!(creations.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn cancelling_cache_miss_owner_unblocks_next_creator() {
    let creations = Arc::new(AtomicUsize::new(0));
    let first_entered = Arc::new(Notify::new());
    let mux = RelayMux::new(
        ControlledCreateFactory {
            creations: Arc::clone(&creations),
            first_entered: Arc::clone(&first_entered),
            release_first: Arc::new(Notify::new()),
            first_never_completes: true,
        },
        RelayPoolConfig::default(),
    );

    let owner_mux = mux.clone();
    let owner = tokio::spawn(async move { owner_mux.open_stream("owner.example:443").await });
    first_entered.notified().await;
    let waiter_mux = mux.clone();
    let waiter = tokio::spawn(async move { waiter_mux.open_stream("waiter.example:443").await });
    tokio::task::yield_now().await;

    owner.abort();
    let Err(error) = owner.await else {
        panic!("owner must be cancelled");
    };
    assert!(error.is_cancelled());
    let stream = timeout(Duration::from_secs(1), waiter)
        .await
        .expect("waiter must acquire released creation gate")
        .expect("waiter task")
        .expect("waiter stream");
    drop(stream);

    assert_eq!(creations.load(Ordering::SeqCst), 2);
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

/// A cached carrier that the server/NAT closed during the idle window:
/// its FIRST open succeeded (so it got cached), but every later open fails
/// until the mux evicts it and dials a fresh session.
#[derive(Clone)]
struct StaleFirstCarrierFactory {
    creations: Arc<AtomicUsize>,
}

struct StaleFirstCarrierSession {
    generation: usize,
    opens: AtomicUsize,
}

impl RelaySession for StaleFirstCarrierSession {
    type Stream = tokio::io::DuplexStream;
    type Datagram = usize;
    type Error = std::io::Error;

    async fn open_stream(&self, _target: &str) -> Result<Self::Stream, Self::Error> {
        if self.generation == 0 && self.opens.fetch_add(1, Ordering::SeqCst) == 0 {
            Ok(duplex(64).0)
        } else if self.generation == 0 {
            Err(std::io::Error::new(std::io::ErrorKind::ConnectionReset, "carrier closed by peer"))
        } else {
            Ok(duplex(64).0)
        }
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        Ok(7)
    }
}

impl RelaySessionFactory for StaleFirstCarrierFactory {
    async fn shutdown(&self) -> Result<(), Self::Error> {
        Ok(())
    }

    type Session = StaleFirstCarrierSession;
    type Error = std::io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: true, reusable: true }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let generation = self.creations.fetch_add(1, Ordering::SeqCst);
        Ok(Arc::new(StaleFirstCarrierSession { generation, opens: AtomicUsize::new(0) }))
    }
}

#[tokio::test]
async fn stale_cached_carrier_failure_is_retried_once_on_a_fresh_session() {
    let creations = Arc::new(AtomicUsize::new(0));
    let mux = RelayMux::new(StaleFirstCarrierFactory { creations: Arc::clone(&creations) }, RelayPoolConfig::default());

    // Warm the cache: the first carrier opens fine and is reused afterward.
    let warm = mux.open_stream("example.com:443").await.expect("warm-up open");
    drop(warm);
    assert_eq!(1, creations.load(Ordering::SeqCst));

    // The peer closed the carrier meanwhile: this lease hits the CACHE and
    // must be transparently retried on a freshly created session.
    let stream = mux
        .open_stream("example.com:443")
        .await
        .expect("a stale cached carrier must be retried on a fresh session, not surfaced");
    drop(stream);

    assert_eq!(2, creations.load(Ordering::SeqCst), "exactly one fresh-carrier retry must happen");
}

#[tokio::test]
async fn fresh_carrier_failure_surfaces_without_a_retry() {
    // A factory whose EVERY carrier fails: from_cache is false for a freshly
    // created session, so no retry may run — otherwise a hard outage would
    // double the dial cost per request.
    struct AlwaysFailingFactory;
    struct AlwaysFailingSession;

    impl RelaySession for AlwaysFailingSession {
        type Stream = tokio::io::DuplexStream;
        type Datagram = usize;
        type Error = std::io::Error;

        async fn open_stream(&self, _target: &str) -> Result<Self::Stream, Self::Error> {
            Err(std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "relay unreachable"))
        }

        async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
            Err(std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "relay unreachable"))
        }
    }

    impl RelaySessionFactory for AlwaysFailingFactory {
        async fn shutdown(&self) -> Result<(), Self::Error> {
            Ok(())
        }

        type Session = AlwaysFailingSession;
        type Error = std::io::Error;

        fn capabilities(&self) -> RelayCapabilities {
            RelayCapabilities { tcp: true, udp: true, reusable: true }
        }

        async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
            Ok(Arc::new(AlwaysFailingSession))
        }
    }

    let mux = RelayMux::new(AlwaysFailingFactory, RelayPoolConfig::default());
    let Err(error) = mux.open_stream("example.com:443").await else {
        panic!("a failing fresh carrier must surface its error");
    };
    assert_eq!(std::io::ErrorKind::ConnectionRefused, error.kind());
}

#[tokio::test]
async fn shutdown_waits_for_factory_and_evicts_only_after_cleanup() {
    #[derive(Clone)]
    struct ClosingFactory {
        entered: Arc<Notify>,
        release: Arc<Notify>,
    }
    impl RelaySessionFactory for ClosingFactory {
        type Session = TestSession;
        type Error = Infallible;
        fn capabilities(&self) -> RelayCapabilities {
            RelayCapabilities { tcp: true, udp: true, reusable: true }
        }
        async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
            Ok(Arc::new(TestSession))
        }
        async fn shutdown(&self) -> Result<(), Self::Error> {
            self.entered.notify_one();
            self.release.notified().await;
            Ok(())
        }
    }
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let mux = RelayMux::new(
        ClosingFactory { entered: entered.clone(), release: release.clone() },
        RelayPoolConfig::default(),
    );
    drop(mux.open_stream("interop.invalid:443").await.expect("open"));
    let closing_mux = mux.clone();
    let closing = tokio::spawn(async move { closing_mux.shutdown().await });
    let observed = timeout(Duration::from_millis(100), entered.notified()).await;
    if observed.is_err() {
        closing.abort();
        let _ = closing.await;
        panic!("shutdown must invoke factory cleanup");
    }
    assert!(!closing.is_finished(), "must await factory-owned work");
    assert_eq!(mux.health().idle_streams, 1, "retain cached owner during cleanup");
    release.notify_one();
    closing.await.expect("shutdown worker").expect("shutdown");
    assert_eq!(mux.health().idle_streams, 0, "evict only after cleanup");
}
