use ring::signature::{self, UnparsedPublicKey};

use crate::types::{DnsCryptCachedCertificate, EncryptedDnsError};

use super::{DNSCRYPT_CERT_MAGIC, DNSCRYPT_CERT_SIZE, DNSCRYPT_ES_VERSION};

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
