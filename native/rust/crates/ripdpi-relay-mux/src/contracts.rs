use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

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

    fn open_stream<'a>(&'a self, target: &'a str) -> BoxFuture<'a, Result<Self::Stream, Self::Error>>;

    fn open_datagram(&self) -> BoxFuture<'_, Result<Self::Datagram, Self::Error>>;
}

pub trait RelaySessionFactory: Send + Sync + 'static {
    type Session: RelaySession<Error = Self::Error>;
    type Error: Send + Sync + 'static;

    fn capabilities(&self) -> RelayCapabilities;

    fn create_session(&self) -> BoxFuture<'_, Result<Arc<Self::Session>, Self::Error>>;
}
