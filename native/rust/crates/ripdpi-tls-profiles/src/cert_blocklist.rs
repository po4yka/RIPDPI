//! SPKI blocklist for compromised public CA intermediates (rustls clients).
//!
//! Mirrors the entries Chromium and NaiveProxy block by SHA-256 of the
//! certificate's `SubjectPublicKeyInfo` DER. The `webpki-roots` snapshot still
//! trusts the eMudhra root, so chains through these intermediates would
//! otherwise verify.
//!
//! Source: klzgrad/naiveproxy commit 23953c90e3b9f08f8d0c6ede92ae35f357af6f73,
//! `src/net/cert/cert_verify_proc_blocklist.inc`.

use std::sync::Arc;

use rustls::client::WantsClientCert;
use rustls::client::WebPkiServerVerifier;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{
    CertificateError, ClientConfig, ConfigBuilder, DigitallySignedStruct, RootCertStore, SignatureScheme, WantsVerifier,
};
use sha2::{Digest, Sha256};

type SpkiHash = [u8; 32];

/// SHA-256 SPKI hashes of the compromised eMudhra intermediates.
const BLOCKED_SPKI_SHA256: [SpkiHash; 8] = [
    // 0164d7ceae5bb909c272af151ff44b1177ae9b47d9424b94f1d9558c30934d19.pem
    hex32(b"49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f"),
    // 42c4accd12c4f59597925e1a08a7a90102608543809308d50ec427e118ba1493.pem
    hex32(b"ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105"),
    // 432ec25f0d28de9006d97bf752f17bbb2cd41f669933fcb072d9a1148d0261f3.pem
    hex32(b"5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0"),
    // 98b896625c0c230adeec3eb2cf6aad5001ecf9156a68644e82374ce5833bc25b.pem
    hex32(b"43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b"),
    // cb6e15165ac802e9b3f993f16c25d41f4e81c21ebde71ae1b012ac00bf9c7c3f.pem
    hex32(b"63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8"),
    // e7568fe6e12331f4fd7de61da4c7559d0f8eb192a218d4741a3ba75184333838.pem
    hex32(b"e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41"),
    // 4b2460a466f95fd40113bcdcca2ceb29b3b093921de45474da7df48874c630b1.pem
    hex32(b"92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30"),
    // 9e39de2881273acc225a149c72ba3e0f51424963669ab633ee1dc7df5db067b4.pem
    hex32(b"ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6"),
];

const fn nibble(digit: u8) -> u8 {
    match digit {
        b'0'..=b'9' => digit - b'0',
        b'a'..=b'f' => digit - b'a' + 10,
        // Infallible at runtime: only const-evaluated, so a bad digit fails the build.
        _ => panic!("blocklist hash must be lowercase hex"),
    }
}

const fn hex32(hex: &[u8; 64]) -> SpkiHash {
    let mut out = [0_u8; 32];
    let mut i = 0;
    while i < 32 {
        out[i] = (nibble(hex[2 * i]) << 4) | nibble(hex[2 * i + 1]);
        i += 1;
    }
    out
}

/// Read one DER TLV at the start of `input`; returns (tag, content, rest).
fn der_tlv(input: &[u8]) -> Option<(u8, &[u8], &[u8])> {
    let (&tag, rest) = input.split_first()?;
    let (&first, rest) = rest.split_first()?;
    let (len, rest) = if first < 0x80 {
        (usize::from(first), rest)
    } else {
        let count = usize::from(first & 0x7f);
        if count == 0 || count > 4 || rest.len() < count {
            return None;
        }
        let len = rest[..count].iter().fold(0_usize, |acc, byte| (acc << 8) | usize::from(*byte));
        (len, &rest[count..])
    };
    if rest.len() < len {
        return None;
    }
    Some((tag, &rest[..len], &rest[len..]))
}

/// Full DER encoding (tag, length, content) of the certificate's SPKI.
fn spki_der(cert: &[u8]) -> Option<&[u8]> {
    let (_, cert_body, _) = der_tlv(cert)?;
    let (_, tbs, _) = der_tlv(cert_body)?;
    let mut rest = tbs;
    // Optional [0] version, then serial, signature, issuer, validity, subject.
    if rest.first() == Some(&0xa0) {
        rest = der_tlv(rest)?.2;
    }
    for _ in 0..5 {
        rest = der_tlv(rest)?.2;
    }
    let (tag, _, after) = der_tlv(rest)?;
    if tag != 0x30 {
        return None;
    }
    Some(&rest[..rest.len() - after.len()])
}

fn spki_sha256(cert: &CertificateDer<'_>) -> Option<SpkiHash> {
    spki_der(cert.as_ref()).map(|spki| Sha256::digest(spki).into())
}

/// Whether a DER certificate's SPKI hash is in `blocked`.
fn der_cert_blocked_by(der: &[u8], blocked: &[SpkiHash]) -> bool {
    spki_der(der).is_some_and(|spki| blocked.contains(&Sha256::digest(spki).into()))
}

/// Installs the blocklist as the BoringSSL verify callback (mode PEER). It
/// runs for every certificate of the chain BoringSSL built, after BoringSSL's
/// own check. A later `set_verify` on the builder replaces the callback and
/// removes the check, so callers must not call it.
pub fn install_cert_blocklist(builder: &mut boring::ssl::SslContextBuilder) {
    install_cert_blocklist_with(builder, BLOCKED_SPKI_SHA256.to_vec());
}

fn install_cert_blocklist_with(builder: &mut boring::ssl::SslContextBuilder, blocked: Vec<SpkiHash>) {
    builder.set_verify_callback(boring::ssl::SslVerifyMode::PEER, move |preverified, context| {
        if !preverified {
            return false;
        }
        // Fails closed: a certificate that cannot be re-encoded counts as blocked.
        let rejected = context
            .current_cert()
            .and_then(|cert| cert.to_der().ok())
            .is_none_or(|der| der_cert_blocked_by(&der, &blocked));
        if rejected {
            context.set_error(Err(boring::x509::X509VerifyError::CERT_REVOKED));
        }
        !rejected
    });
}

/// Wraps a verifier and additionally rejects chains that present a
/// blocklisted certificate (end entity or any intermediate).
#[derive(Debug)]
pub struct BlocklistingVerifier {
    inner: Arc<dyn ServerCertVerifier>,
    blocked: Vec<SpkiHash>,
}

impl BlocklistingVerifier {
    fn is_blocked(&self, cert: &CertificateDer<'_>) -> bool {
        spki_sha256(cert).is_some_and(|hash| self.blocked.contains(&hash))
    }
}

impl ServerCertVerifier for BlocklistingVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp_response: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let verified = self.inner.verify_server_cert(end_entity, intermediates, server_name, ocsp_response, now)?;
        if std::iter::once(end_entity).chain(intermediates).any(|cert| self.is_blocked(cert)) {
            return Err(rustls::Error::InvalidCertificate(CertificateError::Revoked));
        }
        Ok(verified)
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls12_signature(message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls13_signature(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.inner.supported_verify_schemes()
    }

    fn requires_raw_public_keys(&self) -> bool {
        self.inner.requires_raw_public_keys()
    }

    fn root_hint_subjects(&self) -> Option<&[rustls::DistinguishedName]> {
        self.inner.root_hint_subjects()
    }
}

/// Wrap `inner` so it also rejects the compromised eMudhra intermediates.
/// Wrapping never loosens `inner`: it runs first and its error wins.
#[must_use]
pub fn with_cert_blocklist(inner: Arc<dyn ServerCertVerifier>) -> Arc<dyn ServerCertVerifier> {
    Arc::new(BlocklistingVerifier { inner, blocked: BLOCKED_SPKI_SHA256.to_vec() })
}

/// Standard WebPKI verifier for `roots` with the cert blocklist applied.
///
/// # Errors
/// Returns the rustls builder error, for example when `roots` is empty.
pub fn blocklisting_webpki_verifier(
    roots: Arc<RootCertStore>,
    provider: Arc<CryptoProvider>,
) -> Result<Arc<dyn ServerCertVerifier>, rustls::client::VerifierBuilderError> {
    let inner: Arc<dyn ServerCertVerifier> = WebPkiServerVerifier::builder_with_provider(roots, provider).build()?;
    Ok(with_cert_blocklist(inner))
}

/// Drop-in for `ConfigBuilder::with_root_certificates` that also applies the
/// cert blocklist, using the builder's own crypto provider.
///
/// # Errors
/// Returns the rustls verifier builder error, for example when `roots` is empty.
/// Unlike `with_root_certificates`, an empty `roots` fails here, not at the handshake.
pub fn with_blocklisted_roots(
    builder: ConfigBuilder<ClientConfig, WantsVerifier>,
    roots: RootCertStore,
) -> Result<ConfigBuilder<ClientConfig, WantsClientCert>, rustls::client::VerifierBuilderError> {
    let verifier = blocklisting_webpki_verifier(Arc::new(roots), builder.crypto_provider().clone())?;
    Ok(builder.dangerous().with_custom_certificate_verifier(verifier))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::{BasicConstraints, CertificateParams, IsCa, Issuer, KeyPair, PublicKeyData};

    fn provider() -> Arc<CryptoProvider> {
        Arc::new(rustls::crypto::ring::default_provider())
    }

    fn ca_params(name: &str) -> CertificateParams {
        let mut params = CertificateParams::new(Vec::new()).expect("params");
        params.distinguished_name.push(rcgen::DnType::CommonName, name);
        params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
        params
    }

    struct Chain {
        root: CertificateDer<'static>,
        intermediate: CertificateDer<'static>,
        leaf: CertificateDer<'static>,
        intermediate_spki: SpkiHash,
    }

    fn chain() -> Chain {
        let root_key = KeyPair::generate().expect("root key");
        let root = ca_params("test root").self_signed(&root_key).expect("root");
        let root_issuer = Issuer::new(ca_params("test root"), root_key);
        let int_key = KeyPair::generate().expect("int key");
        let intermediate = ca_params("test intermediate").signed_by(&int_key, &root_issuer).expect("intermediate");
        let int_spki: SpkiHash = Sha256::digest(PublicKeyData::subject_public_key_info(&int_key)).into();
        let int_issuer = Issuer::new(ca_params("test intermediate"), int_key);
        let leaf_key = KeyPair::generate().expect("leaf key");
        let leaf_params = CertificateParams::new(vec!["example.test".to_string()]).expect("leaf params");
        let leaf = leaf_params.signed_by(&leaf_key, &int_issuer).expect("leaf");
        Chain {
            root: root.der().clone(),
            intermediate: intermediate.der().clone(),
            leaf: leaf.der().clone(),
            intermediate_spki: int_spki,
        }
    }

    #[test]
    fn der_walker_rejects_malformed_input_without_panicking() {
        let chain = chain();
        let leaf = chain.leaf.as_ref();
        assert!(spki_der(leaf).is_some());
        for bad in
            [&[][..], &[0x30], &[0x30, 0x80], &[0x30, 0x85, 1, 1, 1, 1, 1], &[0x30, 0x82, 0xff], &[0x30, 0x05, 1]]
        {
            assert!(der_tlv(bad).is_none(), "{bad:?}");
            assert!(spki_der(bad).is_none(), "{bad:?}");
        }
        for cut in 0..leaf.len() {
            let _ = spki_der(&leaf[..cut]);
        }
    }

    /// A real BoringSSL handshake: the chain verifies with an empty list and
    /// fails once the intermediate's SPKI is listed.
    #[test]
    fn boringssl_handshake_rejects_a_blocklisted_intermediate() {
        use boring::pkey::PKey;
        use boring::ssl::{SslAcceptor, SslConnector, SslMethod};
        use boring::x509::X509;

        let root_key = KeyPair::generate().expect("root key");
        let root = ca_params("test root").self_signed(&root_key).expect("root");
        let root_issuer = Issuer::new(ca_params("test root"), root_key);
        let int_key = KeyPair::generate().expect("int key");
        let intermediate = ca_params("test intermediate").signed_by(&int_key, &root_issuer).expect("intermediate");
        let int_spki: SpkiHash = Sha256::digest(PublicKeyData::subject_public_key_info(&int_key)).into();
        let int_issuer = Issuer::new(ca_params("test intermediate"), int_key);
        let leaf_key = KeyPair::generate().expect("leaf key");
        let leaf = CertificateParams::new(vec!["example.test".to_string()])
            .expect("leaf params")
            .signed_by(&leaf_key, &int_issuer)
            .expect("leaf");

        let handshake = |blocked: Vec<SpkiHash>| {
            let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).expect("acceptor");
            acceptor.set_certificate(&X509::from_der(leaf.der()).expect("leaf x509")).expect("leaf cert");
            acceptor.add_extra_chain_cert(X509::from_der(intermediate.der()).expect("int x509")).expect("chain");
            acceptor
                .set_private_key(&PKey::private_key_from_der(&leaf_key.serialize_der()).expect("leaf key der"))
                .expect("private key");
            let acceptor = acceptor.build();
            let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
            let address = listener.local_addr().expect("address");
            let server = std::thread::spawn(move || {
                let (stream, _) = listener.accept().expect("accept");
                let _ = acceptor.accept(stream);
            });
            let mut connector = SslConnector::builder(SslMethod::tls()).expect("connector");
            connector.cert_store_mut().add_cert(X509::from_der(root.der()).expect("root x509")).expect("root");
            install_cert_blocklist_with(&mut connector, blocked);
            let stream = std::net::TcpStream::connect(address).expect("connect");
            let result = connector.build().connect("example.test", stream).map(drop).map_err(|error| error.to_string());
            server.join().expect("server thread");
            result
        };

        assert!(handshake(Vec::new()).is_ok(), "the chain must verify without a blocklist entry");
        assert!(handshake(vec![int_spki]).is_err(), "a blocklisted intermediate must fail the handshake");
    }

    fn verify(chain: &Chain, blocked: Vec<SpkiHash>) -> Result<ServerCertVerified, rustls::Error> {
        let mut roots = RootCertStore::empty();
        roots.add(chain.root.clone()).expect("root added");
        let inner: Arc<dyn ServerCertVerifier> =
            WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider()).build().expect("verifier");
        let verifier = BlocklistingVerifier { inner, blocked };
        let name = ServerName::try_from("example.test").expect("name");
        verifier.verify_server_cert(&chain.leaf, std::slice::from_ref(&chain.intermediate), &name, &[], UnixTime::now())
    }

    #[test]
    fn blocklisted_intermediate_spki_is_rejected_but_chain_otherwise_verifies() {
        let chain = chain();
        assert!(verify(&chain, BLOCKED_SPKI_SHA256.to_vec()).is_ok());
        let result = verify(&chain, vec![chain.intermediate_spki]);
        assert!(matches!(result, Err(rustls::Error::InvalidCertificate(CertificateError::Revoked))), "{result:?}");
    }

    #[test]
    fn production_hashes_match_upstream() {
        let expected = [
            "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f",
            "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105",
            "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0",
            "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b",
            "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8",
            "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41",
            "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30",
            "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6",
        ];
        for (hash, hex) in BLOCKED_SPKI_SHA256.iter().zip(expected) {
            let rendered: String = hash.iter().map(|byte| format!("{byte:02x}")).collect();
            assert_eq!(rendered, hex);
        }
    }
}
