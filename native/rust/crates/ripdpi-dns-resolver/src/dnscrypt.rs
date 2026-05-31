mod certificate;
mod cipher;
mod framing;
mod identity;
mod padding;

pub(crate) use certificate::parse_dnscrypt_certificate;
pub(crate) use cipher::DnsCryptCipher;
pub(crate) use framing::decrypt_dnscrypt_response;
pub(crate) use identity::{dnscrypt_provider_name, dnscrypt_verifying_key};
pub(crate) use padding::{dnscrypt_pad, dnscrypt_unpad};

pub(crate) const DNSCRYPT_CERT_MAGIC: [u8; 4] = *b"DNSC";
/// `es_version` for the classic NaCl box (XSalsa20Poly1305), RFC 8937 / DNSCrypt v2.
pub(crate) const DNSCRYPT_ES_VERSION_XSALSA: u16 = 1;
/// `es_version` for XChaCha20Poly1305, the default DNSCrypt v2 suite.
pub(crate) const DNSCRYPT_ES_VERSION_XCHACHA: u16 = 2;
pub(crate) const DNSCRYPT_RESPONSE_MAGIC: [u8; 8] = [0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38];
pub(crate) const DNSCRYPT_NONCE_SIZE: usize = 24;
pub(crate) const DNSCRYPT_QUERY_NONCE_HALF: usize = DNSCRYPT_NONCE_SIZE / 2;
pub(crate) const DNSCRYPT_CERT_SIZE: usize = 124;
pub(crate) const DNSCRYPT_PADDING_BLOCK_SIZE: usize = 64;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::EncryptedDnsError;
    use crate::types::{EncryptedDnsEndpoint, EncryptedDnsProtocol};
    use crypto_box::ChaChaBox;
    use ring::signature::{Ed25519KeyPair, KeyPair};

    /// Build a valid Ed25519-signed DNSCrypt certificate for testing.
    ///
    /// The ed25519 signature covers `bytes[72..]` only, so the es_version field at
    /// `bytes[4..6]` can be overwritten without invalidating the signature.
    fn test_keypair_and_cert_with_es_version(es_version: u16) -> ([u8; 32], Vec<u8>) {
        let key_pair = Ed25519KeyPair::from_seed_unchecked(&[7u8; 32]).expect("ed25519 keypair");
        let public_bytes: [u8; 32] = key_pair.public_key().as_ref().try_into().unwrap();

        let resolver_secret = [9u8; 32];
        let valid_from: u32 = 1_700_000_000;
        let valid_until: u32 = 1_700_086_400;
        let mut client_magic = [0u8; 8];
        client_magic.copy_from_slice(&resolver_secret[..8]);

        let mut inner = [0u8; 52];
        inner[..32].copy_from_slice(&resolver_secret);
        inner[32..40].copy_from_slice(&client_magic);
        inner[40..44].copy_from_slice(&1u32.to_be_bytes());
        inner[44..48].copy_from_slice(&valid_from.to_be_bytes());
        inner[48..52].copy_from_slice(&valid_until.to_be_bytes());
        let signature = key_pair.sign(&inner);

        let mut cert = Vec::with_capacity(DNSCRYPT_CERT_SIZE);
        cert.extend_from_slice(&DNSCRYPT_CERT_MAGIC);
        cert.extend_from_slice(&es_version.to_be_bytes());
        cert.extend_from_slice(&0u16.to_be_bytes());
        cert.extend_from_slice(signature.as_ref());
        cert.extend_from_slice(&inner);
        assert_eq!(cert.len(), DNSCRYPT_CERT_SIZE);

        (public_bytes, cert)
    }

    fn test_keypair_and_cert() -> ([u8; 32], Vec<u8>) {
        test_keypair_and_cert_with_es_version(DNSCRYPT_ES_VERSION_XCHACHA)
    }

    fn make_endpoint(public_key: Option<&str>) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: None,
            host: "test".into(),
            port: 443,
            tls_server_name: None,
            bootstrap_ips: vec![],
            doh_url: None,
            dnscrypt_provider_name: Some("test".into()),
            dnscrypt_public_key: public_key.map(String::from),
            odoh: None,
        }
    }

    // -- parse_dnscrypt_certificate tests --

    #[test]
    fn parse_certificate_accepts_valid_signature() {
        let (pk, cert) = test_keypair_and_cert();
        let parsed = parse_dnscrypt_certificate(&cert, &pk, "test.provider").expect("valid cert");
        assert_eq!(parsed.valid_from, 1_700_000_000);
        assert_eq!(parsed.valid_until, 1_700_086_400);
    }

    #[test]
    fn parse_certificate_extracts_resolver_key_and_client_magic() {
        let (pk, cert) = test_keypair_and_cert();
        let parsed = parse_dnscrypt_certificate(&cert, &pk, "test.provider").expect("valid cert");
        assert_eq!(&parsed.resolver_public_key, &cert[72..104]);
        assert_eq!(&parsed.client_magic, &cert[104..112]);
    }

    #[test]
    fn parse_certificate_rejects_tampered_signature() {
        let (pk, mut cert) = test_keypair_and_cert();
        cert[10] ^= 0xff;
        let err = parse_dnscrypt_certificate(&cert, &pk, "test.provider").unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptVerification(_)));
    }

    #[test]
    fn parse_certificate_rejects_wrong_verifying_key() {
        let (_pk, cert) = test_keypair_and_cert();
        let wrong_key = [0xAA; 32];
        let err = parse_dnscrypt_certificate(&cert, &wrong_key, "test.provider").unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptVerification(_)));
    }

    #[test]
    fn parse_certificate_rejects_wrong_size() {
        let (pk, cert) = test_keypair_and_cert();
        let err = parse_dnscrypt_certificate(&cert[..100], &pk, "test.provider").unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptCertificate(_)));
    }

    #[test]
    fn parse_certificate_rejects_bad_magic() {
        let (pk, mut cert) = test_keypair_and_cert();
        cert[0..4].copy_from_slice(b"NOPE");
        let err = parse_dnscrypt_certificate(&cert, &pk, "test.provider").unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptCertificate(_)));
    }

    #[test]
    fn parse_certificate_rejects_unsupported_es_version() {
        let (pk, mut cert) = test_keypair_and_cert();
        cert[4..6].copy_from_slice(&3u16.to_be_bytes());
        let err = parse_dnscrypt_certificate(&cert, &pk, "test.provider").unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptCertificate(_)));
    }

    #[test]
    fn parse_certificate_accepts_xsalsa_es_version() {
        let (pk, cert) = test_keypair_and_cert_with_es_version(DNSCRYPT_ES_VERSION_XSALSA);
        let parsed = parse_dnscrypt_certificate(&cert, &pk, "test.provider").expect("valid xsalsa cert");
        assert_eq!(parsed.es_version, DNSCRYPT_ES_VERSION_XSALSA);

        // The stored es_version must route to the XSalsa cipher variant.
        use crypto_box::{PublicKey, SecretKey};
        let sk = SecretKey::from([1u8; 32]);
        let resolver_pk = PublicKey::from(parsed.resolver_public_key);
        let cipher = DnsCryptCipher::new(parsed.es_version, &resolver_pk, &sk).expect("xsalsa cipher");
        assert!(matches!(cipher, DnsCryptCipher::XSalsa(_)));
    }

    #[test]
    fn parse_certificate_accepts_xchacha_es_version() {
        let (pk, cert) = test_keypair_and_cert_with_es_version(DNSCRYPT_ES_VERSION_XCHACHA);
        let parsed = parse_dnscrypt_certificate(&cert, &pk, "test.provider").expect("valid xchacha cert");
        assert_eq!(parsed.es_version, DNSCRYPT_ES_VERSION_XCHACHA);
    }

    // -- dnscrypt_verifying_key tests --

    #[test]
    fn verifying_key_parses_valid_hex() {
        let endpoint = make_endpoint(Some("0102030405060708091011121314151617181920212223242526272829303132"));
        let key = dnscrypt_verifying_key(&endpoint).expect("valid key");
        assert_eq!(key[0], 0x01);
        assert_eq!(key[31], 0x32);
    }

    #[test]
    fn verifying_key_rejects_missing_public_key() {
        let endpoint = make_endpoint(None);
        assert!(dnscrypt_verifying_key(&endpoint).is_err());
    }

    #[test]
    fn verifying_key_rejects_invalid_hex() {
        let endpoint = make_endpoint(Some("not_hex"));
        assert!(dnscrypt_verifying_key(&endpoint).is_err());
    }

    // -- dnscrypt_pad / dnscrypt_unpad tests --

    #[test]
    fn pad_unpad_round_trips() {
        for size in [0, 1, 10, 63, 64, 65, 127, 128] {
            let payload: Vec<u8> = (0..size).map(|i: usize| (i % 255) as u8).collect();
            let padded = dnscrypt_pad(&payload);
            assert_eq!(padded.len() % DNSCRYPT_PADDING_BLOCK_SIZE, 0, "padded size for input len {size}");
            assert!(padded.len() > payload.len(), "room for marker at len {size}");
            let unpadded = dnscrypt_unpad(&padded).expect("unpad");
            assert_eq!(unpadded, payload, "round-trip at len {size}");
        }
    }

    #[test]
    fn unpad_rejects_all_zeros() {
        let bad = vec![0u8; 64];
        assert!(dnscrypt_unpad(&bad).is_err());
    }

    // ---- dnscrypt_provider_name tests ----

    #[test]
    fn provider_name_extracts_trimmed_name() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = Some("  2.dnscrypt-cert.example.com  ".to_string());
        assert_eq!(dnscrypt_provider_name(&ep).unwrap(), "2.dnscrypt-cert.example.com");
    }

    #[test]
    fn provider_name_rejects_none() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = None;
        assert!(dnscrypt_provider_name(&ep).is_err());
    }

    #[test]
    fn provider_name_rejects_empty_string() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = Some("".to_string());
        assert!(dnscrypt_provider_name(&ep).is_err());
    }

    #[test]
    fn provider_name_rejects_whitespace_only() {
        let mut ep = make_endpoint(None);
        ep.dnscrypt_provider_name = Some("   ".to_string());
        assert!(dnscrypt_provider_name(&ep).is_err());
    }

    // ---- decrypt_dnscrypt_response tests ----

    #[test]
    fn decrypt_response_rejects_too_short() {
        use crypto_box::{PublicKey, SecretKey};
        let sk = SecretKey::from([1u8; 32]);
        let pk = PublicKey::from([2u8; 32]);
        let cipher = DnsCryptCipher::XChaCha(ChaChaBox::new(&pk, &sk));
        let short = vec![0u8; 8 + DNSCRYPT_NONCE_SIZE]; // exactly at boundary, not >
        let err = decrypt_dnscrypt_response(&cipher, &short, &[0u8; 12]).unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptDecrypt(_)));
    }

    #[test]
    fn decrypt_response_rejects_bad_magic() {
        use crypto_box::{PublicKey, SecretKey};
        let sk = SecretKey::from([1u8; 32]);
        let pk = PublicKey::from([2u8; 32]);
        let cipher = DnsCryptCipher::XChaCha(ChaChaBox::new(&pk, &sk));
        let mut response = vec![0u8; 100];
        response[..8].copy_from_slice(b"BADMAGIC");
        let err = decrypt_dnscrypt_response(&cipher, &response, &[0u8; 12]).unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptDecrypt(_)));
    }

    #[test]
    fn decrypt_response_rejects_nonce_prefix_mismatch() {
        use crypto_box::{PublicKey, SecretKey};
        let sk = SecretKey::from([1u8; 32]);
        let pk = PublicKey::from([2u8; 32]);
        let cipher = DnsCryptCipher::XChaCha(ChaChaBox::new(&pk, &sk));
        let mut response = vec![0u8; 100];
        response[..8].copy_from_slice(&DNSCRYPT_RESPONSE_MAGIC);
        response[8..20].copy_from_slice(&[0xAA; 12]);
        let expected_prefix = [0xBB; 12];
        let err = decrypt_dnscrypt_response(&cipher, &response, &expected_prefix).unwrap_err();
        assert!(matches!(err, EncryptedDnsError::DnsCryptDecrypt(_)));
    }
}
