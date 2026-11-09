use std::io;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use tokio::sync::oneshot;

use crate::MasqueClient;
use crate::config::{MasqueConfig, MasqueTcpProtocol};

fn raw_proxy(quic_datagrams: bool) -> (quinn::Endpoint, MasqueConfig) {
    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("certificate");
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![certificate.cert.der().clone()],
            rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into(),
        )
        .expect("TLS certificate");
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC TLS");
    let mut server = quinn::ServerConfig::with_crypto(Arc::new(crypto));
    let mut transport = quinn::TransportConfig::default();
    if !quic_datagrams {
        transport.datagram_receive_buffer_size(None);
    }
    server.transport_config(Arc::new(transport));
    let endpoint = quinn::Endpoint::server(server, "127.0.0.1:0".parse().expect("address")).expect("endpoint");
    let address = endpoint.local_addr().expect("address");
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: format!("https://localhost:{}/", address.port()),
        proxy_socket_addr: Some(address),
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: false,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(certificate.cert.pem()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };
    (endpoint, config)
}

/// # Cancel safety:
/// NOT cancel-safe: the test must await its peer; both endpoints are local and bounded by the test.
async fn rejected_peer(settings: &'static [u8], quic_datagrams: bool) -> (io::Error, quinn::ConnectionError) {
    let (endpoint, config) = raw_proxy(quic_datagrams);
    let peer = tokio::spawn(async move {
        let connection = endpoint.accept().await.expect("incoming").await.expect("handshake");
        let mut control = connection.open_uni().await.expect("control stream");
        control.write_all(settings).await.expect("literal control bytes");
        let closed = tokio::time::timeout(Duration::from_secs(3), async {
            tokio::select! {
                request = connection.accept_bi() => match request {
                    Ok(_) => panic!("CONNECT sent without permission"),
                    Err(error) => error,
                },
                error = connection.closed() => error,
            }
        })
        .await
        .expect("client closes rejected connection");
        drop(control);
        closed
    });
    let client = MasqueClient::new(config).expect("client");
    let mut relay = client.udp_session();
    let error = tokio::time::timeout(Duration::from_secs(3), relay.send_to("127.0.0.1:9", b"audit"))
        .await
        .expect("bounded open")
        .expect_err("unsupported peer must not open a flow");
    (error, peer.await.expect("peer task"))
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins its bounded local peer and owns the exchange.
#[tokio::test]
async fn h3_settings_require_extended_connect_permission() {
    for settings in [&[0, 4, 2, 0x33, 1][..], &[0, 4, 4, 8, 0, 0x33, 1][..]] {
        let (error, _) = rejected_peer(settings, true).await;
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        assert!(error.to_string().contains("masque_h3_extended_connect_unsupported"));
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins its bounded local peer and owns the exchange.
#[tokio::test]
async fn h3_settings_delay_the_request_until_permission_arrives() {
    let (endpoint, config) = raw_proxy(true);
    let (done_tx, done_rx) = oneshot::channel();
    let peer = tokio::spawn(async move {
        let connection = endpoint.accept().await.expect("incoming").await.expect("handshake");
        assert!(tokio::time::timeout(Duration::from_millis(100), connection.accept_bi()).await.is_err());
        let mut control = connection.open_uni().await.expect("control stream");
        // Stream type 0; SETTINGS length 4; ENABLE_CONNECT_PROTOCOL=1, H3_DATAGRAM=1.
        control.write_all(&[0, 4, 4, 8, 1, 0x33, 1]).await.expect("SETTINGS");
        let (mut send, mut recv) = connection.accept_bi().await.expect("CONNECT request");
        let mut request = [0; 512];
        assert!(recv.read(&mut request).await.expect("request bytes").is_some());
        // RFC 9204: zero QPACK prefixes and static :status 200 (index 25).
        send.write_all(&[1, 3, 0, 0, 0xd9]).await.expect("literal response");
        let datagram = connection.read_datagram().await.expect("HTTP Datagram");
        assert_eq!(datagram.as_ref(), b"\x00\x00audit");
        connection.send_datagram(Bytes::from_static(b"\x00\x00echo")).expect("echo");
        done_rx.await.expect("client finished");
        (endpoint, connection, control, send, recv)
    });
    let client = MasqueClient::new(config).expect("client");
    let mut relay = client.udp_session();
    tokio::time::timeout(Duration::from_secs(3), relay.send_to("127.0.0.1:9", b"audit"))
        .await
        .expect("bounded open")
        .expect("permitted flow");
    let (_, payload) = tokio::time::timeout(Duration::from_secs(3), relay.recv_from())
        .await
        .expect("bounded receive")
        .expect("echo payload");
    assert_eq!(payload, b"echo");
    done_tx.send(()).expect("peer running");
    let _held_peer = peer.await.expect("peer task");
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins its bounded local peer and owns the exchange.
#[tokio::test]
async fn h3_settings_goaway_prevents_connect() {
    let (error, _) = rejected_peer(&[0, 4, 4, 8, 1, 0x33, 1, 7, 1, 0], true).await;
    assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins its bounded local peer and owns the exchange.
#[tokio::test]
async fn h3_settings_require_http_and_quic_datagram_support() {
    for settings in [&[0, 4, 2, 8, 1][..], &[0, 4, 4, 8, 1, 0x33, 0][..]] {
        let (error, _) = rejected_peer(settings, true).await;
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        assert!(error.to_string().contains("masque_h3_datagram_unsupported"));
    }
    let (error, _) = rejected_peer(&[0, 4, 4, 8, 1, 0x33, 1], false).await;
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    assert!(error.to_string().contains("QUIC DATAGRAM"));
}

/// # Cancel safety:
/// NOT cancel-safe: the test joins its bounded local peer and owns the exchange.
#[tokio::test]
async fn h3_settings_reject_invalid_booleans_with_settings_error() {
    for settings in [&[0, 4, 4, 8, 2, 0x33, 1][..], &[0, 4, 4, 8, 1, 0x33, 2][..]] {
        let (_, closed) = rejected_peer(settings, true).await;
        match closed {
            quinn::ConnectionError::ApplicationClosed(close) => assert_eq!(close.error_code.into_inner(), 0x109),
            other => panic!("expected H3_SETTINGS_ERROR, got {other}"),
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test explicitly cancels and joins the opening task, then joins its bounded peer.
#[tokio::test]
async fn h3_settings_wait_is_cancelled_without_leaving_a_connection() {
    let (endpoint, config) = raw_proxy(true);
    let (ready_tx, ready_rx) = oneshot::channel();
    let peer = tokio::spawn(async move {
        let connection = endpoint.accept().await.expect("incoming").await.expect("handshake");
        assert!(tokio::time::timeout(Duration::from_millis(100), connection.accept_bi()).await.is_err());
        ready_tx.send(()).expect("opening running");
        tokio::time::timeout(Duration::from_secs(3), connection.closed())
            .await
            .expect("cancelled client closes its carrier");
    });
    let opening = tokio::spawn(async move {
        let client = MasqueClient::new(config).expect("client");
        client.udp_session().send_to("127.0.0.1:9", b"audit").await
    });
    ready_rx.await.expect("handshake complete");
    opening.abort();
    assert!(opening.await.expect_err("opening cancelled").is_cancelled());
    peer.await.expect("peer task");
}
