use ring::signature::{self, UnparsedPublicKey};

use crate::types::{DnsCryptCachedCertificate, EncryptedDnsError};

use super::{DNSCRYPT_CERT_MAGIC, DNSCRYPT_CERT_SIZE, DNSCRYPT_ES_VERSION_XCHACHA, DNSCRYPT_ES_VERSION_XSALSA};

pub(crate) fn parse_dnscrypt_certificate(
    bytes: &[u8],
    verifying_key: &[u8; 32],
    _provider_name: &str,
) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
    // Bytes past the fixed fields are extensions, covered by the signature.
    if bytes.len() < DNSCRYPT_CERT_SIZE {
        return Err(EncryptedDnsError::DnsCryptCertificate(format!("unexpected certificate size {}", bytes.len())));
    }
    if bytes[..4] != DNSCRYPT_CERT_MAGIC {
        return Err(EncryptedDnsError::DnsCryptCertificate("unexpected cert magic".to_string()));
    }
    let es_version = u16::from_be_bytes([bytes[4], bytes[5]]);
    if es_version != DNSCRYPT_ES_VERSION_XSALSA && es_version != DNSCRYPT_ES_VERSION_XCHACHA {
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
    let serial = u32::from_be_bytes([bytes[112], bytes[113], bytes[114], bytes[115]]);
    let valid_from = u32::from_be_bytes([bytes[116], bytes[117], bytes[118], bytes[119]]);
    let valid_until = u32::from_be_bytes([bytes[120], bytes[121], bytes[122], bytes[123]]);
    if valid_from >= valid_until {
        return Err(EncryptedDnsError::DnsCryptCertificate("certificate ts-start is not before ts-end".to_string()));
    }

    Ok(DnsCryptCachedCertificate { resolver_public_key, client_magic, serial, valid_from, valid_until, es_version })
}

/// Picks the certificate to use from the provider's TXT records, following the
/// DNSCrypt "Validation and Selection" rules: a record that does not parse or
/// verify is ignored, only certificates valid at `now` count, XChaCha20 is
/// preferred over XSalsa20, then the highest serial, then the latest ts-end.
/// When nothing is usable, the error of the last rejected record is returned.
pub(crate) fn select_dnscrypt_certificate<'a>(
    records: impl IntoIterator<Item = &'a [u8]>,
    verifying_key: &[u8; 32],
    provider_name: &str,
    now: u32,
) -> Result<DnsCryptCachedCertificate, EncryptedDnsError> {
    let preference = |certificate: &DnsCryptCachedCertificate| {
        (certificate.es_version == DNSCRYPT_ES_VERSION_XCHACHA, certificate.serial, certificate.valid_until)
    };
    let mut best: Option<DnsCryptCachedCertificate> = None;
    let mut last_error = None;
    for bytes in records {
        match parse_dnscrypt_certificate(bytes, verifying_key, provider_name) {
            Ok(certificate) if certificate.valid_from <= now && now <= certificate.valid_until => {
                if best.as_ref().is_none_or(|current| preference(&certificate) > preference(current)) {
                    best = Some(certificate);
                }
            }
            Ok(_) => {
                last_error = Some(EncryptedDnsError::DnsCryptCertificate(
                    "certificate is outside its validity window".to_string(),
                ));
            }
            Err(error) => last_error = Some(error),
        }
    }
    best.ok_or_else(|| {
        last_error.unwrap_or_else(|| {
            EncryptedDnsError::DnsCryptCertificate("resolver did not return a valid certificate".to_string())
        })
    })
}
