use std::io;
use std::sync::Arc;
use std::sync::atomic::AtomicUsize;

use tokio::sync::Mutex;

use crate::config::{AsyncIo, XhttpMode, XhttpRealityConfig, XhttpTlsConfig, XmuxConfig};
use crate::pool::{self, PoolState};
use crate::relay::XhttpStream;

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

pub async fn connect_reality(config: &XhttpRealityConfig, target: &str) -> io::Result<impl AsyncIo + use<>> {
    XhttpClient::new_reality(config.clone()).connect(target).await
}

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

    pub async fn connect(&self, target: &str) -> io::Result<XhttpStream> {
        let (connection, permit) = pool::acquire_connection(&self.inner).await?;
        connection.open_stream_from_mode(&self.inner.mode, target, permit).await
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
