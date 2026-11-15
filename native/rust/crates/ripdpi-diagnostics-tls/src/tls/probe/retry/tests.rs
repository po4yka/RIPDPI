use super::*;
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::Duration;

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

#[test]
fn monitor_retries_authenticated_ech_before_application_data() {
    check_monitor_ech_retry(RetryFixture::Accepted);
}

#[test]
fn failed_monitor_ech_retry_has_its_own_transport_open_time() {
    check_monitor_ech_retry(RetryFixture::HandshakeFailed);
}

#[test]
fn monitor_preserves_rejected_ech_when_retry_connect_fails() {
    check_monitor_ech_retry(RetryFixture::ConnectFailed);
}

#[derive(Clone, Copy)]
enum RetryFixture {
    Accepted,
    HandshakeFailed,
    ConnectFailed,
}

fn check_monitor_ech_retry(fixture: RetryFixture) {
    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "inner.example".into()]).unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(certificate.cert.der().clone()).unwrap();
    let verifier: Arc<dyn rustls::client::danger::ServerCertVerifier> =
        rustls::client::WebPkiServerVerifier::builder_with_provider(
            Arc::new(roots),
            Arc::new(rustls::crypto::aws_lc_rs::default_provider()),
        )
        .build()
        .unwrap();
    let mut context = boring::ssl::SslContext::builder(boring::ssl::SslMethod::tls()).unwrap();
    context.set_certificate(&boring::x509::X509::from_der(certificate.cert.der().as_ref()).unwrap()).unwrap();
    context
        .set_private_key(&boring::pkey::PKey::private_key_from_der(&certificate.signing_key.serialize_der()).unwrap())
        .unwrap();
    let mut keys = boring::ssl::SslEchKeys::builder().unwrap();
    keys.add_key(true, BORING_ECH_CONFIG_2, boring::hpke::HpkeKey::dhkem_p256_sha256(BORING_ECH_KEY_2).unwrap())
        .unwrap();
    context.set_ech_keys(&keys.build()).unwrap();
    let context = context.build();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let mut listener = Some(listener);
        let reconnect = !matches!(fixture, RetryFixture::ConnectFailed);
        for attempt in 0..if reconnect { 2 } else { 1 } {
            let (mut socket, _) = listener.as_ref().unwrap().accept().unwrap();
            if !reconnect {
                drop(listener.take());
            }
            socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            if attempt == 1 && matches!(fixture, RetryFixture::HandshakeFailed) {
                let mut header = [0u8; 5];
                socket.read_exact(&mut header).unwrap();
                assert_eq!(header[0], 22);
                let mut payload = vec![0u8; usize::from(u16::from_be_bytes([header[3], header[4]]))];
                socket.read_exact(&mut payload).unwrap();
                return;
            }
            let result = boring::ssl::Ssl::new(&context).unwrap().accept(socket);
            if attempt == 0 {
                if let Ok(mut tls) = result {
                    assert!(!tls.ssl().ech_accepted());
                    assert!(matches!(tls.read(&mut [0u8; 1]), Ok(0) | Err(_)));
                }
            } else {
                let mut tls = result.expect("fresh ECH accepted");
                assert!(tls.ssl().ech_accepted());
                let mut data = [0u8; 4];
                tls.read_exact(&mut data).unwrap();
                assert_eq!(&data, b"ping");
            }
        }
    });
    let socket = TcpStream::connect(address).unwrap();
    let targets = [TargetAddress::Ip(address.ip())];
    let transport = crate::transport::direct_transport();
    let options = CaptureTlsHandshakeOptions {
        targets: &targets,
        ech_retry_configs: Some(BORING_ECH_CONFIG_LIST),
        transport: &transport,
        tls_name: "inner.example",
        profile: crate::tls::types::TlsClientProfile::Tls13WithEch,
        tls_verifier: Some(&verifier),
        key_log: None,
        application_protocol: crate::tls::types::ApplicationProtocolPolicy::Http11Only,
        should_abort: &|| None,
    };
    let (result, evidence) = capture_with_ech_retry(socket, Some(address), 7, &options);
    let evidence = evidence.expect("a new attempt retains the first ECH rejection");
    assert_eq!(evidence.initial_attempt.connected_addr, address);
    assert_eq!(evidence.initial_attempt.transport_open_ms, 7);
    assert!(evidence.initial_attempt.ja3_fingerprint.is_some());
    if matches!(fixture, RetryFixture::ConnectFailed) {
        assert!(result.is_err());
        assert_eq!(evidence.transport_failure, Some(ProbeStreamFailureStage::TcpConnect));
        assert_eq!(evidence.connected_addr, None);
        server.join().unwrap();
        return;
    }
    assert_eq!(evidence.connected_addr, Some(address));
    assert_eq!(evidence.transport_failure, None);
    if matches!(fixture, RetryFixture::HandshakeFailed) {
        let Err(error) = result else { panic!("stopped TLS peer completed the retry") };
        assert!(error.handshake_ms.is_some());
        assert!(error.client_hello.is_some_and(|hello| hello.ja3_fingerprint.is_some()));
        server.join().unwrap();
        return;
    }
    let mut handshake = result.unwrap_or_else(|error| panic!("retry failed: {}", error.message));
    match &handshake.stream {
        crate::transport::ConnectionStream::Tls(stream) => {
            assert_eq!(stream.conn.ech_status(), rustls::client::EchStatus::Accepted);
        }
        crate::transport::ConnectionStream::Plain(_) => panic!("expected TLS"),
    }
    handshake.stream.write_all(b"ping").unwrap();
    server.join().unwrap();
}
