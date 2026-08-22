#![forbid(unsafe_code)]
// AI-authorship >= 50% — opt into the full pedantic group on top of the curated
// workspace floor. This crate is the maintained per-crate adoption example.
#![warn(clippy::pedantic)]
#![allow(clippy::must_use_candidate)] // accessor-heavy client API; #[must_use] churn not worth it
#![allow(clippy::missing_errors_doc)] // TorError variants are self-describing at the call site

use std::fmt;
use std::fs;
use std::future::Future;
use std::io;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Once};
use std::time::Duration;
use std::{collections::BTreeSet, result::Result as StdResult};

use arti_client::{
    IntoTorAddr, TorClient as ArtiTorClient,
    config::{
        BoolOrAuto, BridgeConfigBuilder, CfgPath, PtTransportName, TorClientConfigBuilder, pt::TransportConfigBuilder,
    },
};
use tokio::io::{AsyncRead, AsyncWrite};
use tor_rtcompat::PreferredRuntime;

pub use arti_client::config::TorClientConfig as ArtiTorClientConfig;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

pub type BoxedIo = Box<dyn AsyncIo>;

static RUSTLS_PROVIDER: Once = Once::new();

/// Default upper bound for an Arti bootstrap attempt.
///
/// A censored or blackholed bridge can leave Arti's `OnDemand` bootstrap
/// pending indefinitely on the first connect; without a bound the relay would
/// look "connected" forever while never carrying traffic (audit P1-6/TOR-3).
/// 90 s matches the wrapper already used by `tests/chutney.rs` and gives a slow
/// but reachable bridge enough headroom to finish a real bootstrap.
pub const DEFAULT_BOOTSTRAP_TIMEOUT: Duration = Duration::from_secs(90);

#[derive(Debug, thiserror::Error)]
pub enum TorError {
    /// Arti bootstrap did not complete within the allotted window. Surfaced as a
    /// distinct variant (rather than a generic IO error) so the relay layer can
    /// fail fast with an actionable "bridge unreachable / blocked" signal.
    #[error("Tor bootstrap did not complete within {timeout:?}")]
    BootstrapTimeout { timeout: Duration },
    /// Arti reported a hard bootstrap failure before the timeout elapsed.
    #[error("Tor bootstrap failed: {source}")]
    BootstrapFailed {
        #[source]
        source: arti_client::Error,
    },
}

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
    inner: Arc<ArtiTorClient<PreferredRuntime>>,
}

impl TorRelayClient {
    pub fn create_unbootstrapped(config: ArtiTorClientConfig) -> arti_client::Result<Self> {
        ensure_rustls_crypto_provider();
        let inner = ArtiTorClient::builder().config(config).create_unbootstrapped()?;
        Ok(Self { inner })
    }

    pub async fn create_bootstrapped(config: ArtiTorClientConfig) -> arti_client::Result<Self> {
        ensure_rustls_crypto_provider();
        let inner = ArtiTorClient::create_bootstrapped(config).await?;
        Ok(Self { inner })
    }

    pub fn from_arti_client(inner: ArtiTorClient<PreferredRuntime>) -> Self {
        Self { inner: Arc::new(inner) }
    }

    /// Proactively drive (and await) Arti's bootstrap, bounded by `timeout`.
    ///
    /// `create_unbootstrapped` leaves Arti in `OnDemand` mode where bootstrap is
    /// triggered lazily by the first `connect_tcp`/`resolve_hostname` with no
    /// upper bound. A censored bridge therefore hangs that first request
    /// forever. Calling this first converts that open-ended hang into a bounded,
    /// typed [`TorError::BootstrapTimeout`]. Calling it after bootstrap already
    /// succeeded returns immediately (Arti's `bootstrap()` is idempotent).
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the only `.await` is `apply_bootstrap_timeout`, which wraps
    /// `ArtiTorClient::bootstrap()`. Dropping this future before it resolves
    /// cancels the in-flight bootstrap attempt without leaving partial state;
    /// Arti's `bootstrap()` is documented as safely retryable afterwards.
    pub async fn bootstrap_with_timeout(&self, timeout: Duration) -> Result<(), TorError> {
        apply_bootstrap_timeout(self.inner.bootstrap(), timeout).await
    }

    /// Convenience wrapper over [`Self::bootstrap_with_timeout`] using
    /// [`DEFAULT_BOOTSTRAP_TIMEOUT`].
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: delegates to [`Self::bootstrap_with_timeout`]; see its note.
    pub async fn bootstrap(&self) -> Result<(), TorError> {
        self.bootstrap_with_timeout(DEFAULT_BOOTSTRAP_TIMEOUT).await
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

/// Wrap a bootstrap future in `timeout`, mapping the elapsed case to a typed
/// [`TorError::BootstrapTimeout`] and a hard Arti failure to
/// [`TorError::BootstrapFailed`].
///
/// Factored out (and generic over the future) so the timeout/error mapping can
/// be unit-tested with a never-completing future, no live Tor network required.
///
/// # Cancel safety
///
/// cancel-safe: `tokio::time::timeout` is cancel-safe and simply drops the
/// inner future if this future is dropped; no state escapes.
async fn apply_bootstrap_timeout<F>(bootstrap: F, timeout: Duration) -> Result<(), TorError>
where
    F: Future<Output = arti_client::Result<()>>,
{
    match tokio::time::timeout(timeout, bootstrap).await {
        Ok(Ok(())) => Ok(()),
        Ok(Err(source)) => Err(TorError::BootstrapFailed { source }),
        Err(_elapsed) => Err(TorError::BootstrapTimeout { timeout }),
    }
}

fn ensure_rustls_crypto_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        // TUIC installs the ring provider as the process-global default; when
        // it won the install race, keep that default instead of panicking —
        // both providers provide the TLS 1.3 suites Tor's arti client needs.
        if rustls::crypto::CryptoProvider::get_default().is_none() {
            let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
        }
    });
}

#[cfg(test)]
mod tests {
    use super::{DEFAULT_BOOTSTRAP_TIMEOUT, TorError, apply_bootstrap_timeout};
    use std::future::{self, Future};
    use std::pin::Pin;
    use std::task::{Context, Poll};
    use std::time::{Duration, Instant};

    /// A future that never resolves, standing in for a censored/blackholed
    /// bridge whose bootstrap hangs indefinitely. No live Tor network is used.
    struct NeverBootstrap;

    impl Future for NeverBootstrap {
        type Output = arti_client::Result<()>;

        fn poll(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<Self::Output> {
            Poll::Pending
        }
    }

    #[tokio::test(flavor = "current_thread", start_paused = true)]
    async fn blocked_bridge_bootstrap_times_out_within_bound() {
        let timeout = Duration::from_secs(75);
        let started = Instant::now();

        let result = apply_bootstrap_timeout(NeverBootstrap, timeout).await;

        // With the tokio test clock paused, the timeout fires deterministically
        // by advancing virtual time — the test does not actually sleep 75 s.
        match result {
            Err(TorError::BootstrapTimeout { timeout: observed }) => assert_eq!(observed, timeout),
            other => panic!("expected BootstrapTimeout, got {other:?}"),
        }
        assert!(started.elapsed() < Duration::from_secs(5), "must not wall-clock sleep for the full timeout");
    }

    #[tokio::test(flavor = "current_thread", start_paused = true)]
    async fn already_bootstrapped_resolves_immediately() {
        let result = apply_bootstrap_timeout(future::ready(Ok(())), DEFAULT_BOOTSTRAP_TIMEOUT).await;
        assert!(result.is_ok(), "a ready bootstrap must succeed: {result:?}");
    }
}
