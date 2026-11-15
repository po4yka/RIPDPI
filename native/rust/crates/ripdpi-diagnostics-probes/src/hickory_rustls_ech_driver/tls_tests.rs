use super::*;
use crate::probes::ech_handshake::EchHandshakeProbe;
use crate::{Probe, ProbeContext, ProbeVerdict};
use std::io::Read;
use std::net::{TcpListener, TcpStream};
use std::time::Instant;

const BORING_ECH_CONFIG_LIST: &[u8] = &[
    0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e, 0x04,
    0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07, 0x9b, 0x93,
    0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00, 0x07, 0x65, 0x63,
    0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
];
const BORING_ECH_CONFIG_2: &[u8] = &[
    0xfe, 0x0d, 0x00, 0x3a, 0x01, 0x00, 0x20, 0x00, 0x20, 0x7e, 0xfb, 0x5f, 0xee, 0xa2, 0xa2, 0x74, 0xb3, 0xff, 0xfe,
    0x69, 0x51, 0x9e, 0xfa, 0xe1, 0xfa, 0x49, 0x8c, 0x61, 0x2c, 0xfa, 0xd0, 0x0f, 0x8c, 0x22, 0x87, 0x0e, 0xbc, 0xb0,
    0x2d, 0x76, 0x25, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00, 0x07, 0x65, 0x63, 0x68, 0x2e,
    0x63, 0x6f, 0x6d, 0x00, 0x00,
];
const BORING_ECH_KEY_2: &[u8] = &[
    0xa7, 0x35, 0x44, 0x24, 0x6c, 0xfe, 0xb0, 0x53, 0x4c, 0x62, 0x7e, 0xca, 0x2e, 0x11, 0x8f, 0x56, 0x3c, 0xc0, 0x2e,
    0x6a, 0xa2, 0xe7, 0xaf, 0x3a, 0x7d, 0xb4, 0x72, 0x13, 0x1e, 0x88, 0xb6, 0x85,
];

fn fixture(retry_config: Option<&[u8]>) -> (rustls::pki_types::CertificateDer<'static>, boring::ssl::SslContext) {
    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "localhost".into()]).unwrap();
    let mut context = boring::ssl::SslContext::builder(boring::ssl::SslMethod::tls()).unwrap();
    context.set_certificate(&boring::x509::X509::from_der(certificate.cert.der().as_ref()).unwrap()).unwrap();
    context
        .set_private_key(&boring::pkey::PKey::private_key_from_der(&certificate.signing_key.serialize_der()).unwrap())
        .unwrap();
    if let Some(config) = retry_config {
        let mut keys = boring::ssl::SslEchKeys::builder().unwrap();
        keys.add_key(true, config, boring::hpke::HpkeKey::dhkem_p256_sha256(BORING_ECH_KEY_2).unwrap()).unwrap();
        context.set_ech_keys(&keys.build()).unwrap();
    }
    (certificate.cert.der().clone(), context.build())
}

fn roots(certificates: &[rustls::pki_types::CertificateDer<'static>]) -> RootCertStore {
    let mut roots = RootCertStore::empty();
    for certificate in certificates {
        roots.add(certificate.clone()).unwrap();
    }
    roots
}

fn test_client_config(configs: Vec<u8>, roots: &RootCertStore) -> Result<Arc<rustls::ClientConfig>, String> {
    let ech =
        EchConfig::new(EchConfigListBytes::from(configs), ALL_SUPPORTED_SUITES).map_err(|error| error.to_string())?;
    let config = rustls::ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
        .with_ech(EchMode::Enable(ech))
        .map_err(|error| error.to_string())?
        .with_root_certificates(roots.clone())
        .with_no_client_auth();
    Ok(Arc::new(config))
}

fn accept(listener: &TcpListener) -> TcpStream {
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        match listener.accept() {
            Ok((socket, _)) => {
                socket.set_nonblocking(false).unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
                socket.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
                return socket;
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(5));
            }
            error => panic!("loopback TLS accept failed: {error:?}"),
        }
    }
}

fn connect(
    configs: &[u8],
    roots: RootCertStore,
    contexts: Vec<boring::ssl::SslContext>,
) -> (EchHandshakeOutcome, Vec<bool>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        contexts
            .into_iter()
            .map(|context| {
                let tcp = accept(&listener);
                match boring::ssl::Ssl::new(&context).unwrap().accept(tcp) {
                    Ok(mut tls) => {
                        let accepted = tls.ssl().ech_accepted();
                        let mut byte = [0u8; 1];
                        assert!(
                            matches!(tls.read(&mut byte), Ok(0) | Err(_)),
                            "the probe must not send application data"
                        );
                        accepted
                    }
                    Err(error) => {
                        eprintln!("loopback server handshake: {error}");
                        false
                    }
                }
            })
            .collect()
    });
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    let outcome = runtime.block_on(handshake_with_config(
        "localhost",
        address.port(),
        configs,
        Duration::from_secs(3),
        |configs| test_client_config(configs, &roots),
    ));
    (outcome, server.join().unwrap())
}

#[test]
fn authenticated_rejection_without_retry_preserves_ech_class() {
    let (certificate, context) = fixture(None);
    let (outcome, accepted) = connect(BORING_ECH_CONFIG_LIST, roots(&[certificate]), vec![context]);
    assert_eq!(accepted, vec![false]);
    eprintln!("captured ECH outcome: {outcome:?}");
    assert_eq!(
        EchHandshakeProbe::new("localhost", outcome, None).run(&ProbeContext::empty()).verdict,
        ProbeVerdict::Fail { class: "ech-rejected".into() },
    );
}

#[test]
fn authenticated_rejection_with_absent_empty_or_unsupported_retry_is_preserved() {
    use rustls::internal::msgs::{
        codec::{Codec, Reader},
        handshake::EchConfigPayload,
    };
    let unsupported = Vec::<EchConfigPayload>::read(&mut Reader::init(&[0, 4, 0xfe, 0xff, 0, 0])).unwrap();
    for configs in [None, Some(Vec::new()), Some(unsupported)] {
        let error =
            rustls::Error::PeerIncompatible(rustls::PeerIncompatible::ServerRejectedEncryptedClientHello(configs));
        assert_eq!(
            classify_handshake_error(std::io::Error::new(std::io::ErrorKind::InvalidData, error)),
            EchHandshakeOutcome::EchRejectedWithoutUsableRetryConfig,
        );
    }
}

#[test]
fn fresh_ech_is_accepted_by_both_tls_stacks() {
    let (certificate, context) = fixture(Some(BORING_ECH_CONFIG_2));
    let mut configs = u16::try_from(BORING_ECH_CONFIG_2.len()).unwrap().to_be_bytes().to_vec();
    configs.extend_from_slice(BORING_ECH_CONFIG_2);
    let (outcome, accepted) = connect(&configs, roots(&[certificate]), vec![context]);
    assert_eq!(accepted, vec![true]);
    assert!(matches!(outcome, EchHandshakeOutcome::EchAccepted { .. }));
}

#[test]
fn stale_ech_retries_once_against_the_same_listener() {
    let (first_cert, first) = fixture(Some(BORING_ECH_CONFIG_2));
    let (second_cert, second) = fixture(Some(BORING_ECH_CONFIG_2));
    let (outcome, accepted) = connect(BORING_ECH_CONFIG_LIST, roots(&[first_cert, second_cert]), vec![first, second]);
    assert_eq!(accepted, vec![false, true]);
    assert!(matches!(outcome, EchHandshakeOutcome::EchAccepted { .. }));
}

#[test]
fn repeated_rejection_stops_after_the_single_retry() {
    let (first_cert, first) = fixture(Some(BORING_ECH_CONFIG_2));
    let mut next_config = BORING_ECH_CONFIG_2.to_vec();
    next_config[4] = 2; // A third config ID makes the second connection reject the first retry.
    let (second_cert, second) = fixture(Some(&next_config));
    let (outcome, accepted) = connect(BORING_ECH_CONFIG_LIST, roots(&[first_cert, second_cert]), vec![first, second]);
    assert_eq!(accepted, vec![false, false]);
    assert_eq!(outcome, EchHandshakeOutcome::EchRejectedWithRetryConfig);
}

#[test]
fn wrong_public_name_is_a_certificate_failure() {
    let (certificate, context) = fixture(Some(BORING_ECH_CONFIG_2));
    let mut configs = BORING_ECH_CONFIG_LIST.to_vec();
    let offset = configs.windows(7).position(|bytes| bytes == b"ech.com").unwrap();
    configs[offset..offset + 7].copy_from_slice(b"bad.com");
    let (outcome, accepted) = connect(&configs, roots(&[certificate]), vec![context]);
    assert_eq!(accepted, vec![false]);
    assert!(
        matches!(outcome, EchHandshakeOutcome::HandshakeFailure { detail } if detail.starts_with("tls-handshake-error:"))
    );
}

#[test]
fn untrusted_certificate_cannot_authorize_retry() {
    let (_, context) = fixture(Some(BORING_ECH_CONFIG_2));
    let (outcome, accepted) = connect(BORING_ECH_CONFIG_LIST, RootCertStore::empty(), vec![context]);
    assert_eq!(accepted, vec![false]);
    assert!(
        matches!(outcome, EchHandshakeOutcome::HandshakeFailure { detail } if detail.starts_with("tls-handshake-error:"))
    );
}
