use std::net::{Ipv4Addr, Ipv6Addr};

use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpsRrRecordType {
    Https,
    Svcb,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpsRr {
    pub owner_name: String,
    pub record_type: HttpsRrRecordType,
    pub service_priority: u16,
    pub target_name: String,
    pub ttl_secs: u32,
    pub alpn: Vec<String>,
    pub no_default_alpn: bool,
    pub port: Option<u16>,
    pub ipv4_hints: Vec<Ipv4Addr>,
    pub ipv6_hints: Vec<Ipv6Addr>,
    pub ech_config: Option<EchConfig>,
    pub ech_capable: bool,
    pub odoh_config: Option<Vec<u8>>,
    pub odoh_capable: bool,
}

/// What an HTTPS or SVCB answer offers for its query name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HttpsServiceSelection {
    /// ServiceMode records, in ascending SvcPriority order (possibly none).
    Service(Vec<HttpsRr>),
    /// AliasMode: the query name is served under this TargetName.
    Alias(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EchConfig {
    pub raw_list_bytes: Vec<u8>,
    pub configs: Vec<EchConfigEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EchConfigEntry {
    pub version: u16,
    pub config_id: Option<u8>,
    pub kem_id: Option<u16>,
    pub public_key_len: Option<usize>,
    pub maximum_name_length: Option<u8>,
    pub public_name: Option<String>,
    pub cipher_suites: Vec<EchCipherSuite>,
    pub extensions: Vec<EchExtension>,
    pub has_unknown_mandatory_extension: bool,
    pub raw_contents: Vec<u8>,
}

impl EchConfig {
    /// The ECHConfigList of the supported entries only, or `None` when no entry is supported.
    pub fn supported_config_list(&self) -> Option<Vec<u8>> {
        let supported: Vec<&EchConfigEntry> = self.configs.iter().filter(|entry| entry.is_supported()).collect();
        if supported.is_empty() {
            return None;
        }
        let mut payload = Vec::new();
        for entry in supported {
            payload.extend(entry.version.to_be_bytes());
            payload.extend(u16::try_from(entry.raw_contents.len()).ok()?.to_be_bytes());
            payload.extend(&entry.raw_contents);
        }
        let mut list = u16::try_from(payload.len()).ok()?.to_be_bytes().to_vec();
        list.extend(payload);
        Some(list)
    }
}

impl EchConfigEntry {
    /// A v18 entry with a valid public_name, no unknown mandatory extension and
    /// no repeated extension type (RFC 9849 4.2).
    pub fn is_supported(&self) -> bool {
        let mut seen = std::collections::HashSet::new();
        self.version == 0xfe0d
            && self.public_name.as_ref().is_some_and(|name| name.len() <= 253)
            && !self.has_unknown_mandatory_extension
            && self.extensions.iter().all(|extension| seen.insert(extension.extension_type))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EchCipherSuite {
    pub kdf_id: u16,
    pub aead_id: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EchExtension {
    pub extension_type: u16,
    pub data_len: usize,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum HttpsSvcbParseError {
    #[error("DNS response parse failed: {0}")]
    Response(String),
    #[error("ECHConfigList is empty")]
    EmptyEchConfigList,
    #[error("ECHConfigList is malformed: {0}")]
    MalformedEchConfigList(String),
}
