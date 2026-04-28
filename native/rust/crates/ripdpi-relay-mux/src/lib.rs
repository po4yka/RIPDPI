//! Relay session multiplexer for TCP streams and UDP datagrams.
//!
//! [`RelaySession`] is a unified abstraction covering both connection types:
//!
//! - [`RelaySession::open_stream`] — opens a proxied TCP stream to a target address.
//! - [`RelaySession::open_datagram`] — opens a UDP datagram session through the relay.
//!   Backends that are TCP-only (VLESS Reality, xHTTP, ChainRelay, ShadowTLS) return
//!   `io::ErrorKind::Unsupported` and advertise `RelayCapabilities { udp: false }`.
//!   Callers must check [`RelayCapabilities::udp`] before invoking this method.
//!
//! The `UdpSocket` used in `ripdpi-relay-core` for SOCKS5 UDP ASSOCIATE is the
//! **local inbound half** of that flow (binding on the device's loopback/LAN
//! interface to receive datagrams from the Android client). The outbound half
//! — towards the relay server — goes through `RelayMux::open_datagram` →
//! `RelaySession::open_datagram`. The two sockets are complementary, not
//! redundant. See ADR-007 for the full rationale.

#![forbid(unsafe_code)]

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

#[derive(Debug, Clone, Copy, Default)]
pub struct RelayCapabilities {
    pub tcp: bool,
    pub udp: bool,
    pub reusable: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct RelayPoolConfig {
    pub max_active_leases: usize,
    pub idle_timeout: Duration,
}

impl Default for RelayPoolConfig {
    fn default() -> Self {
        Self { max_active_leases: 64, idle_timeout: Duration::from_secs(30) }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct RelayPoolHealth {
    pub idle_streams: usize,
    pub busy_streams: usize,
    pub evictions: u64,
    pub idle_timeout: Duration,
    pub backpressure_events: u64,
}

impl Default for RelayPoolHealth {
    fn default() -> Self {
        Self {
            idle_streams: 0,
            busy_streams: 0,
            evictions: 0,
            idle_timeout: Duration::from_secs(30),
            backpressure_events: 0,
        }
    }
}

pub trait RelaySession: Send + Sync + 'static {
    type Stream: Send + 'static;
    type Datagram: Send + 'static;
    type Error: Send + Sync + 'static;

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>>;

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>>;
}

pub trait RelaySessionFactory: Send + Sync + 'static {
    type Session: RelaySession<Error = Self::Error>;
    type Error: Send + Sync + 'static;

    fn capabilities(&self) -> RelayCapabilities;

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>>;
}

#[derive(Clone)]
pub struct RelayMux<F>
where
    F: RelaySessionFactory,
{
    inner: Arc<RelayMuxInner<F>>,
}

struct RelayMuxInner<F>
where
    F: RelaySessionFactory,
{
    factory: F,
    capabilities: RelayCapabilities,
    config: RelayPoolConfig,
    permits: Arc<Semaphore>,
    state: Arc<Mutex<RelayMuxState<F::Session>>>,
}

struct RelayMuxState<S>
where
    S: RelaySession,
{
    cached_session: Option<CachedSession<S>>,
    active_leases: usize,
    evictions: u64,
    backpressure_events: u64,
}

struct CachedSession<S>
where
    S: RelaySession,
{
    session: Arc<S>,
    idle_since: Instant,
}

pub struct MuxStream<T, S>
where
    S: RelaySession,
{
    inner: T,
    _guard: LeaseGuard<S>,
}

pub struct MuxLease<T, S>
where
    S: RelaySession,
{
    inner: T,
    _guard: LeaseGuard<S>,
}

struct LeaseGuard<S>
where
    S: RelaySession,
{
    state: Arc<Mutex<RelayMuxState<S>>>,
    session: Option<Arc<S>>,
    reusable: bool,
    _permit: OwnedSemaphorePermit,
}

impl<F> RelayMux<F>
where
    F: RelaySessionFactory,
{
    pub fn new(factory: F, config: RelayPoolConfig) -> Self {
        let max_active_leases = config.max_active_leases.max(1);
        Self {
            inner: Arc::new(RelayMuxInner {
                capabilities: factory.capabilities(),
                factory,
                config,
                permits: Arc::new(Semaphore::new(max_active_leases)),
                state: Arc::new(Mutex::new(RelayMuxState {
                    cached_session: None,
                    active_leases: 0,
                    evictions: 0,
                    backpressure_events: 0,
                })),
            }),
        }
    }

    pub fn capabilities(&self) -> RelayCapabilities {
        self.inner.capabilities
    }

    pub fn health(&self) -> RelayPoolHealth {
        let mut state = self.inner.state.lock().expect("relay mux state");
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        RelayPoolHealth {
            idle_streams: usize::from(
                self.inner.capabilities.reusable && state.active_leases == 0 && state.cached_session.is_some(),
            ),
            busy_streams: state.active_leases,
            evictions: state.evictions,
            idle_timeout: self.inner.config.idle_timeout,
            backpressure_events: state.backpressure_events,
        }
    }

    pub async fn open_stream(
        &self,
        target: &str,
    ) -> Result<MuxStream<<F::Session as RelaySession>::Stream, F::Session>, F::Error> {
        let permit = self.acquire_permit().await;
        let session = self.session_for_open().await?;
        self.mark_lease_started();

        match session.open_stream(target).await {
            Ok(stream) => Ok(MuxStream {
                inner: stream,
                _guard: LeaseGuard::new(self.inner.state.clone(), session, self.inner.capabilities.reusable, permit),
            }),
            Err(error) => {
                self.finish_failed_open(Some(&session));
                Err(error)
            }
        }
    }

    pub async fn open_datagram(
        &self,
    ) -> Result<MuxLease<<F::Session as RelaySession>::Datagram, F::Session>, F::Error> {
        let permit = self.acquire_permit().await;
        let session = self.session_for_open().await?;
        self.mark_lease_started();

        match session.open_datagram().await {
            Ok(datagram) => Ok(MuxLease {
                inner: datagram,
                _guard: LeaseGuard::new(self.inner.state.clone(), session, self.inner.capabilities.reusable, permit),
            }),
            Err(error) => {
                self.finish_failed_open(Some(&session));
                Err(error)
            }
        }
    }

    async fn acquire_permit(&self) -> OwnedSemaphorePermit {
        if let Ok(permit) = self.inner.permits.clone().try_acquire_owned() {
            return permit;
        }

        self.inner.state.lock().expect("relay mux state").backpressure_events += 1;
        self.inner.permits.clone().acquire_owned().await.expect("relay mux semaphore unexpectedly closed")
    }

    async fn session_for_open(&self) -> Result<Arc<F::Session>, F::Error> {
        if !self.inner.capabilities.reusable {
            return self.inner.factory.create_session().await;
        }

        {
            let mut state = self.inner.state.lock().expect("relay mux state");
            prune_expired_session(&mut state, self.inner.config.idle_timeout);
            if let Some(session) = state.cached_session.as_ref().map(|cached| Arc::clone(&cached.session)) {
                return Ok(session);
            }
        }

        let created = self.inner.factory.create_session().await?;
        let mut state = self.inner.state.lock().expect("relay mux state");
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        if let Some(session) = state.cached_session.as_ref().map(|cached| Arc::clone(&cached.session)) {
            return Ok(session);
        }
        state.cached_session = Some(CachedSession { session: Arc::clone(&created), idle_since: Instant::now() });
        Ok(created)
    }

    fn mark_lease_started(&self) {
        let mut state = self.inner.state.lock().expect("relay mux state");
        prune_expired_session(&mut state, self.inner.config.idle_timeout);
        state.active_leases += 1;
    }

    fn finish_failed_open(&self, session: Option<&Arc<F::Session>>) {
        let mut state = self.inner.state.lock().expect("relay mux state");
        if state.active_leases > 0 {
            state.active_leases -= 1;
        }
        if let Some(session) = session {
            invalidate_cached_session(&mut state, session);
        }
    }
}

impl<T, S> MuxLease<T, S>
where
    S: RelaySession,
{
    pub fn get_ref(&self) -> &T {
        &self.inner
    }

    pub fn get_mut(&mut self) -> &mut T {
        &mut self.inner
    }
}

impl<T, S> AsyncRead for MuxStream<T, S>
where
    T: AsyncRead + Unpin,
    S: RelaySession,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}

impl<T, S> AsyncWrite for MuxStream<T, S>
where
    T: AsyncWrite + Unpin,
    S: RelaySession,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

impl<S> LeaseGuard<S>
where
    S: RelaySession,
{
    fn new(state: Arc<Mutex<RelayMuxState<S>>>, session: Arc<S>, reusable: bool, permit: OwnedSemaphorePermit) -> Self {
        Self { state, session: Some(session), reusable, _permit: permit }
    }
}

impl<S> Drop for LeaseGuard<S>
where
    S: RelaySession,
{
    fn drop(&mut self) {
        let mut state = self.state.lock().expect("relay mux state");
        if state.active_leases > 0 {
            state.active_leases -= 1;
        }
        if self.reusable {
            let no_active_leases = state.active_leases == 0;
            if let (Some(session), Some(cached)) = (&self.session, state.cached_session.as_mut()) {
                if Arc::ptr_eq(session, &cached.session) && no_active_leases {
                    cached.idle_since = Instant::now();
                }
            }
        }
    }
}

fn prune_expired_session<S>(state: &mut RelayMuxState<S>, idle_timeout: Duration)
where
    S: RelaySession,
{
    let should_evict = state.active_leases == 0
        && state.cached_session.as_ref().is_some_and(|cached| cached.idle_since.elapsed() >= idle_timeout);
    if should_evict {
        state.cached_session = None;
        state.evictions += 1;
    }
}

fn invalidate_cached_session<S>(state: &mut RelayMuxState<S>, session: &Arc<S>)
where
    S: RelaySession,
{
    if state.cached_session.as_ref().is_some_and(|cached| Arc::ptr_eq(&cached.session, session)) {
        state.cached_session = None;
        state.evictions += 1;
    }
}

#[cfg(test)]
mod tests {
    use std::convert::Infallible;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use tokio::io::duplex;
    use tokio::time::sleep;

    use super::*;

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

        fn open_stream<'a>(&'a self, _target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>> {
            Box::pin(async move { Ok(duplex(64).0) })
        }

        fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>> {
            Box::pin(async move { Ok(7) })
        }
    }

    impl RelaySessionFactory for TestFactory {
        type Session = TestSession;
        type Error = Infallible;

        fn capabilities(&self) -> RelayCapabilities {
            RelayCapabilities { tcp: true, udp: true, reusable: self.reusable }
        }

        fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>> {
            self.creations.fetch_add(1, Ordering::SeqCst);
            Box::pin(async move { Ok(Arc::new(TestSession)) })
        }
    }

    #[tokio::test]
    async fn reusable_mux_reuses_cached_session() {
        let creations = Arc::new(AtomicUsize::new(0));
        let mux = RelayMux::new(
            TestFactory { creations: Arc::clone(&creations), reusable: true },
            RelayPoolConfig::default(),
        );

        drop(mux.open_stream("example.com:443").await.expect("first stream"));
        drop(mux.open_stream("example.com:443").await.expect("second stream"));

        assert_eq!(1, creations.load(Ordering::SeqCst));
        assert_eq!(1, mux.health().idle_streams);
    }

    #[tokio::test]
    async fn non_reusable_mux_creates_fresh_session_per_open() {
        let creations = Arc::new(AtomicUsize::new(0));
        let mux = RelayMux::new(
            TestFactory { creations: Arc::clone(&creations), reusable: false },
            RelayPoolConfig::default(),
        );

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
        let waiter =
            tokio::spawn(async move { waiter_mux.open_stream("example.com:443").await.expect("queued stream") });

        sleep(Duration::from_millis(10)).await;
        assert_eq!(1, mux.health().backpressure_events);

        drop(first);
        drop(waiter.await.expect("waiter join"));
    }
}
