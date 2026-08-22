use std::net::Ipv4Addr;
use std::time::Duration;

use local_network_fixture::{AnyTlsLoopback, AnyTlsLoopbackConfig};
use ripdpi_anytls::DEFAULT_PADDING_SCHEME;
use ripdpi_anytls::padding::PaddingScheme;
use ripdpi_anytls::session::{AnyTlsClient, AnyTlsClientConfig, AnyTlsError, TargetAddr};
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

    assert!(matches!(err, AnyTlsError::AuthenticationRejected | AnyTlsError::SessionClosed));
}

#[tokio::test]
async fn settings_synack_tcp_echo_and_multiplexing_share_one_tls_session() {
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
    assert_eq!(observed.tls_session_count, 1, "new streams must reuse the newest idle AnyTLS session");
    assert_eq!(observed.settings_padding_md5.len(), 1);
    assert_eq!(observed.syn_stream_ids, vec![1, 2]);
    assert_eq!(observed.synack_successes, vec![1, 2]);
    assert_eq!(observed.tcp_targets.len(), 2);
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

    // The stream is alive before the half-close.
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

    // Dropping after a successful half-close must not queue a second FIN for
    // the same substream.
    drop(stream);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(fixture.observed().fin_stream_ids, vec![1], "drop must not duplicate the FIN");
}

#[tokio::test]
async fn concurrent_opens_share_one_in_flight_tls_session() {
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
    assert_eq!(observed.tls_session_count, 1, "concurrent opens must share one in-flight carrier");
    assert_eq!(observed.syn_stream_ids, vec![1, 2]);
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
    assert_eq!(fixture.observed().tls_session_count, 1, "cancellation must not discard the shared carrier");
}

#[tokio::test]
async fn cancelling_first_waiter_does_not_cancel_shared_establishment() {
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
        loop {
            if fixture.observed().tls_session_count == 1 {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("first carrier accepted");

    first.abort();
    assert!(first.await.expect_err("first waiter must be cancelled").is_cancelled());

    let mut second = tokio::time::timeout(
        Duration::from_secs(2),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("second waiter must observe shared establishment")
    .expect("open second stream");
    second.write_all(b"shared").await.expect("write second stream");
    assert_eq!(second.read_exact_len(6).await.expect("read second stream"), b"shared");
    assert_eq!(fixture.observed().tls_session_count, 1, "caller cancellation must not restart the carrier");
}

#[tokio::test]
async fn unread_stream_does_not_block_other_streams_on_the_same_session() {
    let fixture = AnyTlsLoopback::start(
        "fixture-password",
        AnyTlsLoopbackConfig { flood_first_stream_frames: 33, ..AnyTlsLoopbackConfig::default() },
    )
    .await
    .expect("fixture");
    let client = AnyTlsClient::new(client_config(&fixture, "fixture-password")).expect("client");

    let _unread = client
        .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port())
        .await
        .expect("open unread stream");
    let mut responsive = tokio::time::timeout(
        Duration::from_secs(1),
        client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), fixture.target_port()),
    )
    .await
    .expect("unread stream must not block opening a sibling stream")
    .expect("open responsive stream");

    responsive.write_all(b"responsive").await.expect("write responsive stream");
    let echoed = tokio::time::timeout(Duration::from_secs(1), responsive.read_exact_len(10))
        .await
        .expect("unread stream must not block sibling traffic")
        .expect("read responsive stream");
    assert_eq!(echoed, b"responsive");
    assert_eq!(fixture.observed().tls_session_count, 1, "both streams must share one carrier");
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
