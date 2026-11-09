use super::*;
use std::io::Read;
use std::net::TcpListener as BlockingTcpListener;
use std::thread;
use std::time::Duration;

/// # Cancel safety:
/// NOT cancel-safe: the bounded fixture peer must be joined.
#[tokio::test]
async fn h2_accepts_valid_201_tunnel_responses() {
    use std::io::Write;
    for udp in [false, true] {
        let (certificate, key, certificate_pem) = localhost_certificate();
        let mut tls = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(
                vec![rustls::pki_types::CertificateDer::from(certificate)],
                rustls::pki_types::PrivatePkcs8KeyDer::from(key).into(),
            )
            .unwrap();
        tls.alpn_protocols = vec![b"h2".to_vec()];
        let listener = BlockingTcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let peer = thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            let mut stream = rustls::StreamOwned::new(rustls::ServerConnection::new(Arc::new(tls)).unwrap(), socket);
            let mut preface = [0; 24];
            stream.read_exact(&mut preface).unwrap();
            // SETTINGS_ENABLE_CONNECT_PROTOCOL=1, then a valid 201 HEADERS response (no END_STREAM).
            stream.write_all(&[0, 0, 6, 4, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 1]).unwrap();
            stream.flush().unwrap();
            loop {
                let mut h = [0; 9];
                stream.read_exact(&mut h).unwrap();
                let len = (usize::from(h[0]) << 16) | (usize::from(h[1]) << 8) | usize::from(h[2]);
                let mut body = vec![0; len];
                stream.read_exact(&mut body).unwrap();
                if h[3] == 1 {
                    stream.write_all(&[0, 0, 5, 1, 4, 0, 0, 0, 1, 0x48, 3, b'2', b'0', b'1']).unwrap();
                    stream.flush().unwrap();
                    loop {
                        let mut head = [0; 9];
                        stream.read_exact(&mut head).unwrap();
                        let len = (usize::from(head[0]) << 16) | (usize::from(head[1]) << 8) | usize::from(head[2]);
                        let mut data = vec![0; len];
                        stream.read_exact(&mut data).unwrap();
                        if head[3] == 0 {
                            assert_eq!(data, if udp { b"\0\x05\0ping".as_slice() } else { b"ping".as_slice() });
                            let payload = if udp { b"\x00\x05\x00pong".as_slice() } else { b"pong".as_slice() };
                            stream.write_all(&[0, 0, payload.len() as u8, 0, 0, 0, 0, 0, 1]).unwrap();
                            stream.write_all(payload).unwrap();
                            stream.flush().unwrap();
                            std::thread::sleep(Duration::from_millis(100));
                            return;
                        }
                    }
                }
            }
        });
        let mut config = grease_test_config(MasqueTcpProtocol::Http2);
        config.url = format!("https://localhost:{}/", address.port());
        config.proxy_socket_addr = Some(address);
        config.root_certificate_pem = Some(certificate_pem);
        if udp {
            let (tx, mut rx) = tokio::sync::mpsc::channel(1);
            let flow = crate::h2::attempt_h2_connect_udp(&config, "example.test:443", None, tx)
                .await
                .unwrap_or_else(|e| panic!("valid 201 UDP was refused: {e:?}"));
            let crate::udp::MasqueUdpSender::H2(sender) = &flow.sender else {
                panic!("H2 flow");
            };
            sender.send(b"ping".to_vec()).await.unwrap();
            let (_, payload) = tokio::time::timeout(Duration::from_secs(3), rx.recv()).await.unwrap().unwrap();
            assert_eq!(payload, b"pong");
        } else {
            let mut stream = crate::h2::attempt_h2_connect_tcp(&config, "example.test:443", None)
                .await
                .unwrap_or_else(|e| panic!("valid 201 TCP was refused: {e:?}"));
            stream.write_all(b"ping").await.unwrap();
            let mut payload = [0; 4];
            stream.read_exact(&mut payload).await.unwrap();
            assert_eq!(&payload, b"pong");
        }
        tokio::task::spawn_blocking(move || peer.join().unwrap()).await.unwrap();
    }
}
