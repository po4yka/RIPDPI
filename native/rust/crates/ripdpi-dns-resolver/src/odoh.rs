use bytes::Bytes;
use odoh_rs::{
    ODOH_VERSION, ObliviousDoHConfigContents, ObliviousDoHMessage, ObliviousDoHMessagePlaintext, OdohSecret, compose,
    decrypt_response, encrypt_query, parse,
};
use thiserror::Error;

pub const ODOH_MESSAGE_MEDIA_TYPE: &str = "application/oblivious-dns-message";
pub(crate) const ODOHCONFIG_SVCB_KEY: u16 = 32769;

#[derive(Debug, Error)]
pub enum OdohError {
    #[error("ODoH configs do not contain a supported RFC 9230 target config")]
    NoSupportedConfig,
    #[error("ODoH config source did not contain target config material")]
    MissingConfig,
    #[error("ODoH config material is stale: now={now_secs}, expires_at={expires_at_secs}")]
    StaleConfig { now_secs: u64, expires_at_secs: u64 },
    #[error("ODoH config lookup must use encrypted DNS; plaintext fallback is forbidden")]
    PlaintextConfigLookup,
    #[error("ODoH HTTPS/SVCB config parse failed: {0}")]
    HttpsSvcb(#[from] crate::HttpsSvcbParseError),
    #[error("ODoH protocol failed: {0}")]
    Protocol(#[from] odoh_rs::Error),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OdohConfigLookupSecurity {
    EncryptedDns,
    PlainDns,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OdohConfigSourceKind {
    Bundled,
    HttpsSvcb,
    CustomBytes,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OdohConfigSource {
    Bundled { configs_wire: Vec<u8>, retrieved_at_secs: u64, ttl_secs: u64 },
    HttpsSvcb { response_packet: Vec<u8>, lookup_security: OdohConfigLookupSecurity, retrieved_at_secs: u64 },
    CustomBytes { configs_wire: Vec<u8>, retrieved_at_secs: u64, ttl_secs: u64 },
}

impl OdohConfigSource {
    pub fn resolve_at(&self, now_secs: u64) -> Result<OdohResolvedConfig, OdohError> {
        match self {
            Self::Bundled { configs_wire, retrieved_at_secs, ttl_secs } => resolve_config_wire(
                configs_wire,
                OdohConfigSourceKind::Bundled,
                *retrieved_at_secs,
                *ttl_secs,
                now_secs,
            ),
            Self::CustomBytes { configs_wire, retrieved_at_secs, ttl_secs } => resolve_config_wire(
                configs_wire,
                OdohConfigSourceKind::CustomBytes,
                *retrieved_at_secs,
                *ttl_secs,
                now_secs,
            ),
            Self::HttpsSvcb { response_packet, lookup_security, retrieved_at_secs } => {
                if matches!(lookup_security, OdohConfigLookupSecurity::PlainDns) {
                    return Err(OdohError::PlaintextConfigLookup);
                }

                let bindings = crate::parse_https_service_bindings(response_packet)?;
                let binding =
                    bindings.iter().find(|record| record.odoh_config.is_some()).ok_or(OdohError::MissingConfig)?;
                resolve_config_wire(
                    binding.odoh_config.as_deref().ok_or(OdohError::MissingConfig)?,
                    OdohConfigSourceKind::HttpsSvcb,
                    *retrieved_at_secs,
                    u64::from(binding.ttl_secs),
                    now_secs,
                )
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct OdohResolvedConfig {
    target_config: OdohTargetConfig,
    source_kind: OdohConfigSourceKind,
    retrieved_at_secs: u64,
    expires_at_secs: u64,
}

impl OdohResolvedConfig {
    pub fn target_config(&self) -> &OdohTargetConfig {
        &self.target_config
    }

    pub fn source_kind(&self) -> OdohConfigSourceKind {
        self.source_kind
    }

    pub fn retrieved_at_secs(&self) -> u64 {
        self.retrieved_at_secs
    }

    pub fn expires_at_secs(&self) -> u64 {
        self.expires_at_secs
    }

    pub fn is_fresh_at(&self, now_secs: u64) -> bool {
        now_secs < self.expires_at_secs
    }
}

fn resolve_config_wire(
    configs_wire: &[u8],
    source_kind: OdohConfigSourceKind,
    retrieved_at_secs: u64,
    ttl_secs: u64,
    now_secs: u64,
) -> Result<OdohResolvedConfig, OdohError> {
    let expires_at_secs = retrieved_at_secs.saturating_add(ttl_secs);
    if now_secs >= expires_at_secs {
        return Err(OdohError::StaleConfig { now_secs, expires_at_secs });
    }

    Ok(OdohResolvedConfig {
        target_config: OdohTargetConfig::parse_configs(configs_wire)?,
        source_kind,
        retrieved_at_secs,
        expires_at_secs,
    })
}

#[derive(Debug, Clone)]
pub struct OdohTargetConfig {
    contents: ObliviousDoHConfigContents,
    key_id: Vec<u8>,
}

impl OdohTargetConfig {
    /// Picks the first supported entry of an `ObliviousDoHConfigs` list. Entries
    /// with another version, KEM, KDF or AEAD are skipped (RFC 9230 5); odoh-rs
    /// would fail the whole list on the first one, so the list is split here and
    /// only version 0x0001 contents are handed to it.
    pub fn parse_configs(configs_wire: &[u8]) -> Result<Self, OdohError> {
        let mut list = read_u16_prefixed(configs_wire).ok_or(odoh_rs::Error::ShortInput)?;
        while !list.is_empty() {
            let (version, rest) = list.split_at_checked(2).ok_or(odoh_rs::Error::ShortInput)?;
            let contents = read_u16_prefixed(rest).ok_or(odoh_rs::Error::ShortInput)?;
            list = &rest[2 + contents.len()..];
            if u16::from_be_bytes([version[0], version[1]]) != ODOH_VERSION {
                continue;
            }
            let mut contents_bytes = Bytes::copy_from_slice(contents);
            if let Ok(contents) = parse::<ObliviousDoHConfigContents, _>(&mut contents_bytes) {
                let key_id = contents.identifier()?;
                return Ok(Self { contents, key_id });
            }
        }
        Err(OdohError::NoSupportedConfig)
    }

    pub fn key_id(&self) -> &[u8] {
        &self.key_id
    }

    pub fn encrypt_query(&self, dns_message: &[u8], padding_len: usize) -> Result<OdohEncryptedQuery, OdohError> {
        let plaintext = ObliviousDoHMessagePlaintext::new(dns_message, padding_len);
        let mut rng: rand::rngs::StdRng = rand::make_rng();
        let (message, secret) = encrypt_query(&plaintext, &self.contents, &mut rng)?;
        let wire_message = compose(&message)?.to_vec();
        Ok(OdohEncryptedQuery {
            wire_message,
            key_id: self.key_id.clone(),
            context: OdohQueryContext { plaintext, secret },
        })
    }
}

/// The bytes of a `u16`-length-prefixed field at the start of `input`.
fn read_u16_prefixed(input: &[u8]) -> Option<&[u8]> {
    let (length, rest) = input.split_at_checked(2)?;
    rest.get(..usize::from(u16::from_be_bytes([length[0], length[1]])))
}

#[derive(Debug, Clone)]
pub struct OdohEncryptedQuery {
    wire_message: Vec<u8>,
    key_id: Vec<u8>,
    context: OdohQueryContext,
}

impl OdohEncryptedQuery {
    pub fn wire_message(&self) -> &[u8] {
        &self.wire_message
    }

    pub fn key_id(&self) -> &[u8] {
        &self.key_id
    }

    pub fn decrypt_response(&self, response_wire: &[u8]) -> Result<OdohPlainResponse, OdohError> {
        self.context.decrypt_response(response_wire)
    }
}

#[derive(Debug, Clone)]
pub struct OdohQueryContext {
    plaintext: ObliviousDoHMessagePlaintext,
    secret: OdohSecret,
}

impl OdohQueryContext {
    pub fn decrypt_response(&self, response_wire: &[u8]) -> Result<OdohPlainResponse, OdohError> {
        let mut response_bytes: Bytes = response_wire.to_vec().into();
        let response: ObliviousDoHMessage = parse(&mut response_bytes)?;
        let plaintext = decrypt_response(&self.plaintext, &response, self.secret)?;
        let padding_len = plaintext.padding_len();
        Ok(OdohPlainResponse { dns_message: plaintext.into_msg().to_vec(), padding_len })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OdohPlainResponse {
    dns_message: Vec<u8>,
    padding_len: usize,
}

impl OdohPlainResponse {
    pub fn dns_message(&self) -> &[u8] {
        &self.dns_message
    }

    pub fn padding_len(&self) -> usize {
        self.padding_len
    }
}
