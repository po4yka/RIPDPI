use bytes::Bytes;
use odoh_rs::{
    compose, decrypt_response, encrypt_query, parse, ObliviousDoHConfigContents, ObliviousDoHConfigs,
    ObliviousDoHMessage, ObliviousDoHMessagePlaintext, OdohSecret,
};
use rand_09::SeedableRng;
use thiserror::Error;

pub const ODOH_MESSAGE_MEDIA_TYPE: &str = "application/oblivious-dns-message";

#[derive(Debug, Error)]
pub enum OdohError {
    #[error("ODoH configs do not contain a supported RFC 9230 target config")]
    NoSupportedConfig,
    #[error("ODoH protocol failed: {0}")]
    Protocol(#[from] odoh_rs::Error),
}

#[derive(Debug, Clone)]
pub struct OdohTargetConfig {
    contents: ObliviousDoHConfigContents,
    key_id: Vec<u8>,
}

impl OdohTargetConfig {
    pub fn parse_configs(configs_wire: &[u8]) -> Result<Self, OdohError> {
        let mut configs_bytes: Bytes = configs_wire.to_vec().into();
        let configs: ObliviousDoHConfigs = parse(&mut configs_bytes)?;
        let contents: ObliviousDoHConfigContents =
            configs.supported().into_iter().next().ok_or(OdohError::NoSupportedConfig)?.into();
        let key_id = contents.identifier()?;
        Ok(Self { contents, key_id })
    }

    pub fn key_id(&self) -> &[u8] {
        &self.key_id
    }

    pub fn encrypt_query(&self, dns_message: &[u8], padding_len: usize) -> Result<OdohEncryptedQuery, OdohError> {
        let plaintext = ObliviousDoHMessagePlaintext::new(dns_message, padding_len);
        let mut rng = rand_09::rngs::StdRng::from_os_rng();
        let (message, secret) = encrypt_query(&plaintext, &self.contents, &mut rng)?;
        let wire_message = compose(&message)?.to_vec();
        Ok(OdohEncryptedQuery {
            wire_message,
            key_id: self.key_id.clone(),
            context: OdohQueryContext { plaintext, secret },
        })
    }
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
