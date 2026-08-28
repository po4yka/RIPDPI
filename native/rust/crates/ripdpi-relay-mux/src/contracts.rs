use std::future::Future;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, Default)]
pub struct RelayCapabilities {
    pub tcp: bool,
    pub udp: bool,
    pub reusable: bool,
}

pub trait RelaySession: Send + Sync + 'static {
    type Stream: Send + 'static;
    type Datagram: Send + 'static;
    type Error: Send + Sync + 'static;

    // RPITIT (stable since 1.75) replaces the previous `BoxFuture` (boxed
    // `dyn Future`) return type: no per-call heap allocation on the relay data
    // path. The explicit `+ Send` bound preserves the Send guarantee that the
    // boxed `dyn Future + Send` provided -- `RelayMux` `.await`s these futures
    // inside tasks spawned on a multi-thread runtime. The trait is only ever
    // used via generic `F: RelaySessionFactory` bounds, never as `dyn`, so RPITIT
    // (which is not dyn-compatible) is safe here.
    fn open_stream(&self, target: &str) -> impl Future<Output = Result<Self::Stream, Self::Error>> + Send;

    fn open_datagram(&self) -> impl Future<Output = Result<Self::Datagram, Self::Error>> + Send;
}

pub trait RelaySessionFactory: Send + Sync + 'static {
    type Session: RelaySession<Error = Self::Error>;
    type Error: Send + Sync + 'static;

    fn capabilities(&self) -> RelayCapabilities;

    fn create_session(&self) -> impl Future<Output = Result<Arc<Self::Session>, Self::Error>> + Send;

    /// Stop and join factory-owned work, including evicted or abandoned sessions.
    /// The caller normally drains stream opens first. Factories retaining work
    /// must close admission before cleanup, including the forced-drain error path.
    ///
    /// # Cancel safety
    /// Implementations retain unfinished cleanup ownership if this future is dropped.
    fn shutdown(&self) -> impl Future<Output = Result<(), Self::Error>> + Send;
}
