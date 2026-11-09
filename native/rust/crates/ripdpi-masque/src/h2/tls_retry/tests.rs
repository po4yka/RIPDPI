use super::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

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

fn acceptor(certificate: &[u8], private_key: &[u8], ech: bool) -> boring::ssl::SslAcceptor {
    let mut builder = boring::ssl::SslAcceptor::mozilla_intermediate_v5(boring::ssl::SslMethod::tls()).unwrap();
    builder.set_certificate(&boring::x509::X509::from_der(certificate).unwrap()).unwrap();
    builder.set_private_key(&boring::pkey::PKey::private_key_from_der(private_key).unwrap()).unwrap();
    builder.set_alpn_select_callback(|_, offered| {
        boring::ssl::select_next_proto(b"\x02h2", offered).ok_or(boring::ssl::AlpnError::NOACK)
    });
    if ech {
        let mut keys = boring::ssl::SslEchKeys::builder().unwrap();
        keys.add_key(true, BORING_ECH_CONFIG_2, boring::hpke::HpkeKey::dhkem_p256_sha256(BORING_ECH_KEY_2).unwrap())
            .unwrap();
        builder.set_ech_keys(&keys.build()).unwrap();
    }
    builder.build()
}

/// # Cancel safety:
/// NOT cancel-safe: fixture completion must be joined; runtime teardown closes peers.
#[tokio::test]
async fn owned_h2_carrier_retries_the_authenticated_ech_key_before_data() {
    run_fixture(false).await;
}

/// # Cancel safety:
/// NOT cancel-safe: fixture completion must be joined; runtime teardown closes peers.
#[tokio::test]
async fn a_second_ech_rejection_fails_without_a_third_carrier() {
    run_fixture(true).await;
}

/// # Cancel safety:
/// NOT cancel-safe: dropping this test transaction can detach its peer until runtime teardown.
async fn run_fixture(reject_retry: bool) {
    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "inner.example".into()]).unwrap();
    let pem = certificate.cert.pem();
    let private = certificate.signing_key.serialize_der();
    let accepted = acceptor(certificate.cert.der().as_ref(), &private, true);
    let rejected = acceptor(certificate.cert.der().as_ref(), &private, false);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let peer = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        for attempt in 0..2 {
            let (tcp, _) = listener.accept().await.unwrap();
            let ctx = if attempt == 1 && reject_retry { &rejected } else { &accepted };
            let handshake = tokio_boring::accept(ctx, tcp).await;
            if attempt == 0 || reject_retry {
                if let Ok(mut tls) = handshake {
                    assert!(!tls.ssl().ech_accepted());
                    let mut byte = [0u8; 1];
                    assert!(matches!(tls.read(&mut byte).await, Ok(0) | Err(_)));
                }
            } else {
                let mut tls = handshake.expect("fresh ECH accepted");
                assert!(tls.ssl().ech_accepted());
                let mut data = [0u8; 4];
                tls.read_exact(&mut data).await.unwrap();
                assert_eq!(&data, b"ping");
            }
        }
        assert!(tokio::time::timeout(std::time::Duration::from_millis(100), listener.accept()).await.is_err());
    });
    let mut config = crate::tests::grease_test_config(crate::config::MasqueTcpProtocol::Http2);
    config.url = "https://inner.example/".into();
    config.proxy_socket_addr = Some(peer);
    config.root_certificate_pem = Some(pem);
    config.ech_config =
        Some(ripdpi_tls_profiles::OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST.to_vec()).unwrap());
    let origin = crate::url::parse_proxy_origin(&config).unwrap();
    let tcp = tokio::net::TcpStream::connect(peer).await.unwrap();
    let result = tokio::time::timeout(std::time::Duration::from_secs(5), connect(&config, &origin, tcp, Some(peer)))
        .await
        .unwrap();
    if reject_retry {
        assert!(result.is_err(), "a rejected retry must not become an accepted connection");
    } else {
        result.expect("retry succeeds").write_all(b"ping").await.unwrap();
    }
    server.await.unwrap();
}
