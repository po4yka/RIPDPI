use std::net::Ipv4Addr;
use std::time::Duration;

use local_network_fixture::{AnyTlsLoopback, AnyTlsLoopbackConfig};
use ripdpi_anytls::DEFAULT_PADDING_SCHEME;
use ripdpi_anytls::padding::PaddingScheme;
use ripdpi_anytls::session::{AnyTlsClient, AnyTlsClientConfig, AnyTlsError, SharedPaddingScheme, TargetAddr};
use sha2::{Digest, Sha256};

fn client_config(fixture: &AnyTlsLoopback, password: &str) -> AnyTlsClientConfig {
    AnyTlsClientConfig {
        server_host: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        password: password.to_owned(),
        tls_fingerprint_profile: "chrome".to_owned(),
        root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
        client_name: "ripdpi-anytls-test/0.1.0".to_owned(),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
    }
}

#[tokio::test]
async fn auth_packet_uses_sha256_password_and_padding0_before_session_loop() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let mut stream =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("open stream");
    stream.write_all(b"hello over anytls").await.expect("write");
    assert_eq!(stream.read_exact_len(17).await.expect("read"), b"hello over anytls");

    let observed = fixture.observed();
    let expected_hash = Sha256::digest(b"fixture-password");
    let default_scheme = PaddingScheme::parse(DEFAULT_PADDING_SCHEME.as_bytes()).expect("default scheme");
    assert_eq!(&observed.auth_packet[..32], expected_hash.as_slice());
    assert_eq!(
        u16::from_be_bytes([observed.auth_packet[32], observed.auth_packet[33]]) as usize,
        default_scheme.auth_padding0_len(0).expect("padding0 len")
    );
    assert_eq!(observed.auth_packet.len(), 34 + default_scheme.auth_padding0_len(0).expect("padding0 len"));
}

#[tokio::test]
async fn bad_password_is_not_reported_as_a_successful_stream() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "wrong-password")).expect("client");

    let err = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect_err("bad auth must fail");

    assert!(matches!(err, AnyTlsError::AuthenticationRejected), "unexpected error: {err}");
    assert!(err.to_string().contains("wrong password or not an AnyTLS server"));
}

/// Each session carries one stream; a finished stream returns its session to
/// the client's pool, and the next open reuses it with the next stream id.
#[tokio::test]
async fn concurrent_streams_use_separate_sessions_and_a_finished_stream_returns_its_session() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let mut first =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("open first stream");
    let mut second = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("open second stream");

    first.write_all(b"first").await.expect("write first");
    second.write_all(b"second").await.expect("write second");

    assert_eq!(first.read_exact_len(5).await.expect("read first"), b"first");
    assert_eq!(second.read_exact_len(6).await.expect("read second"), b"second");

    let observed = fixture.observed();
    assert_eq!(observed.tls_session_count, 2, "a session carries at most one stream");
    assert_eq!(observed.settings_padding_md5.len(), 2, "each session sends its own Settings");
    assert_eq!(observed.syn_stream_ids, vec![1, 1]);
    assert_eq!(observed.synack_successes, vec![1, 1]);
    assert_eq!(observed.tcp_targets.len(), 2);

    drop(first);
    drop(second);
    let mut third =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("open third stream");
    third.write_all(b"third").await.expect("write third");
    assert_eq!(third.read_exact_len(5).await.expect("read third"), b"third");
    let observed = fixture.observed();
    assert_eq!(observed.tls_session_count, 2, "the third stream reuses an idle session");
    assert_eq!(observed.syn_stream_ids.len(), 3);
    assert_eq!(observed.syn_stream_ids[2], 2, "the reused session continues its stream ids");
}

#[tokio::test]
async fn dropping_open_stream_sends_fin_and_releases_route() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");
    let stream =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("open stream");

    drop(stream);

    tokio::time::timeout(Duration::from_secs(1), async {
        while fixture.observed().fin_stream_ids != vec![1] {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("dropping a stream must send FIN");
}

#[tokio::test]
async fn close_sends_fin_once_and_drop_does_not_duplicate_it() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");
    let mut stream =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("open stream");

    // The stream is alive before the close.
    stream.write_all(b"ping").await.expect("write");
    assert_eq!(stream.read_exact_len(4).await.expect("echo"), b"ping");

    stream.close().await.expect("close");

    tokio::time::timeout(Duration::from_secs(1), async {
        while fixture.observed().fin_stream_ids != vec![1] {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("close must send FIN");

    // Dropping after a successful close must not queue a second FIN for
    // the same substream.
    drop(stream);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(fixture.observed().fin_stream_ids, vec![1], "drop must not duplicate the FIN");
}

#[tokio::test]
async fn concurrent_opens_dial_one_session_each() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let (first, second) = tokio::join!(
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    );
    let mut first = first.expect("open first stream");
    let mut second = second.expect("open second stream");

    first.write_all(b"first").await.expect("write first");
    second.write_all(b"second").await.expect("write second");
    assert_eq!(first.read_exact_len(5).await.expect("read first"), b"first");
    assert_eq!(second.read_exact_len(6).await.expect("read second"), b"second");

    let observed = fixture.observed();
    assert_eq!(observed.tls_session_count, 2);
    assert_eq!(observed.syn_stream_ids, vec![1, 1]);
}

/// The fixture parks its frame loop in the SYNACK delay, so it answers neither
/// the SYN nor a HeartRequest: the carrier is silently dead. The open fails
/// after the liveness probe and the next open dials a new carrier.
#[tokio::test]
async fn open_on_a_silent_carrier_fails_and_evicts_it() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { synack_delay: Duration::from_secs(60), ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let started = std::time::Instant::now();
    let result = tokio::time::timeout(
        Duration::from_secs(15),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("an open on a silent carrier must not wait for the caller deadline");
    assert!(result.is_err(), "the open must fail");
    assert!(started.elapsed() >= Duration::from_secs(7), "a probe precedes the verdict");

    let second = client.clone();
    let target_port = fixture.target_port();
    let reopening =
        tokio::spawn(async move { second.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target_port).await });
    tokio::time::timeout(Duration::from_secs(5), async {
        while fixture.observed().tls_session_count < 2 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("the next open dials a new carrier");
    reopening.abort();
}

#[tokio::test]
async fn cancelling_open_closes_only_its_pending_stream() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { synack_delay: Duration::from_millis(250), ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let opening_client = client.clone();
    let target_port = fixture.target_port();
    let opening =
        tokio::spawn(async move { opening_client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target_port).await });
    tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            if fixture.observed().syn_stream_ids == vec![1] {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("first stream SYN");

    opening.abort();
    assert!(opening.await.expect_err("open task must be cancelled").is_cancelled());

    tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            if fixture.observed().fin_stream_ids == vec![1] {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("cancelled open must close its pending stream");

    let mut next = tokio::time::timeout(
        Duration::from_secs(2),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("next open must complete")
    .expect("open next stream");
    next.write_all(b"next").await.expect("write next stream");
    assert_eq!(next.read_exact_len(4).await.expect("read next stream"), b"next");
    assert_eq!(fixture.observed().tls_session_count, 1, "a cancelled open returns its session to the pool");
}

#[tokio::test]
async fn cancelling_an_open_during_the_tls_handshake_does_not_poison_later_opens() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { tls_handshake_delay: Duration::from_millis(250), ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let first_client = client.clone();
    let target_port = fixture.target_port();
    let first =
        tokio::spawn(async move { first_client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target_port).await });
    tokio::time::timeout(Duration::from_secs(1), async {
        while fixture.observed().tls_session_count != 1 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("first carrier accepted");

    first.abort();
    assert!(first.await.expect_err("first open must be cancelled").is_cancelled());

    let mut second = tokio::time::timeout(
        Duration::from_secs(3),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("second open deadline")
    .expect("open second stream");
    second.write_all(b"shared").await.expect("write second stream");
    assert_eq!(second.read_exact_len(6).await.expect("read second stream"), b"shared");
}

/// The first session floods stream 1 and nobody reads it. The carrier reader
/// waits for that reader instead of dropping data, and a stream on another
/// session is unaffected.
#[tokio::test]
async fn unread_stream_loses_no_data_and_does_not_block_other_sessions() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { flood_first_stream_frames: 100, ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let mut unread =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("open stream");
    let mut responsive = tokio::time::timeout(
        Duration::from_secs(1),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("unread stream must not block opening another stream")
    .expect("open responsive stream");

    responsive.write_all(b"responsive").await.expect("write responsive stream");
    let echoed = tokio::time::timeout(Duration::from_secs(1), responsive.read_exact_len(10))
        .await
        .expect("unread stream must not block traffic on another session")
        .expect("read responsive stream");
    assert_eq!(echoed, b"responsive");
    assert_eq!(fixture.observed().tls_session_count, 2);

    let flood = tokio::time::timeout(Duration::from_secs(2), unread.read_exact_len(100 * b"stalled-stream".len()))
        .await
        .expect("flood deadline")
        .expect("every flooded frame is delivered");
    assert_eq!(flood, b"stalled-stream".repeat(100));
}

#[tokio::test]
async fn synack_error_closes_only_the_rejected_stream() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { reject_next_synack: Some("dial failed".to_owned()), ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let err = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect_err("synack data is an open error");

    assert!(matches!(err, AnyTlsError::StreamOpenRejected(message) if message == "dial failed"));
}

#[tokio::test]
async fn update_padding_scheme_is_persisted_for_later_sessions_to_same_server() {
    let pushed_scheme = "stop=3\n0=9-9\n1=10-10\n2=11-11";
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig {
            server_padding_scheme: Some(pushed_scheme.to_owned()),
            close_after_update: true,
            ..AnyTlsLoopbackConfig::default()
        },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let first_err = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect_err("first session closes after update");
    assert!(matches!(first_err, AnyTlsError::SessionClosed));

    let mut stream = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("second session uses pushed padding");
    stream.write_all(b"updated").await.expect("write");
    assert_eq!(stream.read_exact_len(7).await.expect("read"), b"updated");

    let observed = fixture.observed();
    let default_md5 =
        PaddingScheme::parse(DEFAULT_PADDING_SCHEME.as_bytes()).expect("default").padding_md5().to_owned();
    let pushed_md5 = PaddingScheme::parse(pushed_scheme.as_bytes()).expect("pushed").padding_md5().to_owned();
    assert_eq!(observed.settings_padding_md5, vec![default_md5, pushed_md5]);
    assert_eq!(observed.update_padding_scheme_count, 1);
}

#[tokio::test]
async fn pushed_padding_scheme_outlives_the_client_that_learned_it() {
    let pushed_scheme = "stop=3\n0=9-9\n1=10-10\n2=11-11";
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig {
            server_padding_scheme: Some(pushed_scheme.to_owned()),
            close_after_update: true,
            ..AnyTlsLoopbackConfig::default()
        },
    )
    .await
    .expect("fixture");
    let padding = SharedPaddingScheme::default();
    let first = AnyTlsClient::with_padding_scheme(client_config(&fixture, "fixture-password"), padding.clone())
        .expect("first client");
    first
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect_err("first session closes after update");
    first.close().await.expect("close first client");
    drop(first);

    let second =
        AnyTlsClient::with_padding_scheme(client_config(&fixture, "fixture-password"), padding).expect("second client");
    let mut stream = second
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("a new client of the same profile uses the pushed padding");
    stream.write_all(b"updated").await.expect("write");
    assert_eq!(stream.read_exact_len(7).await.expect("read"), b"updated");

    let pushed_md5 = PaddingScheme::parse(pushed_scheme.as_bytes()).expect("pushed").padding_md5().to_owned();
    assert_eq!(fixture.observed().settings_padding_md5.last(), Some(&pushed_md5));
}

#[tokio::test]
async fn an_unusable_pushed_padding_scheme_keeps_the_carrier() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { server_padding_scheme: Some("0=9-9".to_owned()), ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let mut stream = tokio::time::timeout(
        Duration::from_secs(5),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("open deadline")
    .expect("a scheme without stop is ignored, not fatal");
    stream.write_all(b"kept").await.expect("write");
    assert_eq!(stream.read_exact_len(4).await.expect("read"), b"kept");
    assert_eq!(fixture.observed().update_padding_scheme_count, 1);
}

#[tokio::test]
async fn heart_request_gets_response_and_alert_closes_session() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig {
            send_heart_request_after_settings: true,
            send_alert_after_first_syn: Some("upgrade required".to_owned()),
            ..AnyTlsLoopbackConfig::default()
        },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let err = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect_err("alert closes session");

    assert!(matches!(err, AnyTlsError::Alert(message) if message == "upgrade required"));
    assert_eq!(fixture.observed().heart_responses, 1);
}

#[tokio::test]
async fn udp_uses_sing_box_udp_over_tcp_v2_magic_target() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let mut udp = client.open_udp_over_tcp().await.expect("open udp-over-tcp");
    udp.send_datagram(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.udp_target_port(), b"dns")
        .await
        .expect("send udp");
    let datagram = udp.recv_datagram().await.expect("recv udp");

    assert_eq!(datagram.payload, b"dns");
    assert_eq!(fixture.observed().udp_magic_targets, vec!["sp.v2.udp-over-tcp.arpa:0".to_owned()]);
    assert_eq!(fixture.observed().udp_requests, vec![(false, "0.0.0.0:0".to_owned())]);
}

#[tokio::test]
async fn session_honors_outbound_bind_ip_and_fails_closed_on_family_mismatch() {
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");

    let mut config = client_config(&fixture, "fixture-password");
    config.outbound_bind_ip = Some(std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST));
    let client = AnyTlsClient::new(config.clone()).expect("client");
    let mut stream =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("bound connect");
    stream.write_all(b"bound").await.expect("write over bound carrier");

    // A v6 bind IP against a v4-only resolved server must fail closed before
    // any connect runs — silently ignoring the bind would route the carrier
    // over the default interface instead of the pinned one.
    config.outbound_bind_ip = Some(std::net::IpAddr::V6("::1".parse().expect("v6 loopback")));
    let failed = AnyTlsClient::new(config).expect("client");
    let error = failed
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect_err("family mismatch must fail closed");
    assert!(error.to_string().contains("outbound bind IP family"), "unexpected error: {error}");
}

/// # Cancel safety
/// Both the opening request and raw TLS peer are joined before assertions.
#[tokio::test]
async fn dropping_last_client_owner_closes_abandoned_tls_handshake() {
    abandoned_tls_handshake_is_released(false).await;
}

#[tokio::test]
async fn explicit_close_joins_abandoned_tls_handshake_while_owner_remains_live() {
    abandoned_tls_handshake_is_released(true).await;
}

async fn abandoned_tls_handshake_is_released(explicit_close: bool) {
    use tokio::io::AsyncReadExt;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("stalled TLS peer");
    let address = listener.local_addr().expect("peer address");
    let (started, observed) = tokio::sync::oneshot::channel();
    let mut peer = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.expect("accept TLS");
        let mut data = [0; 2048];
        assert!(socket.read(&mut data).await.expect("ClientHello") > 0);
        started.send(()).expect("TLS observed");
        socket.read_to_end(&mut Vec::new()).await.expect("client EOF");
    });
    let client = AnyTlsClient::new(AnyTlsClientConfig {
        server_host: address.ip().to_string(),
        server_port: address.port(),
        server_name: "outbound.invalid".into(),
        password: "loopback-test-password".into(),
        tls_fingerprint_profile: "chrome".into(),
        root_certificate_pem: None,
        client_name: "outbound-test".into(),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
    })
    .expect("client");
    let opening_client = client.clone();
    let opening =
        tokio::spawn(async move { opening_client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), 443).await });
    let started = tokio::time::timeout(Duration::from_secs(1), observed).await;
    opening.abort();
    let _ = opening.await;
    let retained = if explicit_close {
        tokio::time::timeout(Duration::from_secs(1), client.close())
            .await
            .expect("close deadline")
            .expect("join workers");
        Some(client)
    } else {
        drop(client);
        None
    };
    let closed = tokio::time::timeout(Duration::from_millis(200), &mut peer).await;
    if closed.is_err() {
        peer.abort();
        let _ = peer.await;
    }
    started.expect("TLS start deadline").expect("TLS started");
    closed.expect("abandoned TLS worker must release its socket").expect("peer task");
    if let Some(client) = retained {
        client.close().await.expect("repeat close");
        assert!(client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), 443).await.is_err());
    }
}

#[tokio::test]
async fn bridge_drop_preserves_sibling_and_client_close_joins_idle_pumps() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");
    let first = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("first")
        .into_io()
        .expect("first bridge");
    let mut second = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("second")
        .into_io()
        .expect("second bridge");
    drop(first);
    second.write_all(b"sibling").await.expect("sibling survives");
    let mut payload = [0; 7];
    tokio::time::timeout(Duration::from_secs(1), second.read_exact(&mut payload))
        .await
        .expect("echo deadline")
        .expect("echo");
    assert_eq!(&payload, b"sibling");
    tokio::time::timeout(Duration::from_secs(1), async {
        while !fixture.observed().fin_stream_ids.contains(&1) {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("dropped bridge FIN");
    client.close().await.expect("join carrier and bridges");
    // Neither application wrapper nor client is dropped: joined pumps close
    // their endpoints before the stop call can report success.
    assert_eq!(second.read(&mut payload).await.expect("closed bridge"), 0);
    client.close().await.expect("repeat joined close");
}

/// AnyTLS has no half-close. Like the reference clients, an application
/// write-shutdown closes the stream at once, so a silent target does not keep
/// the relay slot open until the idle timeout.
#[tokio::test]
async fn app_write_shutdown_sends_fin_without_waiting_for_the_target() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");
    let mut app = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("open")
        .into_io()
        .expect("bridge");
    app.write_all(b"request").await.expect("write");
    let mut reply = [0; 7];
    app.read_exact(&mut reply).await.expect("echo before shutdown");
    app.shutdown().await.expect("write shutdown");
    tokio::time::timeout(Duration::from_secs(1), async {
        while fixture.observed().fin_stream_ids != vec![1] {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("write shutdown sends FIN while the endpoint is alive");

    drop(app);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(fixture.observed().fin_stream_ids, vec![1], "drop after FIN must not send a second FIN");
}

const PAUSE_TOTAL: usize = 4 * 1024 * 1024;

/// A local reader that pauses for 3 s while the peer keeps echoing must still
/// receive every byte; the stream must not end early with a clean EOF.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn paused_reader_receives_the_whole_echo() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let fixture = AnyTlsLoopback::start("fixture-password", AnyTlsLoopbackConfig::default()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");
    let io = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("open")
        .into_io()
        .expect("io");
    let (mut read, mut write) = tokio::io::split(io);
    let writer = tokio::spawn(async move {
        let chunk = vec![0x5a_u8; 16 * 1024];
        let mut sent = 0;
        while sent < PAUSE_TOTAL {
            if write.write_all(&chunk).await.is_err() {
                break;
            }
            sent += chunk.len();
        }
        sent
    });
    tokio::time::sleep(Duration::from_secs(3)).await;
    let mut received = 0;
    let mut buffer = vec![0_u8; 64 * 1024];
    while received < PAUSE_TOTAL {
        let read_len = tokio::time::timeout(Duration::from_secs(30), read.read(&mut buffer))
            .await
            .expect("read deadline")
            .expect("no error before the echo is complete");
        assert_ne!(read_len, 0, "clean EOF after {received} of {PAUSE_TOTAL} bytes");
        assert!(buffer[..read_len].iter().all(|byte| *byte == 0x5a));
        received += read_len;
    }
    assert_eq!(writer.await.expect("writer"), PAUSE_TOTAL);
}

/// Only a peer FIN is a clean end of the stream. A carrier that dies mid-stream
/// surfaces as a reset to the application.
#[tokio::test]
async fn carrier_death_mid_stream_is_a_reset_not_a_clean_eof() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { drop_carrier_after_first_echo: true, ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");
    let mut app = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("open")
        .into_io()
        .expect("io");
    app.write_all(b"ping").await.expect("write");
    let mut echo = [0; 4];
    app.read_exact(&mut echo).await.expect("echo before the carrier dies");
    let mut rest = [0; 1];
    let end = tokio::time::timeout(Duration::from_secs(5), app.read(&mut rest)).await.expect("end deadline");
    assert_eq!(end.expect_err("carrier death must not look like EOF").kind(), std::io::ErrorKind::ConnectionReset);
}

fn v1_fixture_config() -> AnyTlsLoopbackConfig {
    AnyTlsLoopbackConfig { protocol_v1: true, ..AnyTlsLoopbackConfig::default() }
}

/// A protocol v1 server sends no ServerSettings and no SYNACK. The first open
/// waits a bounded time for ServerSettings, then returns the stream; the client
/// remembers the version, so a later fresh session does not wait again.
#[tokio::test]
async fn protocol_v1_server_tcp_open_returns_without_synack() {
    let fixture = AnyTlsLoopback::start("fixture-password", v1_fixture_config()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let started = std::time::Instant::now();
    let mut first = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("a v1 server needs no SYNACK");
    assert!(started.elapsed() < Duration::from_secs(5), "open took {:?}", started.elapsed());
    first.write_all(b"v1 echo").await.expect("write");
    assert_eq!(first.read_exact_len(7).await.expect("echo"), b"v1 echo");

    // A fresh session of the same client knows the server is v1.
    let started = std::time::Instant::now();
    let mut second =
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()).await.expect("second v1 stream");
    assert!(started.elapsed() < Duration::from_millis(1500), "learned v1 must not wait again: {:?}", started.elapsed());
    second.write_all(b"again").await.expect("write");
    assert_eq!(second.read_exact_len(5).await.expect("echo"), b"again");

    let observed = fixture.observed();
    assert_eq!(observed.tls_session_count, 2);
    assert!(observed.synack_successes.is_empty(), "a v1 server sends no SYNACK");
}

#[tokio::test]
async fn protocol_v1_server_udp_open_returns_without_synack() {
    let fixture = AnyTlsLoopback::start("fixture-password", v1_fixture_config()).await.expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let started = std::time::Instant::now();
    let mut udp = client.open_udp_over_tcp().await.expect("a v1 server needs no SYNACK");
    assert!(started.elapsed() < Duration::from_secs(5), "open took {:?}", started.elapsed());
    udp.send_datagram(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.udp_target_port(), b"dns")
        .await
        .expect("send udp");
    let datagram = udp.recv_datagram().await.expect("recv udp");
    assert_eq!(datagram.payload, b"dns");
    assert!(fixture.observed().synack_successes.is_empty());
}
