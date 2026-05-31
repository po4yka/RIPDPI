use crate::types::EncryptedDnsError;

use super::cipher::DnsCryptCipher;
use super::padding::dnscrypt_unpad;
use super::{DNSCRYPT_NONCE_SIZE, DNSCRYPT_QUERY_NONCE_HALF, DNSCRYPT_RESPONSE_MAGIC};

pub(crate) fn decrypt_dnscrypt_response(
    cipher: &DnsCryptCipher,
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
    let plaintext = cipher
        .decrypt(&nonce, &response[8 + DNSCRYPT_NONCE_SIZE..])
        .map_err(|err| EncryptedDnsError::DnsCryptDecrypt(err.to_string()))?;
    dnscrypt_unpad(&plaintext)
}
