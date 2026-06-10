use std::net::IpAddr;

use ripdpi_tls_profiles::OutboundEchConfig;
use ripdpi_vless::addons::{FlowParseError, VlessFlow};
use ripdpi_vless::config::VlessRealityConfig;
use tokio::io::{AsyncRead, AsyncWrite};

const DEFAULT_XMUX_MAX_CONNECTIONS: usize = 8;
const DEFAULT_XMUX_MAX_CONCURRENT_STREAMS: usize = 32;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// xHTTP transport protocol mode (xray-core `Config.mode`).
///
/// Upstream supports four modes: `stream-up`, `packet-up`, `stream-one`,
/// and `stream-down` (split-endpoint variant of `stream-up`). This crate
/// currently implements `stream-up` (the historical default) and
/// `stream-one`; `packet-up` and split-endpoint remain unsupported.
///
/// `Default` resolves to [`Self::StreamUp`] so existing callers — and
/// any Kotlin/JNI binding that has not yet been taught about the mode
/// selector — keep their historical wire shape.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum XhttpProtocolMode {
    /// Two requests per session: GET (download) + POST (upload). xray's
    /// historical default and the only mode this crate shipped with.
    #[default]
    StreamUp,
    /// Single bidirectional HTTP/2 request — request body carries the
    /// upload stream, response body carries the download stream, no
    /// session id is embedded in the URL. Matches xray-core's REALITY
    /// default in `transport/internet/splithttp/dialer.go`.
    StreamOne,
}

impl XhttpProtocolMode {
    /// Parse the xray `mode` string. Accepts `""` and `"auto"` as the
    /// back-compat default ([`Self::StreamUp`]); rejects modes that are
    /// not yet implemented (`packet-up`, `stream-down`) with a named
    /// error so a misconfigured profile does not silently downgrade.
    pub fn parse(value: &str) -> Result<Self, ProtocolModeParseError> {
        match value.trim() {
            "" | "auto" | "stream-up" => Ok(Self::StreamUp),
            "stream-one" => Ok(Self::StreamOne),
            other => Err(ProtocolModeParseError(other.to_owned())),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("unsupported xHTTP mode {0:?}; expected 'stream-up', 'stream-one', '', or 'auto'")]
pub struct ProtocolModeParseError(pub String);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FinalmaskConfig {
    pub r#type: String,
    pub header_hex: String,
    pub trailer_hex: String,
    pub rand_range: String,
    pub sudoku_seed: String,
    pub fragment_packets: i32,
    pub fragment_min_bytes: i32,
    pub fragment_max_bytes: i32,
}

impl Default for FinalmaskConfig {
    fn default() -> Self {
        Self {
            r#type: "off".to_string(),
            header_hex: String::new(),
            trailer_hex: String::new(),
            rand_range: String::new(),
            sudoku_seed: String::new(),
            fragment_packets: 0,
            fragment_min_bytes: 0,
            fragment_max_bytes: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct XmuxConfig {
    pub max_connections: usize,
    pub max_concurrent_streams: usize,
}

impl Default for XmuxConfig {
    fn default() -> Self {
        Self {
            max_connections: DEFAULT_XMUX_MAX_CONNECTIONS,
            max_concurrent_streams: DEFAULT_XMUX_MAX_CONCURRENT_STREAMS,
        }
    }
}

#[derive(Debug, Clone)]
pub struct XhttpRealityConfig {
    pub vless: VlessRealityConfig,
    pub path: String,
    pub host: Option<String>,
    pub bind_ip: Option<IpAddr>,
    pub xmux: XmuxConfig,
    pub finalmask: FinalmaskConfig,
    /// xHTTP protocol mode. Defaults to [`XhttpProtocolMode::StreamUp`].
    pub protocol_mode: XhttpProtocolMode,
}

#[derive(Debug, Clone)]
pub struct XhttpTlsConfig {
    pub server: String,
    pub port: u16,
    pub server_name: String,
    pub uuid: [u8; 16],
    pub path: String,
    pub host: Option<String>,
    pub bind_ip: Option<IpAddr>,
    pub tls_fingerprint_profile: String,
    pub xmux: XmuxConfig,
    pub finalmask: FinalmaskConfig,
    /// VLESS flow negotiated inside the xHTTP tunnel. Defaults to
    /// [`VlessFlow::Vision`] for back-compatibility with profiles that
    /// have not opted in to per-flow selection.
    pub flow: VlessFlow,
    /// xHTTP protocol mode. Defaults to [`XhttpProtocolMode::StreamUp`].
    pub protocol_mode: XhttpProtocolMode,
    /// Optional ECH config for owned xHTTP TLS outbounds.
    pub ech_config: Option<OutboundEchConfig>,
    /// Optional ordered post-quantum KEM group override (e.g.
    /// `["X25519MLKEM768", "X25519", "P256"]`). When `Some`, it replaces the
    /// profile's static curve list before the connector is built. `None`
    /// (default) keeps the profile's curve template.
    pub kem_groups: Option<Vec<String>>,
}

impl XhttpTlsConfig {
    pub fn from_strings(
        server: &str,
        port: i32,
        server_name: &str,
        uuid: &str,
        path: &str,
        host: &str,
        tls_fingerprint_profile: &str,
    ) -> Result<Self, ConfigError> {
        let port = u16::try_from(port).map_err(|_| ConfigError::InvalidPort(port))?;
        Ok(Self {
            server: server.to_owned(),
            port,
            server_name: server_name.to_owned(),
            uuid: parse_uuid(uuid).map_err(|_| ConfigError::InvalidUuid(uuid.to_owned()))?,
            path: normalize_path(path),
            host: if host.trim().is_empty() { None } else { Some(host.trim().to_owned()) },
            bind_ip: None,
            tls_fingerprint_profile: tls_fingerprint_profile.to_owned(),
            xmux: XmuxConfig::default(),
            finalmask: FinalmaskConfig::default(),
            flow: VlessFlow::default(),
            protocol_mode: XhttpProtocolMode::default(),
            ech_config: None,
            kem_groups: None,
        })
    }

    /// Override the post-quantum KEM group order for this connection (builder
    /// style). When set, the ordered list (e.g.
    /// `["X25519MLKEM768", "X25519", "P256"]`) replaces the profile's curve
    /// template before the connector is built.
    #[must_use]
    pub fn with_kem_groups(mut self, kem_groups: Vec<String>) -> Self {
        self.kem_groups = Some(kem_groups);
        self
    }

    /// Replace the flow selection (builder style). The default is
    /// [`VlessFlow::Vision`] to preserve historical client behavior.
    pub fn with_flow(mut self, flow: VlessFlow) -> Self {
        self.flow = flow;
        self
    }

    /// Parse a flow identifier (`xtls-rprx-vision`,
    /// `xtls-rprx-vision-udp443`, or `""` / `"none"` / `"off"` for no
    /// flow) and attach it.
    pub fn with_flow_str(self, flow: &str) -> Result<Self, ConfigError> {
        let parsed = VlessFlow::parse(flow)?;
        Ok(self.with_flow(parsed))
    }

    /// Replace the xHTTP protocol mode (builder style). The default is
    /// [`XhttpProtocolMode::StreamUp`] for historical back-compatibility.
    pub fn with_protocol_mode(mut self, mode: XhttpProtocolMode) -> Self {
        self.protocol_mode = mode;
        self
    }

    /// Parse the xray `mode` identifier (`stream-up`, `stream-one`,
    /// `""`/`"auto"`) and attach it.
    pub fn with_protocol_mode_str(self, mode: &str) -> Result<Self, ConfigError> {
        let parsed = XhttpProtocolMode::parse(mode)?;
        Ok(self.with_protocol_mode(parsed))
    }

    pub fn with_ech_config(mut self, ech_config: OutboundEchConfig) -> Self {
        self.ech_config = Some(ech_config);
        self
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),
    #[error("invalid port: {0}")]
    InvalidPort(i32),
    #[error("invalid flow: {0}")]
    InvalidFlow(#[from] FlowParseError),
    #[error("invalid xHTTP mode: {0}")]
    InvalidProtocolMode(#[from] ProtocolModeParseError),
}

#[derive(Debug, Clone)]
pub(crate) enum XhttpMode {
    Reality(XhttpRealityConfig),
    Tls(XhttpTlsConfig),
}

pub(crate) fn normalize_path(path: &str) -> String {
    let trimmed = path.trim().trim_matches('/');
    if trimmed.is_empty() { "/".to_owned() } else { format!("/{trimmed}") }
}

fn parse_uuid(value: &str) -> Result<[u8; 16], ()> {
    let hex_only: String = value.chars().filter(|character| *character != '-').collect();
    if hex_only.len() != 32 {
        return Err(());
    }
    let bytes = hex::decode(&hex_only).map_err(|_| ())?;
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}
