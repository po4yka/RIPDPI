use crate::types::EncryptedDnsError;

use super::DNSCRYPT_PADDING_BLOCK_SIZE;

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
