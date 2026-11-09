use std::io;
use std::time::Duration;

use super::native_ech_retry;

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

/// # Cancel safety:
/// NOT cancel-safe: the fixture transaction must be joined. Test runtime
/// teardown closes any pending peer if the test future is dropped.
#[tokio::test]
async fn native_ech_retries_a_trusted_stale_key_once() {
    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "127.0.0.1".into()]).unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(certificate.cert.der().clone()).unwrap();
    let mut acceptor = boring::ssl::SslAcceptor::mozilla_intermediate_v5(boring::ssl::SslMethod::tls()).unwrap();
    acceptor.set_certificate(&boring::x509::X509::from_der(certificate.cert.der().as_ref()).unwrap()).unwrap();
    acceptor
        .set_private_key(&boring::pkey::PKey::private_key_from_der(&certificate.signing_key.serialize_der()).unwrap())
        .unwrap();
    let mut keys = boring::ssl::SslEchKeys::builder().unwrap();
    keys.add_key(true, BORING_ECH_CONFIG_2, boring::hpke::HpkeKey::dhkem_p256_sha256(BORING_ECH_KEY_2).unwrap())
        .unwrap();
    acceptor.set_ech_keys(&keys.build()).unwrap();
    let acceptor = acceptor.build();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        for attempt in 0..2 {
            let (tcp, _) = listener.accept().await.unwrap();
            let handshake = tokio_boring::accept(&acceptor, tcp).await;
            if attempt == 0 {
                if let Ok(mut tls) = handshake {
                    assert!(!tls.ssl().ech_accepted());
                    use tokio::io::AsyncReadExt;
                    let mut byte = [0u8; 1];
                    assert!(matches!(tls.read(&mut byte).await, Ok(0) | Err(_)));
                }
            } else {
                assert!(handshake.expect("fresh ECH accepted").ssl().ech_accepted());
            }
        }
    });
    let operation = native_ech_retry::connect(
        "127.0.0.1",
        address.port(),
        BORING_ECH_CONFIG_LIST.to_vec(),
        Duration::from_secs(2),
        move |configs| {
            let ech = rustls::client::EchConfig::new(
                rustls::pki_types::EchConfigListBytes::from(configs),
                rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
            )
            .map_err(io::Error::other)?;
            let config =
                rustls::ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
                    .with_ech(rustls::client::EchMode::Enable(ech))
                    .map_err(io::Error::other)?
                    .with_root_certificates(roots.clone())
                    .with_no_client_auth();
            Ok(config)
        },
    );
    tokio::time::timeout(Duration::from_secs(5), operation).await.unwrap().expect("fresh key accepted");
    server.await.unwrap();
}
