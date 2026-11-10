//! Unit tests of the session client; they use the private items of `session`.
use super::*;

#[cfg(test)]
mod redaction_tests {
    use super::AnyTlsClientConfig;

    fn config_with_secrets() -> AnyTlsClientConfig {
        AnyTlsClientConfig {
            server_host: "example.com".to_owned(),
            server_port: 443,
            server_name: "example.com".to_owned(),
            password: "super-secret-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: Some("-----BEGIN CERTIFICATE-----\nSECRET\n-----END CERTIFICATE-----".to_owned()),
            client_name: "ripdpi-anytls/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        }
    }

    #[test]
    fn debug_redacts_password_and_root_certificate() {
        let rendered = format!("{:?}", config_with_secrets());
        assert!(!rendered.contains("super-secret-password"), "password leaked: {rendered}");
        assert!(!rendered.contains("BEGIN CERTIFICATE"), "root certificate leaked: {rendered}");
        assert!(rendered.contains("<redacted>"), "expected redaction marker: {rendered}");
        // Non-secret fields stay visible for diagnostics.
        assert!(rendered.contains("example.com"), "server_name should remain visible: {rendered}");
    }

    #[test]
    fn debug_omits_certificate_marker_when_absent() {
        let mut config = config_with_secrets();
        config.root_certificate_pem = None;
        let rendered = format!("{config:?}");
        assert!(rendered.contains("root_certificate_pem: None"), "expected None rendering: {rendered}");
    }
}

#[cfg(test)]
mod test_support {
    use super::*;

    impl SessionHandle {
        /// A handle with no carrier task behind `outbound`.
        pub(super) fn detached(seq: u64, outbound: mpsc::Sender<Outbound>, tasks: Arc<OwnedTasks>) -> Self {
            Self {
                seq,
                peer_version: Arc::new(watch::channel(2).0),
                outbound,
                streams: Arc::new(StdMutex::new(HashMap::new())),
                next_stream_id: Arc::new(Mutex::new(1)),
                settings_sent: Arc::new(Mutex::new(false)),
                closing: Arc::new(AtomicBool::new(false)),
                inbound_frames: Arc::new(AtomicU64::new(0)),
                tasks,
            }
        }
    }

    impl AnyTlsStream {
        /// A stream that is not registered with any client pool.
        pub(super) fn detached(
            outbound: mpsc::Sender<Outbound>,
            inbound: mpsc::Receiver<Vec<u8>>,
            tasks: Arc<OwnedTasks>,
            owner: Option<Arc<Owner>>,
        ) -> Self {
            Self {
                stream_id: 1,
                session: SessionHandle::detached(1, outbound, tasks),
                pool: Weak::new(),
                inbound,
                peer_fin: Arc::new(AtomicBool::new(false)),
                read_buffer: VecDeque::new(),
                fin_queued: false,
                syn_sent: true,
                reusable: false,
                owner,
            }
        }
    }
}

#[cfg(test)]
mod session_cache_tests {
    use std::sync::Arc;
    use std::time::Duration;

    use tokio::sync::mpsc;

    use local_network_fixture::{AnyTlsLoopback, AnyTlsLoopbackConfig};

    use super::{AnyTlsClient, AnyTlsClientConfig, Outbound, SessionHandle, TargetAddr};

    #[tokio::test]
    async fn open_tcp_does_not_deadlock_on_closed_cached_session() {
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: 0,
            server_name: "localhost".to_owned(),
            password: "test-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: None,
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let (outbound, outbound_rx) = mpsc::channel::<Outbound>(1);
        drop(outbound_rx);
        client.state.lock().expect("state").idle.push(super::IdleSession {
            session: SessionHandle::detached(1, outbound, Arc::clone(&client.tasks)),
            since: tokio::time::Instant::now(),
        });

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            client.open_tcp(TargetAddr::Domain("example.test".to_owned()), 443),
        )
        .await;

        assert!(result.is_ok(), "closed cached session must not self-deadlock while being evicted");
        assert!(client.state.lock().expect("state").idle.is_empty(), "closed cached session must be evicted");
    }

    #[tokio::test]
    async fn dropping_client_and_last_stream_releases_session_state() {
        let fixture =
            AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: fixture.port(),
            server_name: fixture.server_name().to_owned(),
            password: "fixture-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let stream = client
            .open_tcp(TargetAddr::Ipv4(std::net::Ipv4Addr::LOCALHOST), fixture.target_port())
            .await
            .expect("stream");
        let state = Arc::downgrade(&client.state);

        drop(stream);
        drop(client);

        tokio::time::timeout(Duration::from_secs(1), async {
            while state.upgrade().is_some() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("session task must not keep client state alive");
    }

    #[tokio::test]
    async fn cancelled_open_before_batch_commit_does_not_enqueue_fin() {
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".into(),
            server_port: 1,
            server_name: "outbound.invalid".into(),
            password: "loopback-test-password".into(),
            tls_fingerprint_profile: "chrome".into(),
            root_certificate_pem: None,
            client_name: "outbound-test".into(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let (outbound, mut receiver) = mpsc::channel(1);
        outbound.send(Outbound::Batch(Vec::new())).await.expect("fill carrier queue");
        let session = SessionHandle::detached(1, outbound, Arc::clone(&client.tasks));
        let routes = Arc::clone(&session.streams);
        client
            .state
            .lock()
            .expect("state")
            .idle
            .push(super::IdleSession { session, since: tokio::time::Instant::now() });
        let caller = client.clone();
        let opening =
            tokio::spawn(async move { caller.open_tcp(TargetAddr::Domain("interop.invalid".into()), 443).await });
        tokio::time::timeout(Duration::from_secs(1), async {
            while routes.lock().expect("routes").is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("open route inserted before blocked batch");
        opening.abort();
        let _ = opening.await;
        tokio::time::timeout(Duration::from_secs(1), async {
            while !routes.lock().expect("routes").is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("cancelled route removed");
        receiver.recv().await.expect("original queued batch");
        let unexpected = tokio::time::timeout(Duration::from_millis(30), receiver.recv()).await;
        assert!(unexpected.is_err(), "a stream whose SYN was never queued must not create a background FIN send");
        let caller = client.clone();
        let second =
            tokio::spawn(async move { caller.open_tcp(TargetAddr::Domain("interop.invalid".into()), 443).await });
        let Outbound::Batch(frames) = tokio::time::timeout(Duration::from_secs(1), receiver.recv())
            .await
            .expect("second batch deadline")
            .expect("second open batch");
        let syn = frames.iter().find(|frame| frame.command() == super::Command::Syn).expect("SYN");
        assert_eq!(syn.stream_id(), 2, "cancelled allocation is not reused");
        super::handle_synack(&routes, syn.stream_id(), &[]);
        let stream = second.await.expect("second caller joined").expect("second stream accepted");
        client.close().await.expect("join client workers");
        assert_eq!(
            frames[0].command(),
            super::Command::Settings,
            "first committed open must send Settings even when stream 1 was cancelled"
        );
        drop(stream);
    }

    #[tokio::test]
    async fn cancelled_datagram_read_preserves_partial_wire_header() {
        use std::future::Future;
        let (outbound, _outbound_rx) = mpsc::channel(1);
        let (inbound_tx, inbound) = mpsc::channel(2);
        let stream = super::AnyTlsStream::detached(outbound, inbound, Arc::default(), None);
        let mut udp = super::AnyTlsUdpOverTcp { stream, pending: Vec::new() };
        inbound_tx.send(vec![0]).await.expect("first partial IPv4 header");
        let mut recv = Box::pin(udp.recv_datagram());
        std::future::poll_fn(|cx| {
            assert!(recv.as_mut().poll(cx).is_pending());
            std::task::Poll::Ready(())
        })
        .await;
        drop(recv); // The uplink branch of the SOCKS pump can win here.
        inbound_tx.send(vec![127, 0, 0, 1, 1, 187, 0, 3, b'a', b'b', b'c']).await.expect("remaining frame");
        let packet = tokio::time::timeout(Duration::from_millis(100), udp.recv_datagram())
            .await
            .expect("datagram deadline")
            .expect("cancelled header must be retained");
        assert_eq!(packet.target, TargetAddr::Ipv4(std::net::Ipv4Addr::LOCALHOST));
        assert_eq!(packet.port, 443);
        assert_eq!(packet.payload, b"abc");
    }

    fn idle_client() -> AnyTlsClient {
        AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: 1,
            server_name: "localhost".to_owned(),
            password: "test-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: None,
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client")
    }

    #[tokio::test]
    async fn the_idle_session_with_the_highest_seq_is_reused_first() {
        let client = idle_client();
        let mut carriers = Vec::new();
        for seq in [2, 5, 3] {
            let (outbound, carrier) = mpsc::channel::<Outbound>(1);
            carriers.push(carrier);
            super::release_session(&client.state, SessionHandle::detached(seq, outbound, Arc::clone(&client.tasks)));
        }
        let order: Vec<u64> = std::iter::from_fn(|| client.take_idle_session()).map(|session| session.seq).collect();
        assert_eq!(order, vec![5, 3, 2]);
    }

    #[tokio::test]
    async fn an_idle_session_past_the_timeout_is_not_reused() {
        let client = idle_client();
        client.state.lock().expect("state").idle_timeout = Duration::from_millis(50);
        let (outbound, _carrier) = mpsc::channel::<Outbound>(1);
        client.state.lock().expect("state").idle.push(super::IdleSession {
            session: SessionHandle::detached(1, outbound, Arc::clone(&client.tasks)),
            since: tokio::time::Instant::now() - Duration::from_secs(1),
        });
        assert!(client.take_idle_session().is_none(), "a session idle past the timeout is discarded on take");
        assert!(client.state.lock().expect("state").idle.is_empty());
    }

    /// A client over a caller's transport has no network dialer: with an empty
    /// pool the open fails and nothing connects to the configured server.
    #[tokio::test]
    async fn a_transport_client_never_dials_the_server() {
        let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
        let mut client = idle_client();
        client.allow_network_dial = false;
        let mut config = (*client.config).clone();
        config.server_port = listener.local_addr().expect("address").port();
        client.config = Arc::new(config);

        let error = client.open_tcp(TargetAddr::Domain("example.test".to_owned()), 443).await.expect_err("no dial");
        assert!(matches!(error, super::AnyTlsError::SessionClosed), "unexpected error: {error}");
        let accepted = tokio::time::timeout(Duration::from_millis(300), listener.accept()).await;
        assert!(accepted.is_err(), "the client connected to the server it must not dial");
    }

    /// The peer version belongs to one session: a later session of the same
    /// client starts unknown, because addresses of a server can differ and a
    /// server can be downgraded.
    #[tokio::test]
    async fn a_new_session_does_not_inherit_the_peer_version() {
        let fixture =
            AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
        let client = AnyTlsClient::new(AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: fixture.port(),
            server_name: fixture.server_name().to_owned(),
            password: "fixture-password".to_owned(),
            tls_fingerprint_profile: "chrome".to_owned(),
            root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
        .expect("client");
        let _stream = client
            .open_tcp(TargetAddr::Ipv4(std::net::Ipv4Addr::LOCALHOST), fixture.target_port())
            .await
            .expect("stream");
        let first = client.take_idle_session();
        assert!(first.is_none(), "the only session carries the stream");
        let second = client.establish_network_session().await.expect("second session");
        assert_eq!(*second.peer_version.borrow(), 0, "a fresh session has no version yet");
    }

    #[tokio::test]
    async fn idle_sessions_are_closed_after_the_timeout() {
        let client = idle_client();
        {
            let mut state = client.state.lock().expect("state");
            state.idle_timeout = Duration::from_millis(150);
            state.sweep_interval = Duration::from_millis(40);
        }
        let (outbound, _carrier) = mpsc::channel::<Outbound>(1);
        super::release_session(&client.state, SessionHandle::detached(1, outbound, Arc::clone(&client.tasks)));
        tokio::time::sleep(Duration::from_millis(60)).await;
        assert_eq!(client.state.lock().expect("state").idle.len(), 1, "a session younger than the timeout stays");
        tokio::time::timeout(Duration::from_secs(2), async {
            while !client.state.lock().expect("state").idle.is_empty() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("a session idle past the timeout is closed");
        tokio::time::timeout(Duration::from_secs(1), async {
            while client.state.lock().expect("state").sweeper_running {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("the sweeper ends with an empty pool");
    }

    /// A frame with an unknown command is skipped with its data; the next frame
    /// still decodes.
    #[tokio::test]
    async fn read_frame_skips_an_unknown_command_with_its_data() {
        use tokio::io::AsyncWriteExt;
        let (mut peer, mut local) = tokio::io::duplex(64);
        peer.write_all(&[11, 0, 0, 0, 7, 0, 3, b'a', b'b', b'c']).await.expect("unknown frame");
        peer.write_all(&[super::Command::Psh as u8, 0, 0, 0, 1, 0, 2, b'o', b'k']).await.expect("known frame");

        assert_eq!(super::read_frame(&mut local, false).await.expect("unknown frame is not an error"), None);
        let frame = super::read_frame(&mut local, false).await.expect("next frame").expect("known frame");
        assert_eq!((frame.command(), frame.stream_id(), frame.data()), (super::Command::Psh, 1, &b"ok"[..]));
    }

    #[tokio::test]
    async fn writer_failure_terminates_session_supervision() {
        let reader = std::future::pending::<Result<(), super::AnyTlsError>>();
        let writer = std::future::ready(Err(super::AnyTlsError::Io("writer failed".to_owned())));

        let result = tokio::time::timeout(Duration::from_millis(100), super::supervise_session_io(reader, writer))
            .await
            .expect("writer failure must terminate supervision")
            .expect_err("writer failure must close the session");

        assert!(matches!(result, super::AnyTlsError::Io(error) if error == "writer failed"));
    }
}

#[cfg(test)]
mod protect_tests {
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::{io, protect_outbound_socket};

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let err = protect_outbound_socket(
            &listener,
            non_loopback(),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect_err("must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn non_loopback_is_protected_via_callback() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd());
        unregister_protect_callback();
    }

    #[test]
    fn loopback_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1);
        unregister_protect_callback();
    }
}

#[cfg(test)]
mod connect_tests {
    use std::net::{Ipv4Addr, SocketAddr};

    use super::connect_first_reachable;

    const INACTIVE: ripdpi_native_protect::SocketProtectionPolicy =
        ripdpi_native_protect::SocketProtectionPolicy::Inactive;

    const ATTEMPT: std::time::Duration = std::time::Duration::from_secs(3);

    /// A loopback port nobody listens on.
    fn closed_addr() -> SocketAddr {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
        listener.local_addr().expect("address")
    }

    #[tokio::test]
    async fn connect_falls_through_to_the_next_resolved_address() {
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let reachable = listener.local_addr().expect("address");
        let tcp =
            connect_first_reachable(vec![closed_addr(), reachable], None, INACTIVE, ATTEMPT).await.expect("fallback");
        assert_eq!(tcp.peer_addr().expect("peer"), reachable);
    }

    /// An address that never answers must not use up the whole budget. 192.0.2.1
    /// (TEST-NET-1) is black-holed on most hosts; where the network rejects it at
    /// once, the fallback is still exercised, only without waiting.
    #[tokio::test]
    async fn connect_gives_each_address_a_bounded_attempt() {
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let reachable = listener.local_addr().expect("address");
        let black_hole: SocketAddr = "192.0.2.1:9".parse().expect("test net address");
        let started = std::time::Instant::now();
        let tcp =
            connect_first_reachable(vec![black_hole, reachable], None, INACTIVE, std::time::Duration::from_millis(300))
                .await
                .expect("the next address is tried");
        assert_eq!(tcp.peer_addr().expect("peer"), reachable);
        assert!(started.elapsed() < std::time::Duration::from_secs(3), "took {:?}", started.elapsed());
    }

    #[tokio::test]
    async fn connect_returns_the_last_error_when_every_address_fails() {
        let error = connect_first_reachable(vec![closed_addr(), closed_addr()], None, INACTIVE, ATTEMPT)
            .await
            .expect_err("all addresses closed");
        assert_eq!(error.kind(), std::io::ErrorKind::ConnectionRefused);
    }

    #[tokio::test]
    async fn connect_skips_addresses_of_the_other_family_than_the_bind_ip() {
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("listener");
        let reachable = listener.local_addr().expect("address");
        let v6: SocketAddr = "[::1]:9".parse().expect("v6");
        let bind = Some(std::net::IpAddr::V4(Ipv4Addr::LOCALHOST));
        let tcp = connect_first_reachable(vec![v6, reachable], bind, INACTIVE, ATTEMPT).await.expect("v4 only");
        assert_eq!(tcp.peer_addr().expect("peer"), reachable);
        let error = connect_first_reachable(vec![v6], bind, INACTIVE, ATTEMPT).await.expect_err("no v4 address");
        assert!(error.to_string().contains("outbound bind IP family"), "unexpected error: {error}");
    }
}

#[cfg(test)]
mod tls_profile_tests {
    use tokio::io::AsyncReadExt;

    use super::{AnyTlsClientConfig, PaddingAction, PaddingScheme, connect_tls, random_selector};

    #[test]
    fn padding_ranges_are_drawn_independently_per_packet() {
        let scheme = PaddingScheme::parse(b"stop=2\n1=100-1000,100-1000").expect("scheme");
        let draws: Vec<(usize, usize)> = (0..32)
            .map(|_| match scheme.write_plan_for_packet_with(1, b"x", random_selector).expect("plan").as_slice() {
                [PaddingAction::PayloadThenWaste { waste_len, .. }, PaddingAction::Waste { len }] => (*waste_len, *len),
                other => panic!("unexpected plan {other:?}"),
            })
            .collect();
        assert!(draws.iter().any(|draw| *draw != draws[0]), "sizes must vary between packets: {draws:?}");
        assert!(draws.iter().any(|(first, second)| first + 8 != *second), "ranges must be drawn independently");
    }

    /// Reads the ClientHello the client writes and returns its cipher suites.
    async fn client_hello_cipher_suites(profile: &str) -> Vec<u16> {
        let config = AnyTlsClientConfig {
            server_host: "127.0.0.1".to_owned(),
            server_port: 443,
            server_name: "example.test".to_owned(),
            password: "test-password".to_owned(),
            tls_fingerprint_profile: profile.to_owned(),
            root_certificate_pem: None,
            client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        };
        let (client, mut server) = tokio::io::duplex(64 * 1024);
        let handshake = tokio::spawn(async move { connect_tls(&config, client).await.map(|_| ()) });
        let mut header = [0_u8; 5];
        server.read_exact(&mut header).await.expect("record header");
        let mut body = vec![0_u8; usize::from(u16::from_be_bytes([header[3], header[4]]))];
        server.read_exact(&mut body).await.expect("ClientHello");
        handshake.abort();
        // Handshake header (4), legacy_version (2), random (32), session id.
        let session_id_end = 4 + 2 + 32 + 1 + usize::from(body[38]);
        let suites_len = usize::from(u16::from_be_bytes([body[session_id_end], body[session_id_end + 1]]));
        body[session_id_end + 2..session_id_end + 2 + suites_len]
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| u16::from_be_bytes(*pair))
            .collect()
    }

    #[tokio::test]
    async fn client_hello_keeps_the_browser_profile_tls12_cipher_suites() {
        const TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256: u16 = 0xc02f;
        let suites = client_hello_cipher_suites("chrome").await;
        assert!(
            suites.contains(&TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256),
            "a TLS 1.3-only override drops the TLS 1.2 suites a browser offers: {suites:04x?}"
        );
    }
}

#[cfg(test)]
mod bridge_tests {
    use std::sync::Arc;
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::sync::mpsc;

    use super::{AnyTlsStream, OwnedTasks, Owner};

    /// A stalled uplink (full carrier queue) must not stop the pump from
    /// draining the stream's downlink; one coupled loop starved the mailbox
    /// into a reset.
    #[tokio::test]
    async fn a_stalled_uplink_does_not_stop_downlink_delivery() {
        let tasks = Arc::new(OwnedTasks::default());
        let (outbound, _stalled_carrier) = mpsc::channel(1);
        let (inbound_tx, inbound) = mpsc::channel(32);
        let stream =
            AnyTlsStream::detached(outbound, inbound, Arc::clone(&tasks), Some(Arc::new(Owner(Arc::clone(&tasks)))));
        let mut app = stream.into_io().expect("bridge");
        // The first chunk fills the carrier queue; the pump then waits to send the second.
        app.write_all(b"first").await.expect("first upload");
        tokio::time::sleep(Duration::from_millis(50)).await;
        app.write_all(b"second").await.expect("second upload");
        tokio::time::sleep(Duration::from_millis(50)).await;

        inbound_tx.send(b"reply".to_vec()).await.expect("downlink chunk");
        let mut reply = [0; 5];
        tokio::time::timeout(Duration::from_secs(1), app.read_exact(&mut reply))
            .await
            .expect("downlink must flow while the uplink is stalled")
            .expect("read");
        assert_eq!(&reply, b"reply");
    }
}
