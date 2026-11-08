use super::*;
use std::io::Read;
use std::net::TcpListener as BlockingTcpListener;
use std::thread;
use std::time::Duration;

/// The caller must connect and join the peer, whose blocking accept has no deadline.
fn start_non_h2_peer(protocol: Option<&[u8]>) -> (SocketAddr, String, thread::JoinHandle<Vec<u8>>) {
    let (certificate, key, certificate_pem) = localhost_certificate();
    let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![rustls::pki_types::CertificateDer::from(certificate)],
            rustls::pki_types::PrivateKeyDer::Pkcs8(rustls::pki_types::PrivatePkcs8KeyDer::from(key)),
        )
        .expect("server certificate");
    tls.alpn_protocols = protocol.into_iter().map(<[u8]>::to_vec).collect();
    let listener = BlockingTcpListener::bind("127.0.0.1:0").expect("listener");
    let address = listener.local_addr().expect("address");
    let peer = thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("accept");
        socket.set_read_timeout(Some(Duration::from_secs(3))).expect("read timeout");
        socket.set_write_timeout(Some(Duration::from_secs(3))).expect("write timeout");
        let mut connection = rustls::ServerConnection::new(Arc::new(tls)).expect("TLS connection");
        while connection.is_handshaking() {
            if let Err(error) = connection.complete_io(&mut socket) {
                assert!(error.to_string().contains("peer doesn't support any known protocol"), "{error}");
                return Vec::new();
            }
        }
        let mut stream = rustls::StreamOwned::new(connection, socket);
        let mut bytes = [0_u8; 24];
        match stream.read(&mut bytes) {
            Ok(count) => bytes[..count].to_vec(),
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted
                ) =>
            {
                Vec::new()
            }
            Err(error) => panic!("read application bytes: {error}"),
        }
    });
    (address, certificate_pem, peer)
}

#[derive(Clone, Copy, Debug)]
enum H2Carrier {
    Udp,
    TcpOverTransport,
    TcpDial,
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation before accept may leave the fixture peer waiting; normal runs join the peer.
#[tokio::test]
async fn h2_carriers_reject_non_h2_peers_before_application_bytes() {
    for carrier in [H2Carrier::Udp, H2Carrier::TcpOverTransport, H2Carrier::TcpDial] {
        for protocol in [None, Some(b"http/1.1".as_slice())] {
            let (address, root, peer) = start_non_h2_peer(protocol);
            let mut config = grease_test_config(MasqueTcpProtocol::Http2);
            config.url = format!("https://localhost:{}/", address.port());
            config.proxy_socket_addr = Some(address);
            config.root_certificate_pem = Some(root);
            let attempt = match carrier {
                H2Carrier::Udp => {
                    let (incoming, _receiver) = tokio::sync::mpsc::channel(1);
                    let attempt = tokio::time::timeout(
                        Duration::from_secs(3),
                        crate::h2::attempt_h2_connect_udp(&config, "example.test:443", None, incoming),
                    )
                    .await
                    .expect("bounded UDP attempt");
                    attempt.map(|_| ())
                }
                H2Carrier::TcpOverTransport => {
                    let socket = tokio::net::TcpStream::connect(address).await.expect("carrier");
                    let attempt = tokio::time::timeout(
                        Duration::from_secs(3),
                        crate::h2::attempt_h2_connect_tcp_over_transport(&config, socket, "example.test:443", None),
                    )
                    .await
                    .expect("bounded TCP-over-transport attempt");
                    attempt.map(|_| ())
                }
                H2Carrier::TcpDial => {
                    let attempt = tokio::time::timeout(
                        Duration::from_secs(3),
                        crate::h2::attempt_h2_connect_tcp(&config, "example.test:443", None),
                    )
                    .await
                    .expect("bounded TCP dialing attempt");
                    attempt.map(|_| ())
                }
            };
            let error = match attempt {
                Err(AttemptError::Io(error)) => error,
                Err(error) => panic!("unexpected carrier failure: {error:?}"),
                Ok(()) => panic!("carrier accepted {protocol:?}"),
            };
            let message = error.to_string();
            assert!(
                message.contains("did not negotiate h2") || message.contains("NO_APPLICATION_PROTOCOL"),
                "non-ALPN carrier failure: {error}"
            );
            assert!(
                peer.join().expect("peer thread").is_empty(),
                "H2 bytes sent without h2 ALPN: carrier={carrier:?} protocol={protocol:?}"
            );
        }
    }
}
