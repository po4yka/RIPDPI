use std::sync::{Arc, LazyLock, Once};

use ripdpi_tls_profiles::{EchFacadeError, EchSetup};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::crypto::hash::{Hash, HashAlgorithm};
use rustls::crypto::{WebPkiSupportedAlgorithms, aws_lc_rs};
use rustls::pki_types::SignatureVerificationAlgorithm;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    CertificateError, ClientConfig as RustlsClientConfig, DigitallySignedStruct, RootCertStore, SignatureScheme,
    SupportedCipherSuite,
};

/// SHA-256 digest of the server leaf certificate in DER form.
pub(crate) type CertificatePinSha256 = [u8; 32];

static RUSTLS_PROVIDER: Once = Once::new();

#[cfg(test)]
pub(crate) fn default_tls_config() -> Arc<RustlsClientConfig> {
    tls_config_with_ech_setup(&EchSetup::Grease, None).expect("default NaiveProxy TLS config must support ECH GREASE")
}

/// Parse `--server-pin-sha256`: exactly 64 lowercase hex digits (the Kotlin side normalizes first).
pub(crate) fn parse_certificate_pin_sha256(raw: &str) -> Result<CertificatePinSha256, String> {
    if raw.len() != 64 || !raw.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)) {
        return Err("expected 64 lowercase hex digits".to_owned());
    }
    let mut pin = [0_u8; 32];
    for (slot, pair) in pin.iter_mut().zip(raw.as_bytes().as_chunks::<2>().0) {
        let digit = |byte: u8| if byte.is_ascii_digit() { byte - b'0' } else { byte - b'a' + 10 };
        *slot = (digit(pair[0]) << 4) | digit(pair[1]);
    }
    Ok(pin)
}

/// With a pin the webpki root check is replaced by [`PinnedLeafVerifier`].
pub(crate) fn tls_config_with_ech_setup(
    setup: &EchSetup,
    pin: Option<CertificatePinSha256>,
) -> Result<Arc<RustlsClientConfig>, EchFacadeError> {
    ensure_rustls_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let provider = Arc::new(chrome_ordered_provider());
    let builder = ripdpi_tls_profiles::configure_rustls_ech(
        RustlsClientConfig::builder_with_provider(Arc::clone(&provider)),
        setup,
    )?;
    let mut config = match pin {
        Some(pin) => builder
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(PinnedLeafVerifier { pin, provider }))
            .with_no_client_auth(),
        // Infallible: the store is seeded from webpki_roots::TLS_SERVER_ROOTS and no CRLs are set.
        None => ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
            .expect("bundled webpki roots are non-empty")
            .with_no_client_auth(),
    };
    // Chrome offers both; connect_tunnel refuses a session that does not negotiate h2.
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

/// Chrome's signature algorithm list, in Chrome's order. Chrome sends neither
/// Ed25519 nor P-521, so a server certificate with such a key fails, as it does
/// in Chrome and the upstream client.
const CHROME_SIGNATURE_SCHEMES: [SignatureScheme; 8] = [
    SignatureScheme::ECDSA_NISTP256_SHA256,
    SignatureScheme::RSA_PSS_SHA256,
    SignatureScheme::RSA_PKCS1_SHA256,
    SignatureScheme::ECDSA_NISTP384_SHA384,
    SignatureScheme::RSA_PSS_SHA384,
    SignatureScheme::RSA_PKCS1_SHA384,
    SignatureScheme::RSA_PSS_SHA512,
    SignatureScheme::RSA_PKCS1_SHA512,
];

type SchemeMapping = (SignatureScheme, &'static [&'static dyn SignatureVerificationAlgorithm]);

static CHROME_SIGNATURE_MAPPING: LazyLock<Vec<SchemeMapping>> = LazyLock::new(|| {
    let defaults = aws_lc_rs::default_provider().signature_verification_algorithms.mapping;
    CHROME_SIGNATURE_SCHEMES
        .iter()
        .filter_map(|scheme| defaults.iter().find(|(candidate, _)| candidate == scheme).copied())
        .collect()
});

/// The aws-lc-rs provider with Chrome's ClientHello lists. X25519MLKEM768 comes
/// first, so rustls sends its share together with the X25519 component share and
/// a server that prefers the hybrid group needs no HelloRetryRequest.
fn chrome_ordered_provider() -> CryptoProvider {
    use aws_lc_rs::{cipher_suite as suite, kx_group};
    let defaults = aws_lc_rs::default_provider();
    CryptoProvider {
        cipher_suites: vec![
            suite::TLS13_AES_128_GCM_SHA256,
            suite::TLS13_AES_256_GCM_SHA384,
            suite::TLS13_CHACHA20_POLY1305_SHA256,
            suite::TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            suite::TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            suite::TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            suite::TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            suite::TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            suite::TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        ],
        kx_groups: vec![kx_group::X25519MLKEM768, kx_group::X25519, kx_group::SECP256R1, kx_group::SECP384R1],
        signature_verification_algorithms: WebPkiSupportedAlgorithms {
            all: defaults.signature_verification_algorithms.all,
            mapping: CHROME_SIGNATURE_MAPPING.as_slice(),
        },
        ..defaults
    }
}

/// Accepts a server only when the SHA-256 of its TLS leaf certificate (DER) equals the pin.
///
/// Semantics match the Hysteria2 `pinSHA256` with `insecure`: the pin replaces CA
/// validation, so a private-CA or self-signed server works, and the hostname is
/// not checked (the pin already names one exact certificate). The handshake
/// signature is still verified with the leaf key, so a match proves the peer
/// holds the pinned certificate's private key. The pin is public, but the
/// comparison does not exit early. Validity dates are not checked either, as
/// with the Hysteria2 pin. There is no mode that skips verification.
#[derive(Debug)]
struct PinnedLeafVerifier {
    pin: CertificatePinSha256,
    provider: Arc<CryptoProvider>,
}

/// SHA-256 from the provider, taken from a TLS 1.3 SHA-256 suite (no direct hash dependency).
pub(crate) fn sha256(data: &[u8]) -> Option<CertificatePinSha256> {
    let SupportedCipherSuite::Tls13(suite) = rustls::crypto::aws_lc_rs::cipher_suite::TLS13_AES_128_GCM_SHA256 else {
        return None;
    };
    let hasher: &dyn Hash = suite.common.hash_provider;
    if hasher.algorithm() != HashAlgorithm::SHA256 {
        return None;
    }
    hasher.hash(data).as_ref().try_into().ok()
}

fn digests_equal(left: &CertificatePinSha256, right: &CertificatePinSha256) -> bool {
    let difference = left.iter().zip(right).fold(0_u8, |acc, (a, b)| acc | (a ^ b));
    std::hint::black_box(difference) == 0
}

impl ServerCertVerifier for PinnedLeafVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if sha256(end_entity.as_ref()).is_some_and(|digest| digests_equal(&digest, &self.pin)) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(CertificateError::ApplicationVerificationFailure))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

pub(crate) fn ensure_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::aws_lc_rs::default_provider().install_default().expect("install rustls aws-lc provider");
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_tls_profiles::EchSetup;

    #[test]
    fn pin_parser_accepts_only_64_lowercase_hex_and_sha256_matches_fips_vector() {
        let abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assert_eq!(sha256(b"abc"), Some(parse_certificate_pin_sha256(abc).expect("pin")));
        for bad in ["", "ab", &abc.to_uppercase(), &abc[..63], &format!("{abc}0"), &abc.replace('b', "g")] {
            assert!(parse_certificate_pin_sha256(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn naive_proxy_tls_config_uses_ech_facade_grease_for_outbound_tunnel() {
        let config = tls_config_with_ech_setup(&EchSetup::Grease, None).expect("ECH TLS config");

        assert!(rustls_client_hello_has_ech_extension(config), "NaiveProxy must send ECH or GREASE");
    }

    fn client_hello(config: Arc<RustlsClientConfig>) -> Vec<u8> {
        let server_name = rustls::pki_types::ServerName::try_from("inner.example").expect("server name");
        let mut conn = rustls::ClientConnection::new(config, server_name).expect("client conn");
        let mut bytes = Vec::new();
        conn.write_tls(&mut bytes).expect("write ClientHello");
        bytes
    }

    fn rustls_client_hello_has_ech_extension(config: Arc<RustlsClientConfig>) -> bool {
        let bytes = client_hello(config);
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&bytes).expect("parse ClientHello");
        layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d)
    }

    /// The body of the first extension of `ext_type` in a ClientHello record.
    fn extension_body(hello: &[u8], ext_type: u16) -> Vec<u8> {
        let layout = ripdpi_packets::parse_tls_client_hello_layout(hello).expect("parse ClientHello");
        let extension = layout.extensions.iter().find(|extension| extension.ext_type == ext_type).expect("extension");
        hello[extension.data_offset..extension.data_offset + extension.data_len].to_vec()
    }

    fn u16_list(bytes: &[u8]) -> Vec<u16> {
        bytes.as_chunks::<2>().0.iter().map(|pair| u16::from_be_bytes(*pair)).collect()
    }

    // Values from the Chrome 154 ClientHello that the upstream client sent in the
    // wire comparison, with GREASE values removed.
    #[test]
    fn client_hello_uses_chrome_groups_shares_signatures_and_alpn() {
        for pin in [None, Some([7_u8; 32])] {
            let hello = client_hello(tls_config_with_ech_setup(&EchSetup::Grease, pin).expect("TLS config"));

            let groups = extension_body(&hello, 0x000a);
            assert_eq!(u16_list(&groups[2..]), [0x11ec, 0x001d, 0x0017, 0x0018], "supported_groups");
            let shares = extension_body(&hello, 0x0033);
            let mut offered = Vec::new();
            let mut rest = &shares[2..];
            while let [high, low, len_high, len_low, tail @ ..] = rest {
                offered.push(u16::from_be_bytes([*high, *low]));
                rest = &tail[usize::from(u16::from_be_bytes([*len_high, *len_low]))..];
            }
            assert_eq!(offered, [0x11ec, 0x001d], "key_share: hybrid first, then its X25519 component");
            let schemes = extension_body(&hello, 0x000d);
            assert_eq!(
                u16_list(&schemes[2..]),
                [0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601],
                "signature_algorithms"
            );
            assert_eq!(extension_body(&hello, 0x0010), b"\x00\x0c\x02h2\x08http/1.1", "ALPN");
            // Outer ECH: type, KDF, AEAD, config id, then a 32-byte X25519 encapsulated key.
            let ech = extension_body(&hello, 0xfe0d);
            assert_eq!(ech[..5], [0x00, 0x00, 0x01, 0x00, 0x01], "outer ECH with HKDF-SHA256 and AES-128-GCM");
            assert_eq!(u16::from_be_bytes([ech[6], ech[7]]), 32, "ECH GREASE encapsulated key length");
        }
    }

    #[test]
    fn client_hello_lists_cipher_suites_in_chrome_order() {
        let hello = client_hello(tls_config_with_ech_setup(&EchSetup::Grease, None).expect("TLS config"));
        // record (5) + handshake header (4) + version (2) + random (32), then the session id.
        let session_id_len = usize::from(hello[43]);
        let suites_at = 44 + session_id_len;
        let suites_len = usize::from(u16::from_be_bytes([hello[suites_at], hello[suites_at + 1]]));

        assert_eq!(
            u16_list(&hello[suites_at + 2..suites_at + 2 + suites_len]),
            [0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8]
        );
    }
}
