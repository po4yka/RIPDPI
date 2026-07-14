use std::fmt;
use std::net::IpAddr;

use crate::odoh::OdohConfigSource;

pub const DOT_DEFAULT_PORT: u16 = 853;
pub const DOQ_DEFAULT_PORT: u16 = 853;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncryptedDnsProtocol {
    Doh,
    Dot,
    DnsCrypt,
    Doq,
    Odoh,
}

impl EncryptedDnsProtocol {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Doh => "doh",
            Self::Dot => "dot",
            Self::DnsCrypt => "dnscrypt",
            Self::Doq => "doq",
            Self::Odoh => "odoh",
        }
    }

    pub(crate) fn default_port(self) -> u16 {
        match self {
            Self::Doh => 443,
            Self::Dot => DOT_DEFAULT_PORT,
            Self::DnsCrypt => 443,
            Self::Doq => DOQ_DEFAULT_PORT,
            Self::Odoh => 443,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OdohEndpointConfig {
    pub proxy_url: String,
    pub proxy_operator_id: String,
    pub target_host: String,
    pub target_path: String,
    pub target_operator_id: String,
    pub config_source: OdohConfigSource,
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
    pub odoh: Option<OdohEndpointConfig>,
}

#[derive(Clone, PartialEq, Eq)]
pub struct EncryptedDnsSocks5Credentials {
    pub username: String,
    pub password: String,
}

impl fmt::Debug for EncryptedDnsSocks5Credentials {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EncryptedDnsSocks5Credentials")
            .field("username", &"<redacted>")
            .field("password", &"<redacted>")
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[non_exhaustive]
pub enum EncryptedDnsTransport {
    Direct,
    Socks5 { host: String, port: u16, credentials: Option<EncryptedDnsSocks5Credentials> },
}

#[cfg(test)]
mod tests {
    use super::EncryptedDnsSocks5Credentials;

    #[test]
    fn socks5_credentials_debug_output_is_redacted() {
        let credentials = EncryptedDnsSocks5Credentials {
            username: "fixture-user".to_string(),
            password: "fixture-password".to_string(),
        };

        let debug = format!("{credentials:?}");

        assert!(!debug.contains("fixture-user"));
        assert!(!debug.contains("fixture-password"));
        assert!(debug.contains("<redacted>"));
    }
}
