use ring::rand::{SecureRandom, SystemRandom};

use crate::types::EncryptedDnsError;

use super::DNSCRYPT_PADDING_BLOCK_SIZE;

/// Largest padded query plaintext: with the 52-byte header and the 16-byte tag,
/// the encrypted query stays within the DNSCrypt limit of 4096 bytes.
pub(crate) const DNSCRYPT_MAX_PADDED_QUERY: usize =
    (4096 - 52 - 16) / DNSCRYPT_PADDING_BLOCK_SIZE * DNSCRYPT_PADDING_BLOCK_SIZE;
/// Most padding bytes, the 0x80 delimiter included, added to one query.
const DNSCRYPT_MAX_PADDING: usize = 256;

/// ISO/IEC 7816-4 padding to a random multiple of the block size with 1 to 256
/// padding bytes, so repeated TCP queries do not share one length.
pub(crate) fn dnscrypt_pad(payload: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
    let block = DNSCRYPT_PADDING_BLOCK_SIZE;
    let shortest = (payload.len() + 1).div_ceil(block) * block;
    let longest = ((payload.len() + DNSCRYPT_MAX_PADDING) / block * block).min(DNSCRYPT_MAX_PADDED_QUERY);
    if shortest > longest {
        return Err(EncryptedDnsError::DnsMessageTooLarge {
            transport: "dnscrypt",
            size: payload.len(),
            max: DNSCRYPT_MAX_PADDED_QUERY - 1,
        });
    }
    let mut random = [0u8; 1];
    SystemRandom::new()
        .fill(&mut random)
        .map_err(|_| EncryptedDnsError::Request("failed to generate random padding length".to_string()))?;
    // At most 4 lengths are possible, so the modulo bias of one byte is negligible.
    let extra_blocks = usize::from(random[0]) % ((longest - shortest) / block + 1);
    let mut padded = Vec::with_capacity(shortest + extra_blocks * block);
    padded.extend_from_slice(payload);
    padded.push(0x80);
    padded.resize(shortest + extra_blocks * block, 0x00);
    Ok(padded)
}

/// Receivers must accept any valid padding length (draft-denis-dprive-dnscrypt,
/// Padding), so only the delimiter is checked.
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
