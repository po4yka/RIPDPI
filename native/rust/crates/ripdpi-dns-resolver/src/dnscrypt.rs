use crypto_box::aead::Aead;
use crypto_box::ChaChaBox;
use ring::signature::{self, UnparsedPublicKey};

use crate::types::{DnsCryptCachedCertificate, EncryptedDnsEndpoint, EncryptedDnsError};

pub(crate) const DNSCRYPT_CERT_MAGIC: [u8; 4] = *b"DNSC";
pub(crate) const DNSCRYPT_ES_VERSION: u16 = 2;
pub(crate) const DNSCRYPT_RESPONSE_MAGIC: [u8; 8] = [0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38];
pub(crate) const DNSCRYPT_NONCE_SIZE: usize = 24;
pub(crate) const DNSCRYPT_QUERY_NONCE_HALF: usize = DNSCRYPT_NONCE_SIZE / 2;
pub(crate) const DNSCRYPT_CERT_SIZE: usize = 124;
pub(crate) const DNSCRYPT_PADDING_BLOCK_SIZE: usize = 64;

pub(crate) fn dnscrypt_provider_name(endpoint: &EncryptedDnsEndpoint) -> Result<String, EncryptedDnsError> {
    endpoint
        .dnscrypt_provider_name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
        .ok_or(EncryptedDnsError::MissingDnsCryptProviderName)
}

pub(crate) fn dnscrypt_verifying_key(endpoint: &EncryptedDnsEndpoint) -> Result<[u8; 32], EncryptedDnsError> {
    let encoded = endpoint
        .dnscrypt_public_key
        .as_deref()
        .ok_or_else(|| EncryptedDnsError::InvalidDnsCryptPublicKey("missing public key".to_string()))?;
    let mut bytes = [0u8; 32];
    hex::decode_to_slice(encoded.trim(), &mut bytes)
        .map_err(|err| EncryptedDnsError::InvalidDnsCryptPublicKey(err.to_string()))?;
    Ok(bytes)
}

pub(crate) fn parse_dnscrypt_certificate(
    bytes: &[u8],
    verifying_key: &[u8; 32],
    _provider_name: &str,
) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
    if bytes.len() != DNSCRYPT_CERT_SIZE {
        return Err(EncryptedDnsError::DnsCryptCertificate(format!("unexpected certificate size {}", bytes.len())));
    }
    if bytes[..4] != DNSCRYPT_CERT_MAGIC {
        return Err(EncryptedDnsError::DnsCryptCertificate("unexpected cert magic".to_string()));
    }
    let es_version = u16::from_be_bytes([bytes[4], bytes[5]]);
    if es_version != DNSCRYPT_ES_VERSION {
        return Err(EncryptedDnsError::DnsCryptCertificate(format!("unsupported es_version {es_version}")));
    }

    let signature = &bytes[8..72];
    let signed = &bytes[72..];
    let public_key = UnparsedPublicKey::new(&signature::ED25519, verifying_key);
    public_key
        .verify(signed, signature)
        .map_err(|_| EncryptedDnsError::DnsCryptVerification("ed25519 signature verification failed".to_string()))?;

    let mut resolver_public_key = [0u8; 32];
    resolver_public_key.copy_from_slice(&bytes[72..104]);
    let mut client_magic = [0u8; 8];
    client_magic.copy_from_slice(&bytes[104..112]);
    let valid_from = u32::from_be_bytes([bytes[116], bytes[117], bytes[118], bytes[119]]);
    let valid_until = u32::from_be_bytes([bytes[120], bytes[121], bytes[122], bytes[123]]);

    Ok(DnsCryptCachedCertificate { resolver_public_key, client_magic, valid_from, valid_until })
}

pub(crate) fn decrypt_dnscrypt_response(
    crypto_box: &ChaChaBox,
    response: &[u8],
    expected_nonce_prefix: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    if response.len() <= 8 + DNSCRYPT_NONCE_SIZE {
        return Err(EncryptedDnsError::DnsCryptDecrypt("response too short".to_string()));
    }
    if response[..8] != DNSCRYPT_RESPONSE_MAGIC {
        return Err(EncryptedDnsError::DnsCryptDecrypt("unexpected response magic".to_string()));
    }
    let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
    nonce.copy_from_slice(&response[8..8 + DNSCRYPT_NONCE_SIZE]);
    if nonce[..DNSCRYPT_QUERY_NONCE_HALF] != *expected_nonce_prefix {
        return Err(EncryptedDnsError::DnsCryptDecrypt("nonce prefix mismatch".to_string()));
    }
    let plaintext = crypto_box
        .decrypt((&nonce).into(), &response[8 + DNSCRYPT_NONCE_SIZE..])
        .map_err(|err| EncryptedDnsError::DnsCryptDecrypt(err.to_string()))?;
    dnscrypt_unpad(&plaintext)
}

pub(crate) fn dnscrypt_pad(payload: &[u8]) -> Vec<u8> {
    let target_len = (payload.len() + 1).div_ceil(DNSCRYPT_PADDING_BLOCK_SIZE) * DNSCRYPT_PADDING_BLOCK_SIZE;
    let mut padded = Vec::with_capacity(target_len);
    padded.extend_from_slice(payload);
    padded.push(0x80);
    while padded.len() % DNSCRYPT_PADDING_BLOCK_SIZE != 0 {
        padded.push(0x00);
    }
    padded
}

pub(crate) fn dnscrypt_unpad(payload: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
    let marker = payload
        .iter()
        .rposition(|byte| *byte != 0x00)
        .ok_or_else(|| EncryptedDnsError::DnsCryptDecrypt("missing padding marker".to_string()))?;
    if payload[marker] != 0x80 {
        return Err(EncryptedDnsError::DnsCryptDecrypt("invalid padding marker".to_string()));
    }
    Ok(payload[..marker].to_vec())
}
