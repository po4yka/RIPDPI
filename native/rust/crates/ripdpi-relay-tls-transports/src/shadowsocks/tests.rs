use super::*;
use shadowsocks::{config::ServerType, context::Context, relay::tcprelay::ProxyServerStream};
use std::time::Duration;
use tokio::io::AsyncReadExt;

const METHODS: [(&str, &str); 6] = [
    ("aes-128-gcm", "test-credential"),
    ("aes-256-gcm", "test-credential"),
    ("chacha20-ietf-poly1305", "test-credential"),
    ("2022-blake3-aes-128-gcm", "AAECAwQFBgcICQoLDA0ODw=="),
    ("2022-blake3-aes-256-gcm", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
    ("2022-blake3-chacha20-poly1305", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
];

fn factory(host: &str, port: u16, method: &str, credential: &str) -> ShadowsocksSessionFactory {
    ShadowsocksSessionFactory::new(
        host.into(),
        port,
        method.into(),
        credential.into(),
        None,
        ripdpi_native_protect::SocketProtectionPolicy::Inactive,
    )
    .unwrap()
}

/// # Cancel safety:
/// cancel-safe: the runtime owns the server tasks and both ends of every stream.
#[tokio::test]
async fn every_method_supports_server_first_data_and_half_close() {
    for (method, credential) in METHODS {
        let factory = factory("invalid.test", 443, method, credential);
        let (transport, peer) = tokio::io::duplex(8192);
        let config = &factory.config.server;
        let mut server = ProxyServerStream::from_stream(
            Context::new_shared(ServerType::Server),
            peer,
            config.method(),
            config.key(),
        );
        let task = tokio::spawn(async move {
            assert_eq!(server.handshake().await.unwrap().to_string(), "example.com:443");
            server.write_all(b"greeting").await.unwrap();
            let mut request = Vec::new();
            server.read_to_end(&mut request).await.unwrap();
            assert_eq!(request, b"client request");
            server.write_all(b"after FIN").await.unwrap();
            server.shutdown().await.unwrap();
        });
        tokio::time::timeout(Duration::from_secs(3), async {
            let mut app = connect_shadowsocks_tcp_over(&factory, transport, "example.com:443").await.unwrap();
            let mut greeting = [0; 8];
            app.read_exact(&mut greeting).await.unwrap();
            assert_eq!(&greeting, b"greeting");
            app.write_all(b"client request").await.unwrap();
            app.shutdown().await.unwrap();
            let mut response = Vec::new();
            app.read_to_end(&mut response).await.unwrap();
            assert_eq!(response, b"after FIN");
            task.await.unwrap();
        })
        .await
        .expect(method);
    }
}

/// # Cancel safety:
/// cancel-safe: the test runtime owns all streams and server tasks.
#[tokio::test]
async fn client_first_and_full_drop_release_the_carrier() {
    let factory = factory("invalid.test", 443, METHODS[0].0, METHODS[0].1);
    let (transport, peer) = tokio::io::duplex(4096);
    let config = &factory.config.server;
    let mut server =
        ProxyServerStream::from_stream(Context::new_shared(ServerType::Server), peer, config.method(), config.key());
    let task = tokio::spawn(async move {
        server.handshake().await.unwrap();
        let mut request = [0; 7];
        server.read_exact(&mut request).await.unwrap();
        assert_eq!(&request, b"request");
        server.write_all(b"ok").await.unwrap();
        let mut end = [0; 1];
        assert_eq!(server.read(&mut end).await.unwrap(), 0);
    });
    let mut app = connect_shadowsocks_tcp_over(&factory, transport, "192.0.2.1:443").await.unwrap();
    app.write_all(b"request").await.unwrap();
    let mut response = [0; 2];
    app.read_exact(&mut response).await.unwrap();
    assert_eq!(&response, b"ok");
    drop(app);
    tokio::time::timeout(Duration::from_secs(2), task).await.unwrap().unwrap();
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the stream held in the pending connect future.
#[tokio::test]
async fn cancelled_initial_write_closes_transport() {
    let factory = factory("invalid.test", 443, METHODS[0].0, METHODS[0].1);
    let (transport, mut peer) = tokio::io::duplex(1);
    assert!(
        tokio::time::timeout(
            Duration::from_millis(20),
            connect_shadowsocks_tcp_over(&factory, transport, "example.com:443")
        )
        .await
        .is_err()
    );
    let mut bytes = Vec::new();
    tokio::time::timeout(Duration::from_secs(1), peer.read_to_end(&mut bytes)).await.unwrap().unwrap();
    assert!(!bytes.is_empty());
}

/// # Cancel safety:
/// cancel-safe: test-owned streams are dropped on cancellation.
#[tokio::test]
async fn corrupt_or_truncated_ciphertext_returns_error_instead_of_eof_or_hang() {
    for response in [vec![0xab; 128], vec![0xab; 2]] {
        let factory = factory("invalid.test", 443, METHODS[0].0, METHODS[0].1);
        let (transport, mut peer) = tokio::io::duplex(4096);
        let mut app = connect_shadowsocks_tcp_over(&factory, transport, "192.0.2.1:443").await.unwrap();
        peer.write_all(&response).await.unwrap();
        peer.shutdown().await.unwrap();
        let mut plaintext = Vec::new();
        let result = tokio::time::timeout(Duration::from_secs(1), app.read_to_end(&mut plaintext)).await.unwrap();
        assert!(result.is_err());
        assert!(plaintext.is_empty());
    }
}

/// # Cancel safety:
/// cancel-safe: rejected datagram open never creates a socket.
#[tokio::test]
async fn legacy_methods_reject_udp_before_resolution() {
    for (method, credential) in &METHODS[..3] {
        let factory = factory("invalid.test", 443, method, credential);
        assert!(!factory.capabilities().udp);
        let session = factory.create_session().await.unwrap();
        let Err(error) = session.open_datagram().await else {
            panic!("legacy UDP accepted");
        };
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    }
}

#[test]
fn target_validation_preserves_ipv6_and_rejects_bare_literals() {
    assert!(target_address("2001:db8::1").is_err());
    let factory = factory("2001:db8::1", 443, METHODS[0].0, METHODS[0].1);
    assert_eq!(shadowsocks_proxy_target(&factory), "[2001:db8::1]:443");
    assert!(target_address(&shadowsocks_proxy_target(&factory)).is_ok());
    assert!(matches!(target_address("[2001:db8::1]:443").unwrap(), Address::SocketAddress(SocketAddr::V6(_))));
    assert!(target_address(&format!("{}:443", "x".repeat(256))).is_err());
}

/// # Cancel safety:
/// cancel-safe: the test owns the sockets and metadata; no tasks are detached.
#[tokio::test]
async fn udp_upstream_exchange_rejects_bad_packets_and_reuses_buffer() {
    for (method, credential) in &METHODS[3..] {
        let raw_server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let port = raw_server.local_addr().unwrap().port();
        let factory = factory("127.0.0.1", port, method, credential);
        let session = factory.create_session().await.unwrap();
        let mut client = session.open_datagram().await.unwrap();
        let timestamp = Arc::new(std::sync::atomic::AtomicU64::new(NetworkTimeProvider::shared().now_unix_u64()));
        let clock = Arc::clone(&timestamp);
        let server_context = Arc::new({
            let mut context = Context::new(ServerType::Server);
            context.set_timestamp_provider(Arc::new(move || clock.load(std::sync::atomic::Ordering::Relaxed)));
            context
        });
        let server = ProxySocket::from_socket(
            UdpSocketType::Server,
            server_context,
            &factory.config.server,
            ProxyUdpSocket::from(raw_server),
        );
        client.send_to("192.0.2.1:53", b"query").await.unwrap();
        let mut buffer = vec![0; BUFFER_SIZE];
        let (read, peer, address, _, control) = server.recv_from_with_ctrl(&mut buffer).await.unwrap();
        assert_eq!(&buffer[..read], b"query");
        assert_eq!(address.to_string(), "192.0.2.1:53");
        let mut control = control.unwrap();
        control.server_session_id = 123;
        let allocation = client.receive_buffer.as_ptr();
        timestamp.store(NetworkTimeProvider::shared().now_unix_u64() - 31, std::sync::atomic::Ordering::Relaxed);
        server.send_to_with_ctrl(peer, &address, &control, b"stale timestamp").await.unwrap();
        // This atomic supplies only a scalar test clock, with no associated memory publication.
        timestamp.store(NetworkTimeProvider::shared().now_unix_u64(), std::sync::atomic::Ordering::Relaxed);
        // Wrong recipient must not consume this packet ID or calibrate the clock.
        control.client_session_id ^= 1;
        server.send_to_with_ctrl(peer, &address, &control, b"wrong recipient").await.unwrap();
        control.client_session_id ^= 1;
        server.send_to_with_ctrl(peer, &address, &control, b"first").await.unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(2), client.recv_from()).await.unwrap().unwrap().1,
            b"first"
        );
        server.send_to_with_ctrl(peer, &address, &control, b"replay").await.unwrap();
        control.packet_id += 1;
        server.send_to_with_ctrl(peer, &address, &control, b"second").await.unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(2), client.recv_from()).await.unwrap().unwrap().1,
            b"second"
        );
        assert_eq!(client.receive_buffer.as_ptr(), allocation);
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the unconnected socket.
#[tokio::test]
async fn udp_supports_ipv6_only_server_without_bind_ip() {
    let factory = factory("::1", 443, METHODS[3].0, METHODS[3].1);
    let session = factory.create_session().await.unwrap();
    let datagram = session.open_datagram().await.unwrap();
    assert!(datagram.socket.local_addr().unwrap().is_ipv6());
}

/// # Cancel safety:
/// cancel-safe: cancelled writes keep acknowledged bytes, and the test owns both stream ends.
#[tokio::test]
async fn cancelled_write_does_not_acknowledge_or_send_the_next_callers_bytes() {
    for (method, credential) in METHODS {
        let factory = factory("invalid.test", 443, method, credential);
        let (transport, peer) = tokio::io::duplex(256);
        let config = &factory.config.server;
        let mut server = ProxyServerStream::from_stream(
            Context::new_shared(ServerType::Server),
            peer,
            config.method(),
            config.key(),
        );
        let (release, receive) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move {
            server.handshake().await.unwrap();
            receive.await.unwrap();
            let mut received = Vec::new();
            server.read_to_end(&mut received).await.unwrap();
            received
        });
        let mut app = connect_shadowsocks_tcp_over(&factory, transport, "example.com:443").await.unwrap();
        let first = vec![b'A'; 64_000];
        let accepted = app.write(&first).await.unwrap();
        assert!(accepted > 256);
        assert!(tokio::time::timeout(Duration::from_millis(20), app.write(b"cancelled B")).await.is_err());
        release.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(3), async {
            app.write_all(b"accepted C").await.unwrap();
            app.shutdown().await.unwrap();
            let received = task.await.unwrap();
            let mut expected = first[..accepted].to_vec();
            expected.extend_from_slice(b"accepted C");
            assert_eq!(received, expected, "{method}");
        })
        .await
        .expect(method);
    }
}

/// Frozen SIP004 frame: MD5(password), HKDF-SHA1 with "ss-subkey", then AES-GCM
/// length and payload using nonces 0 and 1. Generated independently with Python
/// cryptography, using the pre-migration KAT subkey 741ba530dc7cb08412118302a3833ce4.
/// # Cancel safety:
/// cancel-safe: the test owns both ends and closes them on cancellation.
#[tokio::test]
async fn independent_sip004_wire_vector_and_repeated_salt() {
    let factory = factory("invalid.test", 443, "aes-128-gcm", "correct horse battery staple");
    let wire = b"\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x16\xc2\xe9\x48\x1c\x68\xc2\x11\x4f\x71\x79\xdd\xa0\x68\xfd\xe2\x30\xc5\x2e\xd6\x5c\xc8\x82\x7e\x68\x76\x37\x4e\x89\x56\x5b\x2d\xa7\x03\xb8\x0b\xa0\xc3\x2e\x2c\x03\xd1\x90\xf5\xca\xea\x62\xd8\x27\x54\x64\x49\xa0\x60\x33\x4f\x51\x7b\x83\x77\xa8\xd0\xa7\x83\xdc";
    for replay in [false, true] {
        let (transport, mut peer) = tokio::io::duplex(4096);
        let mut app = connect_shadowsocks_tcp_over(&factory, transport, "192.0.2.1:443").await.unwrap();
        peer.write_all(wire).await.unwrap();
        peer.shutdown().await.unwrap();
        let mut plaintext = Vec::new();
        let result = app.read_to_end(&mut plaintext).await;
        if replay {
            assert!(result.is_err());
            assert!(plaintext.is_empty());
        } else {
            result.unwrap();
            assert_eq!(plaintext, b"RIPDPI independent SIP004 frame");
        }
    }
}

/// # Cancel safety:
/// cancel-safe: the test runtime owns the split stream tasks and the server.
#[tokio::test]
async fn split_reader_does_not_steal_pending_writers_wakeup() {
    for (method, credential) in [METHODS[0], METHODS[3]] {
        let factory = factory("invalid.test", 443, method, credential);
        let (transport, peer) = tokio::io::duplex(256);
        let config = &factory.config.server;
        let mut server = ProxyServerStream::from_stream(
            Context::new_shared(ServerType::Server),
            peer,
            config.method(),
            config.key(),
        );
        let (release, receive) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            server.handshake().await.unwrap();
            receive.await.unwrap();
            let mut request = Vec::new();
            server.read_to_end(&mut request).await.unwrap();
            assert!(request.ends_with(b"second write"));
            server.write_all(b"response").await.unwrap();
            server.shutdown().await.unwrap();
        });
        let mut app = connect_shadowsocks_tcp_over(&factory, transport, "example.com:443").await.unwrap();
        assert!(app.write(&vec![b'A'; 4096]).await.unwrap() > 256);
        let (mut reader, mut writer) = tokio::io::split(app);
        let (blocked, waiting) = tokio::sync::oneshot::channel();
        let writer_task = tokio::spawn(async move {
            let mut blocked = Some(blocked);
            std::future::poll_fn(|cx| {
                let result = std::pin::Pin::new(&mut writer).poll_write(cx, b"second write");
                if result.is_pending()
                    && let Some(blocked) = blocked.take()
                {
                    blocked.send(()).unwrap();
                }
                result
            })
            .await
            .unwrap();
            writer.shutdown().await.unwrap();
        });
        waiting.await.unwrap();
        // Poll the reader after the writer has registered its carrier-write waker.
        let reader_task = tokio::spawn(async move {
            let mut release = Some(release);
            let mut response = Vec::new();
            let mut read = Box::pin(reader.read_to_end(&mut response));
            std::future::poll_fn(|cx| {
                use std::future::Future;
                let result = read.as_mut().poll(cx);
                if let Some(release) = release.take() {
                    release.send(()).unwrap();
                }
                result
            })
            .await
            .unwrap();
            drop(read);
            assert_eq!(response, b"response");
        });
        tokio::time::timeout(Duration::from_secs(3), async {
            writer_task.await.unwrap();
            reader_task.await.unwrap();
            server_task.await.unwrap();
        })
        .await
        .expect(method);
    }
}
