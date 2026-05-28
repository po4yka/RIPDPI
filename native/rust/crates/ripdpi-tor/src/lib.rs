#![forbid(unsafe_code)]

use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Once;

use arti_client::{config::TorClientConfigBuilder, IntoTorAddr, TorClient as ArtiTorClient};
use tokio::io::{AsyncRead, AsyncWrite};
use tor_rtcompat::PreferredRuntime;

pub use arti_client::config::TorClientConfig as ArtiTorClientConfig;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub type BoxedIo = Box<dyn AsyncIo>;

static RUSTLS_PROVIDER: Once = Once::new();

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

#[derive(Debug, thiserror::Error)]
pub enum TorConfigError {
    #[error("failed to read Arti config {path}: {source}")]
    Read {
        path: PathBuf,
        #[source]
        source: io::Error,
    },
    #[error("failed to parse Arti config TOML {path}: {source}")]
    ParseToml {
        path: PathBuf,
        #[source]
        source: toml::de::Error,
    },
    #[error("failed to deserialize Arti config {path}: {message}")]
    Deserialize { path: PathBuf, message: String },
    #[error("failed to build Arti config {path}: {source}")]
    Build {
        path: PathBuf,
        #[source]
        source: arti_client::config::ConfigBuildError,
    },
}

pub fn load_arti_config_from_toml(path: impl AsRef<Path>) -> Result<ArtiTorClientConfig, TorConfigError> {
    let path = path.as_ref().to_path_buf();
    let config = fs::read_to_string(&path).map_err(|source| TorConfigError::Read { path: path.clone(), source })?;
    let value: toml::Value =
        toml::from_str(&config).map_err(|source| TorConfigError::ParseToml { path: path.clone(), source })?;
    let builder: TorClientConfigBuilder = value.try_into().map_err(|source: toml::de::Error| {
        TorConfigError::Deserialize { path: path.clone(), message: source.to_string() }
    })?;
    builder.build().map_err(|source| TorConfigError::Build { path, source })
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
        ensure_rustls_crypto_provider();
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

fn ensure_rustls_crypto_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}
