use std::fmt;
use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use thiserror::Error;

pub const DOT_DEFAULT_PORT: u16 = 853;
pub const DOQ_DEFAULT_PORT: u16 = 853;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncryptedDnsProtocol {
    Doh,
    Dot,
    DnsCrypt,
    Doq,
}

impl EncryptedDnsProtocol {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Doh => "doh",
            Self::Dot => "dot",
            Self::DnsCrypt => "dnscrypt",
            Self::Doq => "doq",
        }
    }

    pub(crate) fn default_port(self) -> u16 {
        match self {
            Self::Doh => 443,
            Self::Dot => DOT_DEFAULT_PORT,
            Self::DnsCrypt => 443,
            Self::Doq => DOQ_DEFAULT_PORT,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedDnsEndpoint {
    pub protocol: EncryptedDnsProtocol,
    pub resolver_id: Option<String>,
    pub host: String,
    pub port: u16,
    pub tls_server_name: Option<String>,
    pub bootstrap_ips: Vec<IpAddr>,
    pub doh_url: Option<String>,
    pub dnscrypt_provider_name: Option<String>,
    pub dnscrypt_public_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EncryptedDnsTransport {
    Direct,
    Socks5 { host: String, port: u16 },
}

pub type DirectTcpConnector = dyn Fn(SocketAddr, Duration) -> io::Result<TcpStream> + Send + Sync;
pub type DirectUdpBinder = dyn Fn(SocketAddr) -> io::Result<UdpSocket> + Send + Sync;

#[derive(Clone, Default)]
pub struct EncryptedDnsConnectHooks {
    pub direct_tcp_connector: Option<Arc<DirectTcpConnector>>,
    pub direct_udp_binder: Option<Arc<DirectUdpBinder>>,
}

impl EncryptedDnsConnectHooks {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_direct_tcp_connector<F>(mut self, connector: F) -> Self
    where
        F: Fn(SocketAddr, Duration) -> io::Result<TcpStream> + Send + Sync + 'static,
    {
        self.direct_tcp_connector = Some(Arc::new(connector));
        self
    }

    pub fn with_direct_udp_binder<F>(mut self, binder: F) -> Self
    where
        F: Fn(SocketAddr) -> io::Result<UdpSocket> + Send + Sync + 'static,
    {
        self.direct_udp_binder = Some(Arc::new(binder));
        self
    }

    pub(crate) fn has_direct_tcp_connector(&self) -> bool {
        self.direct_tcp_connector.is_some()
    }
}

impl fmt::Debug for EncryptedDnsConnectHooks {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EncryptedDnsConnectHooks")
            .field("direct_tcp_connector", &self.direct_tcp_connector.is_some())
            .field("direct_udp_binder", &self.direct_udp_binder.is_some())
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedDnsExchangeSuccess {
    pub response_bytes: Vec<u8>,
    pub endpoint_label: String,
    pub latency_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncryptedDnsErrorKind {
    Bootstrap,
    Connect,
    SniBlocked,
    Timeout,
    Tls,
    Http,
    DnsCrypt,
    Decode,
    NoAnswer,
}

#[derive(Debug, Clone)]
pub(crate) struct DnsCryptCachedCertificate {
    pub(crate) resolver_public_key: [u8; 32],
    pub(crate) client_magic: [u8; 8],
    pub(crate) valid_from: u32,
    pub(crate) valid_until: u32,
}

#[derive(Debug, Error)]
pub enum EncryptedDnsError {
    #[error("invalid encrypted DNS endpoint: {0}")]
    InvalidEndpoint(String),
    #[error("encrypted DNS endpoint must include a host")]
    MissingHost,
    #[error("bootstrap IPs are required for direct transport")]
    MissingBootstrapIps,
    #[error("DoH URL is required")]
    MissingDohUrl,
    #[error("invalid DoH URL: {0}")]
    InvalidUrl(String),
    #[error("invalid DNSCrypt public key: {0}")]
    InvalidDnsCryptPublicKey(String),
    #[error("invalid DNSCrypt provider name")]
    MissingDnsCryptProviderName,
    #[error("request build failed: {0}")]
    ClientBuild(String),
    #[error("request failed: {0}")]
    Request(String),
    #[error("DoH server returned HTTP {0}")]
    HttpStatus(reqwest::StatusCode),
    #[error("DNS response parse failed: {0}")]
    DnsParse(String),
    #[error("TLS handshake failed: {0}")]
    Tls(String),
    #[error("SOCKS5 negotiation failed: {0}")]
    Socks5(String),
    #[error("DNSCrypt certificate fetch failed: {0}")]
    DnsCryptCertificate(String),
    #[error("DNSCrypt certificate verification failed: {0}")]
    DnsCryptVerification(String),
    #[error("DNSCrypt response decryption failed: {0}")]
    DnsCryptDecrypt(String),
    #[error("task join failed: {0}")]
    TaskJoin(String),
}

impl EncryptedDnsError {
    pub fn kind(&self) -> EncryptedDnsErrorKind {
        match self {
            EncryptedDnsError::MissingBootstrapIps => EncryptedDnsErrorKind::Bootstrap,
            EncryptedDnsError::InvalidEndpoint(_)
            | EncryptedDnsError::MissingHost
            | EncryptedDnsError::MissingDohUrl
            | EncryptedDnsError::InvalidUrl(_)
            | EncryptedDnsError::InvalidDnsCryptPublicKey(_)
            | EncryptedDnsError::MissingDnsCryptProviderName
            | EncryptedDnsError::DnsParse(_) => EncryptedDnsErrorKind::Decode,
            EncryptedDnsError::ClientBuild(_) | EncryptedDnsError::Socks5(_) | EncryptedDnsError::TaskJoin(_) => {
                EncryptedDnsErrorKind::Connect
            }
            EncryptedDnsError::Request(msg) => {
                if is_connection_reset_pattern(msg) {
                    EncryptedDnsErrorKind::SniBlocked
                } else {
                    EncryptedDnsErrorKind::Connect
                }
            }
            EncryptedDnsError::HttpStatus(_) => EncryptedDnsErrorKind::Http,
            EncryptedDnsError::Tls(msg) => {
                if is_connection_reset_pattern(msg) {
                    EncryptedDnsErrorKind::SniBlocked
                } else {
                    EncryptedDnsErrorKind::Tls
                }
            }
            EncryptedDnsError::DnsCryptCertificate(_)
            | EncryptedDnsError::DnsCryptVerification(_)
            | EncryptedDnsError::DnsCryptDecrypt(_) => EncryptedDnsErrorKind::DnsCrypt,
        }
    }
}

/// Detects TCP RST injection patterns from TSPU DPI equipment.
/// TSPU sends a TCP RST after observing the SNI in the TLS ClientHello,
/// which manifests as "connection reset" or "broken pipe" errors during
/// the TLS handshake or immediately after.
fn is_connection_reset_pattern(message: &str) -> bool {
    let lower = message.to_lowercase();
    lower.contains("connection reset")
        || lower.contains("connection was reset")
        || lower.contains("broken pipe")
        || lower.contains("connection abort")
}
