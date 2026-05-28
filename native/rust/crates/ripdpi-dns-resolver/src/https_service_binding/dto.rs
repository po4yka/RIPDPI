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
