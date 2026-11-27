use super::*;
use crate::tls::config::EchLookupFailure;
use crate::tls::types::ApplicationProtocolPolicy;
use crate::transport::ConnectionStream;
use ripdpi_diagnostics_dns::dns::EchResolutionOutcome;
use rustls::client::EchStatus;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
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

#[test]
fn monitor_does_not_retry_ech_with_custom_certificate_verification() {
    check_monitor_ech_retry(RetryFixture::CustomVerifier);
}

#[test]
fn comparison_connects_plain_grease_and_real_ech_on_the_same_trusted_peer() {
    const REQUEST: &[u8] = b"GET /probe HTTP/1.1\r\nHost: inner.example\r\nConnection: close\r\n\r\n";
    const RESPONSE: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\npass";

    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "inner.example".into()]).unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(certificate.cert.der().clone()).unwrap();
    let mut context = ech_server_context(&certificate);
    context.set_min_proto_version(Some(boring::ssl::SslVersion::TLS1_3)).unwrap();
    context.set_max_proto_version(Some(boring::ssl::SslVersion::TLS1_3)).unwrap();
    context.set_alpn_select_callback(|_, offered| {
        boring::ssl::select_next_proto(b"\x08http/1.1", offered).ok_or(boring::ssl::AlpnError::ALERT_FATAL)
    });
    let context = context.build();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        for accepted_ech in [false, false, true] {
            let (socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            socket.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
            let mut tls = boring::ssl::Ssl::new(&context).unwrap().accept(socket).unwrap();
            assert_eq!(tls.ssl().ech_accepted(), accepted_ech);
            assert_eq!(tls.ssl().selected_alpn_protocol(), Some(b"http/1.1".as_slice()));
            let mut request = [0; REQUEST.len()];
            tls.read_exact(&mut request).unwrap();
            assert_eq!(&request[..], REQUEST);
            tls.write_all(RESPONSE).unwrap();
        }
    });
    let targets = [TargetAddress::Ip(address.ip())];
    let transport = crate::transport::direct_transport();
    let published = EchResolutionOutcome::Available([&[0, 62][..], BORING_ECH_CONFIG_2].concat());
    for (profile, expected_status) in [
        (TlsClientProfile::ComparisonTls13, EchStatus::NotOffered),
        (TlsClientProfile::ComparisonEchGrease, EchStatus::Grease),
        (TlsClientProfile::ComparisonEch, EchStatus::Accepted),
    ] {
        let options = CaptureTlsHandshakeOptions {
            targets: &targets,
            port: address.port(),
            ech_retry_configs: None,
            transport: &transport,
            tls_name: "inner.example",
            profile,
            tls_verifier: None,
            fixture_ech_roots: Some(&roots),
            fixture_ech_lookup: Some(&published),
            key_log: None,
            application_protocol: ApplicationProtocolPolicy::Http11Only,
            should_abort: &|| None,
        };
        let socket = TcpStream::connect(address).unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        socket.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
        let (result, retry) = capture_with_ech_retry(socket.into(), Some(address), 0, &options);
        let mut handshake = result.unwrap_or_else(|error| panic!("{profile:?}: {}", error.message));
        let ConnectionStream::Tls(stream) = &handshake.stream else { panic!("expected TLS") };
        assert_eq!(stream.conn.ech_status(), expected_status);
        assert_eq!(stream.conn.protocol_version(), Some(rustls::ProtocolVersion::TLSv1_3));
        assert_eq!(handshake.negotiated_alpn.as_deref(), Some("http/1.1"));
        assert!(retry.is_none());
        handshake.stream.write_all(REQUEST).unwrap();
        let mut response = [0; RESPONSE.len()];
        handshake.stream.read_exact(&mut response).unwrap();
        assert_eq!(&response[..], RESPONSE);
    }
    server.join().unwrap();
}

#[test]
fn comparison_ech_distinguishes_setup_failures_even_for_a_cdn_peer() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let cdn_peer = "104.16.0.1:443".parse::<SocketAddr>().unwrap();
    let transport = crate::transport::direct_transport();
    let targets = [TargetAddress::Ip(cdn_peer.ip())];
    let mut unusable_key = BORING_ECH_CONFIG_LIST.to_vec();
    unusable_key[11..43].fill(0);

    for (lookup, expected) in [
        (EchResolutionOutcome::NotPublished, "missing"),
        (EchResolutionOutcome::ResolutionFailed("SERVFAIL".into()), "dns"),
        (EchResolutionOutcome::InvalidConfig("empty ECHConfigList".into()), "config"),
        (EchResolutionOutcome::Available(unusable_key), "config"),
    ] {
        let socket = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let options = CaptureTlsHandshakeOptions {
            targets: &targets,
            port: cdn_peer.port(),
            ech_retry_configs: None,
            transport: &transport,
            tls_name: "inner.example",
            profile: TlsClientProfile::ComparisonEch,
            tls_verifier: None,
            fixture_ech_roots: None,
            fixture_ech_lookup: Some(&lookup),
            key_log: None,
            application_protocol: ApplicationProtocolPolicy::Http11Only,
            should_abort: &|| None,
        };
        let (result, retry) = capture_with_ech_retry(socket.into(), Some(cdn_peer), 0, &options);

        let Err(error) = result else { panic!("an unusable ECH setup allowed a handshake") };
        let reason = match error.ech_lookup_failure.as_deref() {
            Some(EchLookupFailure::NotPublished) => "missing",
            Some(EchLookupFailure::ResolutionFailed(_)) => "dns",
            Some(EchLookupFailure::InvalidConfig(_)) => "config",
            None => "unknown",
        };
        assert_eq!(reason, expected);
        assert!(error.client_hello.is_none());
        assert!(retry.is_none());
    }
}

#[test]
fn comparison_never_retries_rejected_ech_with_authenticated_retry_configs() {
    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "inner.example".into()]).unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(certificate.cert.der().clone()).unwrap();
    let context = ech_server_context(&certificate).build();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let (socket, _) = listener.accept().unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        if let Ok(mut tls) = boring::ssl::Ssl::new(&context).unwrap().accept(socket) {
            assert!(!tls.ssl().ech_accepted());
            assert!(matches!(tls.read(&mut [0]), Ok(0) | Err(_)));
        }
    });
    let transport = crate::transport::direct_transport();
    let targets = [TargetAddress::Ip(address.ip())];
    let published = EchResolutionOutcome::Available(BORING_ECH_CONFIG_LIST.to_vec());
    let options = CaptureTlsHandshakeOptions {
        targets: &targets,
        port: address.port(),
        ech_retry_configs: None,
        transport: &transport,
        tls_name: "inner.example",
        profile: TlsClientProfile::ComparisonEch,
        tls_verifier: None,
        fixture_ech_roots: Some(&roots),
        fixture_ech_lookup: Some(&published),
        key_log: None,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        should_abort: &|| None,
    };

    let socket = TcpStream::connect(address).unwrap();
    let (result, retry) = capture_with_ech_retry(socket.into(), Some(address), 0, &options);

    let Err(error) = result else { panic!("rejected ECH yielded a stream") };
    assert_eq!(error.ech_status, Some(EchStatus::Rejected));
    assert!(error.ech_rejected);
    assert!(error.ech_retry_configs.is_some());
    assert!(retry.is_none());
    server.join().unwrap();
}

#[derive(Clone, Copy)]
enum RetryFixture {
    Accepted,
    HandshakeFailed,
    ConnectFailed,
    CustomVerifier,
}

fn ech_server_context(certificate: &rcgen::CertifiedKey<rcgen::KeyPair>) -> boring::ssl::SslContextBuilder {
    let mut context = boring::ssl::SslContext::builder(boring::ssl::SslMethod::tls()).unwrap();
    context.set_certificate(&boring::x509::X509::from_der(certificate.cert.der().as_ref()).unwrap()).unwrap();
    context
        .set_private_key(&boring::pkey::PKey::private_key_from_der(&certificate.signing_key.serialize_der()).unwrap())
        .unwrap();
    let mut keys = boring::ssl::SslEchKeys::builder().unwrap();
    keys.add_key(true, BORING_ECH_CONFIG_2, boring::hpke::HpkeKey::dhkem_p256_sha256(BORING_ECH_KEY_2).unwrap())
        .unwrap();
    context.set_ech_keys(&keys.build()).unwrap();
    context
}

fn check_monitor_ech_retry(fixture: RetryFixture) {
    let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".into(), "inner.example".into()]).unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(certificate.cert.der().clone()).unwrap();
    let verifier: Arc<dyn rustls::client::danger::ServerCertVerifier> = Arc::new(crate::tls::NoCertificateVerification);
    let context = ech_server_context(&certificate).build();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let mut listener = Some(listener);
        let reconnect = matches!(fixture, RetryFixture::Accepted | RetryFixture::HandshakeFailed);
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
        port: address.port(),
        ech_retry_configs: Some(BORING_ECH_CONFIG_LIST),
        transport: &transport,
        tls_name: "inner.example",
        profile: crate::tls::types::TlsClientProfile::Tls13WithEch,
        tls_verifier: matches!(fixture, RetryFixture::CustomVerifier).then_some(&verifier),
        fixture_ech_roots: Some(&roots),
        fixture_ech_lookup: None,
        key_log: None,
        application_protocol: crate::tls::types::ApplicationProtocolPolicy::Http11Only,
        should_abort: &|| None,
    };
    let (result, evidence) = capture_with_ech_retry(socket.into(), Some(address), 7, &options);
    if matches!(fixture, RetryFixture::CustomVerifier) {
        let Err(error) = result else { panic!("unchecked ECH rejection authorized a retry") };
        assert_eq!(error.ech_status, Some(rustls::client::EchStatus::Rejected));
        assert_eq!(error.ech_retry_skipped_reason, Some("certificate_policy_override"));
        assert!(error.client_hello.is_some_and(|hello| hello.ja3_fingerprint.is_some()));
        assert!(evidence.is_none());
        server.join().unwrap();
        return;
    }
    let evidence = evidence.expect("a new attempt retains the first ECH rejection");
    assert_eq!(evidence.initial_attempt.connected_addr, address);
    assert_eq!(evidence.initial_attempt.transport_open_ms, 7);
    assert!(evidence.initial_attempt.ja3_fingerprint.is_some());
    assert_eq!(evidence.initial_attempt.certificate_verification, CertificateVerification::VerifiedWebPki);
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
