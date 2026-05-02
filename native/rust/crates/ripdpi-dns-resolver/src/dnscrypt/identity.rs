use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError};

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
