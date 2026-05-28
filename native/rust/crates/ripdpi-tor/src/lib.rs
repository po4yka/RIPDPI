#![forbid(unsafe_code)]

use std::fmt;
use std::fs;
use std::io;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Once;
use std::{collections::BTreeSet, result::Result as StdResult};

use arti_client::{
    config::{
        pt::TransportConfigBuilder, BoolOrAuto, BridgeConfigBuilder, CfgPath, PtTransportName, TorClientConfigBuilder,
    },
    IntoTorAddr, TorClient as ArtiTorClient,
};
use tokio::io::{AsyncRead, AsyncWrite};
use tor_rtcompat::PreferredRuntime;

pub use arti_client::config::TorClientConfig as ArtiTorClientConfig;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub type BoxedIo = Box<dyn AsyncIo>;

static RUSTLS_PROVIDER: Once = Once::new();

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TorPluggableTransport {
    pub protocols: Vec<String>,
    pub binary_path: PathBuf,
    pub arguments: Vec<String>,
    pub run_on_startup: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TorBridgePtConfig {
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub bridge_lines: Vec<String>,
    pub transports: Vec<TorPluggableTransport>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TorStateDirectories {
    state_dir: PathBuf,
    cache_dir: PathBuf,
}

impl TorStateDirectories {
    pub fn state_dir(&self) -> &Path {
        &self.state_dir
    }

    pub fn cache_dir(&self) -> &Path {
        &self.cache_dir
    }
}

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
    #[error("Tor bridge+PT config requires at least one bridge line")]
    MissingBridgeLines,
    #[error("Tor bridge+PT config requires at least one PT binary")]
    MissingTransportBinaries,
    #[error("PT binary {binary_path} must declare at least one transport protocol")]
    MissingTransportProtocols { binary_path: PathBuf },
    #[error("invalid Tor bridge line {bridge_line}: {source}")]
    InvalidBridgeLine {
        bridge_line: String,
        #[source]
        source: arti_client::config::BridgeParseError,
    },
    #[error("direct Tor bridge line is forbidden in censored bridge+PT profile: {bridge_line}")]
    DirectBridgeLine { bridge_line: String },
    #[error("no PT binary configured for bridge transport {transport}")]
    MissingTransportBinary { transport: String },
    #[error("invalid PT transport protocol {protocol}: {message}")]
    InvalidTransportProtocol { protocol: String, message: String },
    #[error("failed to create Arti {kind} directory {path}: {source}")]
    CreateStateDirectory {
        kind: &'static str,
        path: PathBuf,
        #[source]
        source: io::Error,
    },
    #[error("Arti {kind} path is not a directory: {path}")]
    StatePathNotDirectory { kind: &'static str, path: PathBuf },
    #[error("failed to validate writable Arti {kind} directory {path}: {source}")]
    ValidateStateDirectory {
        kind: &'static str,
        path: PathBuf,
        #[source]
        source: io::Error,
    },
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
    #[error("failed to build Arti bridge+PT config: {source}")]
    BuildBridgePt {
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

pub fn build_bridge_pt_config(config: TorBridgePtConfig) -> Result<ArtiTorClientConfig, TorConfigError> {
    if config.bridge_lines.is_empty() {
        return Err(TorConfigError::MissingBridgeLines);
    }
    if config.transports.is_empty() {
        return Err(TorConfigError::MissingTransportBinaries);
    }

    let mut configured_transports = BTreeSet::new();
    for transport in &config.transports {
        if transport.protocols.is_empty() {
            return Err(TorConfigError::MissingTransportProtocols { binary_path: transport.binary_path.clone() });
        }
        configured_transports.extend(transport.protocols.iter().cloned());
    }

    let mut builder = TorClientConfigBuilder::from_directories(&config.state_dir, &config.cache_dir);
    builder.bridges().enabled(BoolOrAuto::Explicit(true));

    for bridge_line in config.bridge_lines {
        let bridge: BridgeConfigBuilder = bridge_line
            .parse()
            .map_err(|source| TorConfigError::InvalidBridgeLine { bridge_line: bridge_line.clone(), source })?;
        let transport = required_bridge_transport(&bridge_line, &bridge)?;
        if !configured_transports.contains(transport) {
            return Err(TorConfigError::MissingTransportBinary { transport: transport.to_owned() });
        }
        builder.bridges().bridges().push(bridge);
    }

    for transport in config.transports {
        builder.bridges().transports().push(build_transport_config(transport)?);
    }

    builder.build().map_err(|source| TorConfigError::BuildBridgePt { source })
}

pub fn prepare_arti_state_dirs(
    state_dir: impl AsRef<Path>,
    cache_dir: impl AsRef<Path>,
) -> Result<TorStateDirectories, TorConfigError> {
    let state_dir = prepare_arti_dir("state", state_dir.as_ref())?;
    let cache_dir = prepare_arti_dir("cache", cache_dir.as_ref())?;
    Ok(TorStateDirectories { state_dir, cache_dir })
}

fn prepare_arti_dir(kind: &'static str, path: &Path) -> Result<PathBuf, TorConfigError> {
    fs::create_dir_all(path).map_err(|source| TorConfigError::CreateStateDirectory {
        kind,
        path: path.to_path_buf(),
        source,
    })?;
    if !path.is_dir() {
        return Err(TorConfigError::StatePathNotDirectory { kind, path: path.to_path_buf() });
    }

    let probe = path.join(".ripdpi-write-smoke");
    fs::write(&probe, b"ok").map_err(|source| TorConfigError::ValidateStateDirectory {
        kind,
        path: path.to_path_buf(),
        source,
    })?;
    fs::remove_file(&probe).map_err(|source| TorConfigError::ValidateStateDirectory {
        kind,
        path: path.to_path_buf(),
        source,
    })?;
    Ok(path.to_path_buf())
}

fn required_bridge_transport<'a>(
    bridge_line: &str,
    bridge: &'a BridgeConfigBuilder,
) -> Result<&'a str, TorConfigError> {
    match bridge.get_transport().unwrap_or_default() {
        "" | "-" | "bridge" => Err(TorConfigError::DirectBridgeLine { bridge_line: bridge_line.to_owned() }),
        transport => Ok(transport),
    }
}

fn build_transport_config(transport: TorPluggableTransport) -> Result<TransportConfigBuilder, TorConfigError> {
    let protocols = transport
        .protocols
        .iter()
        .map(|protocol| {
            protocol.parse::<PtTransportName>().map_err(|source| TorConfigError::InvalidTransportProtocol {
                protocol: protocol.clone(),
                message: source.to_string(),
            })
        })
        .collect::<StdResult<Vec<_>, _>>()?;
    let mut builder = TransportConfigBuilder::default();
    builder
        .protocols(protocols)
        .path(CfgPath::new_literal(transport.binary_path))
        .arguments(transport.arguments)
        .run_on_startup(transport.run_on_startup);
    Ok(builder)
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

    pub async fn resolve_hostname(&self, hostname: &str) -> io::Result<Vec<IpAddr>> {
        self.inner
            .resolve(hostname)
            .await
            .map_err(|error| io::Error::other(format!("Tor DNS resolve for {hostname} failed: {error}")))
    }
}

fn ensure_rustls_crypto_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}
