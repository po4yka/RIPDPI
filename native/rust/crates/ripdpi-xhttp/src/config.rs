use std::net::IpAddr;

use ripdpi_vless::addons::{FlowParseError, VlessFlow};
use ripdpi_vless::config::VlessRealityConfig;
use tokio::io::{AsyncRead, AsyncWrite};

const DEFAULT_XMUX_MAX_CONNECTIONS: usize = 8;
const DEFAULT_XMUX_MAX_CONCURRENT_STREAMS: usize = 32;

pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

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
        })
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
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("invalid UUID: {0}")]
    InvalidUuid(String),
    #[error("invalid port: {0}")]
    InvalidPort(i32),
    #[error("invalid flow: {0}")]
    InvalidFlow(#[from] FlowParseError),
}

#[derive(Debug, Clone)]
pub(crate) enum XhttpMode {
    Reality(XhttpRealityConfig),
    Tls(XhttpTlsConfig),
}

pub(crate) fn normalize_path(path: &str) -> String {
    let trimmed = path.trim().trim_matches('/');
    if trimmed.is_empty() {
        "/".to_owned()
    } else {
        format!("/{trimmed}")
    }
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
