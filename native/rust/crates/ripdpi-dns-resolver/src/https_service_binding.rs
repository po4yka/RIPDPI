use std::net::{Ipv4Addr, Ipv6Addr};

use hickory_proto::op::Message;
use hickory_proto::rr::rdata::svcb::SvcParamValue;
use hickory_proto::rr::{RData, Record};
use rustls::pki_types::DnsName;
use thiserror::Error;

const ECH_CONFIG_VERSION_V18: u16 = 0xfe0d;

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

pub fn parse_https_service_bindings(packet: &[u8]) -> Result<Vec<HttpsRr>, HttpsSvcbParseError> {
    let message = Message::from_vec(packet).map_err(|error| HttpsSvcbParseError::Response(error.to_string()))?;
    let mut bindings = Vec::new();

    for record in message.answers.iter().chain(message.authorities.iter()).chain(message.additionals.iter()) {
        match &record.data {
            RData::HTTPS(https) => bindings.push(parse_service_binding_record(
                record,
                HttpsRrRecordType::Https,
                https.svc_priority,
                https.target_name.to_ascii(),
                &https.svc_params,
            )?),
            RData::SVCB(svcb) => bindings.push(parse_service_binding_record(
                record,
                HttpsRrRecordType::Svcb,
                svcb.svc_priority,
                svcb.target_name.to_ascii(),
                &svcb.svc_params,
            )?),
            _ => {}
        }
    }

    Ok(bindings)
}

fn parse_service_binding_record(
    record: &Record,
    record_type: HttpsRrRecordType,
    service_priority: u16,
    target_name: String,
    svc_params: &[(hickory_proto::rr::rdata::svcb::SvcParamKey, SvcParamValue)],
) -> Result<HttpsRr, HttpsSvcbParseError> {
    let mut alpn = Vec::new();
    let mut no_default_alpn = false;
    let mut port = None;
    let mut ipv4_hints = Vec::new();
    let mut ipv6_hints = Vec::new();
    let mut ech_config = None;

    for (_, param) in svc_params {
        match param {
            SvcParamValue::Alpn(value) => alpn = value.0.clone(),
            SvcParamValue::NoDefaultAlpn => no_default_alpn = true,
            SvcParamValue::Port(value) => port = Some(*value),
            SvcParamValue::Ipv4Hint(value) => ipv4_hints.extend(value.0.iter().map(|addr| addr.0)),
            SvcParamValue::Ipv6Hint(value) => ipv6_hints.extend(value.0.iter().map(|addr| addr.0)),
            SvcParamValue::EchConfigList(value) => ech_config = Some(parse_ech_config_list(&value.0)?),
            _ => {}
        }
    }

    Ok(HttpsRr {
        owner_name: record.name.to_ascii(),
        record_type,
        service_priority,
        target_name,
        ttl_secs: record.ttl,
        alpn,
        no_default_alpn,
        port,
        ipv4_hints,
        ipv6_hints,
        ech_capable: ech_config.is_some(),
        ech_config,
    })
}

fn parse_ech_config_list(bytes: &[u8]) -> Result<EchConfig, HttpsSvcbParseError> {
    let mut cursor = ByteCursor::new(bytes);
    let total_len = usize::from(cursor.read_u16("ECHConfigList length")?);
    if total_len == 0 {
        return Err(HttpsSvcbParseError::EmptyEchConfigList);
    }
    let config_bytes = cursor.read_bytes(total_len, "ECHConfigList payload")?;
    cursor.expect_empty("ECHConfigList trailing bytes")?;

    let mut payload = ByteCursor::new(config_bytes);
    let mut configs = Vec::new();
    while !payload.is_empty() {
        let version = payload.read_u16("ECHConfig version")?;
        let contents_len = usize::from(payload.read_u16("ECHConfig contents length")?);
        let contents = payload.read_bytes(contents_len, "ECHConfig contents")?;
        configs.push(parse_ech_config_entry(version, contents)?);
    }

    if configs.is_empty() {
        return Err(HttpsSvcbParseError::EmptyEchConfigList);
    }

    Ok(EchConfig { raw_list_bytes: bytes.to_vec(), configs })
}

fn parse_ech_config_entry(version: u16, contents: &[u8]) -> Result<EchConfigEntry, HttpsSvcbParseError> {
    if version != ECH_CONFIG_VERSION_V18 {
        return Ok(EchConfigEntry {
            version,
            config_id: None,
            kem_id: None,
            public_key_len: None,
            maximum_name_length: None,
            public_name: None,
            cipher_suites: Vec::new(),
            extensions: Vec::new(),
            has_unknown_mandatory_extension: false,
            raw_contents: contents.to_vec(),
        });
    }

    let mut cursor = ByteCursor::new(contents);
    let config_id = cursor.read_u8("ECH config_id")?;
    let kem_id = cursor.read_u16("ECH kem_id")?;
    let public_key = cursor.read_vec_u16("ECH public key")?;
    if public_key.is_empty() {
        return Err(HttpsSvcbParseError::MalformedEchConfigList("ECH public key must not be empty".to_string()));
    }

    let cipher_suite_bytes = cursor.read_vec_u16("ECH cipher suites")?;
    if cipher_suite_bytes.len() < 4 || cipher_suite_bytes.len() % 4 != 0 {
        return Err(HttpsSvcbParseError::MalformedEchConfigList(
            "ECH cipher suites must contain whole 4-byte entries".to_string(),
        ));
    }
    let cipher_suites = parse_cipher_suites(cipher_suite_bytes)?;

    let maximum_name_length = cursor.read_u8("ECH maximum_name_length")?;
    let public_name_bytes = cursor.read_vec_u8("ECH public_name")?;
    let public_name = DnsName::try_from(public_name_bytes)
        .map_err(|error| HttpsSvcbParseError::MalformedEchConfigList(format!("invalid ECH public_name: {error}")))?
        .as_ref()
        .to_string();

    let extensions_bytes = cursor.read_vec_u16("ECH extensions")?;
    let extensions = parse_extensions(extensions_bytes)?;
    cursor.expect_empty("ECHConfig contents trailing bytes")?;

    Ok(EchConfigEntry {
        version,
        config_id: Some(config_id),
        kem_id: Some(kem_id),
        public_key_len: Some(public_key.len()),
        maximum_name_length: Some(maximum_name_length),
        public_name: Some(public_name),
        cipher_suites,
        has_unknown_mandatory_extension: extensions.iter().any(|extension| extension.extension_type & 0x8000 != 0),
        extensions,
        raw_contents: contents.to_vec(),
    })
}

fn parse_cipher_suites(bytes: &[u8]) -> Result<Vec<EchCipherSuite>, HttpsSvcbParseError> {
    let mut cursor = ByteCursor::new(bytes);
    let mut suites = Vec::new();
    while !cursor.is_empty() {
        suites.push(EchCipherSuite {
            kdf_id: cursor.read_u16("ECH cipher suite KDF")?,
            aead_id: cursor.read_u16("ECH cipher suite AEAD")?,
        });
    }
    Ok(suites)
}

fn parse_extensions(bytes: &[u8]) -> Result<Vec<EchExtension>, HttpsSvcbParseError> {
    let mut cursor = ByteCursor::new(bytes);
    let mut extensions = Vec::new();
    while !cursor.is_empty() {
        let extension_type = cursor.read_u16("ECH extension type")?;
        let data_len = usize::from(cursor.read_u16("ECH extension data length")?);
        let _ = cursor.read_bytes(data_len, "ECH extension data")?;
        extensions.push(EchExtension { extension_type, data_len });
    }
    Ok(extensions)
}

struct ByteCursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> ByteCursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn is_empty(&self) -> bool {
        self.offset == self.bytes.len()
    }

    fn read_u8(&mut self, label: &str) -> Result<u8, HttpsSvcbParseError> {
        let bytes = self.read_bytes(1, label)?;
        Ok(bytes[0])
    }

    fn read_u16(&mut self, label: &str) -> Result<u16, HttpsSvcbParseError> {
        let bytes = self.read_bytes(2, label)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    fn read_vec_u8(&mut self, label: &str) -> Result<&'a [u8], HttpsSvcbParseError> {
        let len = usize::from(self.read_u8(label)?);
        self.read_bytes(len, label)
    }

    fn read_vec_u16(&mut self, label: &str) -> Result<&'a [u8], HttpsSvcbParseError> {
        let len = usize::from(self.read_u16(label)?);
        self.read_bytes(len, label)
    }

    fn read_bytes(&mut self, len: usize, label: &str) -> Result<&'a [u8], HttpsSvcbParseError> {
        let end = self.offset.saturating_add(len);
        if end > self.bytes.len() {
            return Err(HttpsSvcbParseError::MalformedEchConfigList(format!(
                "{label} truncated at byte {}",
                self.offset
            )));
        }
        let slice = &self.bytes[self.offset..end];
        self.offset = end;
        Ok(slice)
    }

    fn expect_empty(&self, label: &str) -> Result<(), HttpsSvcbParseError> {
        if self.is_empty() {
            return Ok(());
        }
        Err(HttpsSvcbParseError::MalformedEchConfigList(format!(
            "{label}: {} trailing bytes",
            self.bytes.len() - self.offset
        )))
    }
}
