use thiserror::Error;

use std::net::IpAddr;

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
            EncryptedDnsError::ClientBuild(_)
            | EncryptedDnsError::Request(_)
            | EncryptedDnsError::Socks5(_)
            | EncryptedDnsError::TaskJoin(_) => EncryptedDnsErrorKind::Connect,
            EncryptedDnsError::HttpStatus(_) => EncryptedDnsErrorKind::Http,
            EncryptedDnsError::Tls(_) => EncryptedDnsErrorKind::Tls,
            EncryptedDnsError::DnsCryptCertificate(_)
            | EncryptedDnsError::DnsCryptVerification(_)
            | EncryptedDnsError::DnsCryptDecrypt(_) => EncryptedDnsErrorKind::DnsCrypt,
        }
    }
}
