#![forbid(unsafe_code)]

use std::fmt;
use std::io;

use arti_client::{IntoTorAddr, TorClient as ArtiTorClient};
use tokio::io::{AsyncRead, AsyncWrite};
use tor_rtcompat::PreferredRuntime;

pub use arti_client::config::TorClientConfig as ArtiTorClientConfig;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub type BoxedIo = Box<dyn AsyncIo>;

#[derive(Debug, thiserror::Error)]
pub enum TorTargetError {
    #[error("invalid Tor target {host}:{port}: {source}")]
    InvalidTarget {
        host: String,
        port: u16,
        #[source]
        source: arti_client::TorAddrError,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct TorTarget {
    host: String,
    port: u16,
}

impl TorTarget {
    pub fn new(host: impl Into<String>, port: u16) -> Result<Self, TorTargetError> {
        let host = host.into();
        (&host[..], port).into_tor_addr().map_err(|source| TorTargetError::InvalidTarget {
            host: host.clone(),
            port,
            source,
        })?;
        Ok(Self { host, port })
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn as_arti_target(&self) -> (&str, u16) {
        (&self.host, self.port)
    }
}

impl fmt::Display for TorTarget {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.host, self.port)
    }
}

pub struct TorRelayClient {
    inner: ArtiTorClient<PreferredRuntime>,
}

impl TorRelayClient {
    pub async fn create_bootstrapped(config: ArtiTorClientConfig) -> arti_client::Result<Self> {
        let inner = ArtiTorClient::create_bootstrapped(config).await?;
        Ok(Self { inner })
    }

    pub fn from_arti_client(inner: ArtiTorClient<PreferredRuntime>) -> Self {
        Self { inner }
    }

    pub async fn connect_tcp(&self, target: &TorTarget) -> io::Result<BoxedIo> {
        let stream = self
            .inner
            .connect(target.as_arti_target())
            .await
            .map_err(|error| io::Error::other(format!("Tor TCP connect to {target} failed: {error}")))?;
        Ok(Box::new(stream))
    }
}
