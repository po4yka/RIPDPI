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
