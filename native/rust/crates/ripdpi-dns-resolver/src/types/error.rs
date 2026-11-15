use thiserror::Error;

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

/// Classified DoQ failures. Connect failures can affect resolver reachability.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DoqErrorKind {
    Setup,
    Connect,
    Exchange,
    Tls,
    Alpn,
    ConnectTimeout,
    QueryTimeout,
}

impl DoqErrorKind {
    pub(crate) fn error(self, message: impl Into<String>) -> EncryptedDnsError {
        EncryptedDnsError::Doq { kind: self, message: message.into() }
    }
}

#[derive(Debug, Error)]
pub enum EncryptedDnsError {
    #[error("{message}")]
    Doq { kind: DoqErrorKind, message: String },

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
    #[error("encrypted DNS returned no usable IP addresses")]
    NoAnswer,
    #[error("DNS message is too large for {transport} framing: {size} bytes exceeds {max}")]
    DnsMessageTooLarge { transport: &'static str, size: usize, max: usize },
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
            EncryptedDnsError::Doq { kind, .. } => match kind {
                DoqErrorKind::Setup | DoqErrorKind::Connect | DoqErrorKind::Exchange => EncryptedDnsErrorKind::Connect,
                DoqErrorKind::Tls | DoqErrorKind::Alpn => EncryptedDnsErrorKind::Tls,
                DoqErrorKind::ConnectTimeout | DoqErrorKind::QueryTimeout => EncryptedDnsErrorKind::Timeout,
            },
            EncryptedDnsError::MissingBootstrapIps => EncryptedDnsErrorKind::Bootstrap,
            EncryptedDnsError::InvalidEndpoint(_)
            | EncryptedDnsError::MissingHost
            | EncryptedDnsError::MissingDohUrl
            | EncryptedDnsError::InvalidUrl(_)
            | EncryptedDnsError::InvalidDnsCryptPublicKey(_)
            | EncryptedDnsError::MissingDnsCryptProviderName
            | EncryptedDnsError::DnsParse(_)
            | EncryptedDnsError::DnsMessageTooLarge { .. } => EncryptedDnsErrorKind::Decode,
            EncryptedDnsError::NoAnswer => EncryptedDnsErrorKind::NoAnswer,
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

/// Detects TCP RST injection patterns from middlebox DPI equipment.
/// A middlebox sends TCP RST after observing SNI in the TLS ClientHello, which
/// manifests as reset or broken-pipe errors during or soon after TLS handshake.
fn is_connection_reset_pattern(message: &str) -> bool {
    let lower = message.to_lowercase();
    lower.contains("connection reset")
        || lower.contains("connection was reset")
        || lower.contains("broken pipe")
        || lower.contains("connection abort")
}
