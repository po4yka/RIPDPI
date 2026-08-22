//! QUIC path-MTU-discovery (PMTUD) regression tests for the production
//! Hysteria 2 and TUIC clients.
//!
//! Path-MTU shifts (carrier handover, VPN nesting, jumbo-frame paths) break QUIC
//! connections quietly if DPLPMTUD (RFC 8899) is misconfigured. Each test drives
//! the real protocol client against its loopback fixture whose UDP socket is a
//! `quic-mtu-test-util` `MtuDropSocket`. The flow models a mid-connection MTU
//! drop:
//!
//! 1. Connect and warm up with the socket in pass-all mode, so the client's
//!    DPLPMTUD validates a high path MTU.
//! 2. Lower the drop threshold below that MTU — the path MTU "drops" mid-flight.
//!    The client's in-flight 1-RTT data is now black-holed.
//! 3. Push a larger payload and assert it round-trips intact within a timeout —
//!    the QUIC stack must detect the black hole and probe the MTU down to recover.
//!
//! These assert *survival + payload integrity* through the real client and its
//! full framing stack after a mid-connection MTU drop. The "disabling Quinn's
//! `mtu_discovery_config` is observable" teeth (a QUIC connection can't be killed
//! by dropping only oversized datagrams — see the note in `quic-mtu-test-util`)
//! live in that crate's `pmtud_enabled_discovers_larger_path_mtu_than_disabled`.
//!
//! MASQUE/H3 uses a dedicated CONNECT-UDP fixture and checks both IPv4 and IPv6
//! black-hole recovery with exact context-0 DATAGRAM payload integrity. The
//! fixture models silent oversized-packet loss; real ICMP Packet Too Big remains
//! the responsibility of the privileged network lane.
//!
//! These run in the standard `cargo nextest run --workspace` lane.

use std::time::Duration;

use local_network_fixture::{Hysteria2Loopback, MasqueH3ConnectUdpFixture, TuicLoopback};
use quic_mtu_test_util::MtuDropSocket;
use ripdpi_masque::config::{MasqueConfig, MasqueTcpProtocol};
use ripdpi_masque::{MasqueClient, MasqueUdpRelay};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Small warm-up transfer to let DPLPMTUD validate a high path MTU before the
/// simulated drop.
const WARMUP_LEN: usize = 64 * 1024;
/// Post-drop payload — large enough to span many 1-RTT packets so a black hole
/// at the old MTU stalls until the stack probes down.
const PAYLOAD_LEN: usize = 512 * 1024;
/// Simulated post-drop path MTU. Below the loopback-validated MTU (~1452) and
/// below quinn's max, but at/above the 1200-byte QUIC base so recovery succeeds.
const DROP_THRESHOLD: usize = 1300;
/// Generous ceiling for black-hole detection + re-probe on loopback (ms in
/// practice); only a real stall (PMTUD broken) blows it.
const RECOVERY_TIMEOUT: Duration = Duration::from_secs(30);
const MASQUE_SAFE_PAYLOAD_LEN: usize = 1_000;
const RECOVERY_PAYLOAD: &[u8] = b"post-recovery-integrity";
const RECOVERY_PAYLOAD_SHA256: &str = "d506632fdf1d8cc95f5e4d1dec25150ffec318c5ad5b0483ea9fee3f4a584af8";

/// One full-duplex round-trip: write `payload` while concurrently reading its
/// echo into `recv`. The concurrency is load-bearing — writing the whole payload
/// before reading would deadlock once it exceeds the tunnel's buffers.
async fn roundtrip<R, W>(reader: &mut R, writer: &mut W, payload: &[u8], recv: &mut [u8])
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let write = async {
        writer.write_all(payload).await.expect("write payload");
        writer.flush().await.expect("flush payload");
    };
    let read = async {
        reader.read_exact(recv).await.expect("read echo");
    };
    tokio::join!(write, read);
}

#[tokio::test(flavor = "multi_thread")]
async fn hysteria2_survives_mid_connection_mtu_drop() {
    let (socket, threshold, addr) = MtuDropSocket::bind_localhost().expect("bind MTU-drop socket");
    let server = Hysteria2Loopback::start_with_socket(socket).await.expect("start Hysteria2 fixture");

    // insecure=1 trusts the fixture's self-signed cert via the client's runtime
    // flag; the loopback echoes regardless of target, so any address works.
    let url = format!("hysteria2://pmtud@127.0.0.1:{}/?sni=localhost&insecure=1", addr.port());
    let config = ripdpi_hysteria2::Config::from_url(&url).expect("hysteria2 config");
    let client = ripdpi_hysteria2::connect(&config).await.expect("hysteria2 connect");
    let stream = client.tcp_connect("127.0.0.1:9").await.expect("hysteria2 tcp_connect");
    let (mut reader, mut writer) = tokio::io::split(stream);

    // Warm up so DPLPMTUD validates a high path MTU (> DROP_THRESHOLD).
    let warm = vec![0x5A_u8; WARMUP_LEN];
    let mut warm_back = vec![0_u8; WARMUP_LEN];
    roundtrip(&mut reader, &mut writer, &warm, &mut warm_back).await;
    assert_eq!(warm_back, warm, "warm-up payload integrity");
    tokio::time::sleep(Duration::from_millis(300)).await;

    // The path MTU drops below the validated MTU mid-connection.
    threshold.set(DROP_THRESHOLD);

    let payload = vec![0xA7_u8; PAYLOAD_LEN];
    let mut back = vec![0_u8; PAYLOAD_LEN];
    tokio::time::timeout(RECOVERY_TIMEOUT, roundtrip(&mut reader, &mut writer, &payload, &mut back))
        .await
        .expect("Hysteria2 must recover from the mid-connection MTU drop via DPLPMTUD black-hole detection");
    assert_eq!(back, payload, "payload integrity after MTU recovery");

    drop((reader, writer));
    server.shutdown().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn tuic_survives_mid_connection_mtu_drop() {
    let (socket, threshold, addr) = MtuDropSocket::bind_localhost().expect("bind MTU-drop socket");
    let server = TuicLoopback::start_with_socket(socket).await.expect("start TUIC fixture");

    let config = ripdpi_tuic::Config {
        server: "127.0.0.1".to_string(),
        server_port: i32::from(addr.port()),
        server_name: "localhost".to_string(),
        uuid: "11111111-1111-1111-1111-111111111111".to_string(),
        password: "pmtud-tuic-password".to_string(),
        zero_rtt: false,
        congestion_control: "bbr".to_string(),
        udp_enabled: false,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::default(),
        outbound_bind_ip: None,
        keepalive_interval_ms: 0,
        // Pin the fixture's self-signed cert; TLS verification stays ON.
        root_certificate_pem: Some(server.certificate_pem().to_string()),
    };
    let client = ripdpi_tuic::TuicClient::connect(config).await.expect("tuic connect");
    let stream = client.tcp_connect("127.0.0.1:9").await.expect("tuic tcp_connect");
    let (mut reader, mut writer) = tokio::io::split(stream);

    let warm = vec![0x5A_u8; WARMUP_LEN];
    let mut warm_back = vec![0_u8; WARMUP_LEN];
    roundtrip(&mut reader, &mut writer, &warm, &mut warm_back).await;
    assert_eq!(warm_back, warm, "warm-up payload integrity");
    tokio::time::sleep(Duration::from_millis(300)).await;

    threshold.set(DROP_THRESHOLD);

    let payload = vec![0xA7_u8; PAYLOAD_LEN];
    let mut back = vec![0_u8; PAYLOAD_LEN];
    tokio::time::timeout(RECOVERY_TIMEOUT, roundtrip(&mut reader, &mut writer, &payload, &mut back))
        .await
        .expect("TUIC must recover from the mid-connection MTU drop via DPLPMTUD black-hole detection");
    assert_eq!(back, payload, "payload integrity after MTU recovery");

    drop((reader, writer));
    server.shutdown().await;
}

fn masque_fixture_config(fixture: &MasqueH3ConnectUdpFixture) -> MasqueConfig {
    MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: Some(fixture.proxy_address()),
        tcp_protocol: MasqueTcpProtocol::Http3,
        use_http2_fallback: false,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(fixture.certificate_pem().to_string()),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    }
}

/// Send one DATAGRAM and require its exact context-0 echo.
///
/// # Cancel safety
///
/// NOT cancel-safe: cancelling after send may leave the echo queued. Each PMTUD
/// scenario owns one flow and drives it through joined fixture shutdown.
async fn masque_echo(udp: &mut MasqueUdpRelay, target: &str, payload: &[u8]) {
    udp.send_to(target, payload).await.expect("send MASQUE H3 DATAGRAM");
    let (echo_target, echoed) = udp.recv_from().await.expect("receive MASQUE H3 DATAGRAM echo");
    assert_eq!(echo_target, target);
    assert_eq!(echoed, payload);
}

/// Exercise production MASQUE/H3 until Quinn validates a high MTU, inject a
/// mid-connection black hole, and prove the path falls back while base-sized
/// DATAGRAMs retain exact payload integrity.
///
/// # Cancel safety
///
/// NOT cancel-safe: sequential loss/recovery observations, evidence emission,
/// and joined fixture shutdown form one transaction.
async fn run_masque_h3_black_hole(
    socket: std::sync::Arc<MtuDropSocket>,
    threshold: quic_mtu_test_util::MtuThreshold,
    case_id: &str,
    target_family: &str,
) {
    let fixture = if target_family == "ipv6" {
        MasqueH3ConnectUdpFixture::start_with_socket_ipv6_target(socket.clone())
    } else {
        MasqueH3ConnectUdpFixture::start_with_socket(socket.clone())
    }
    .expect("start H3 PMTUD fixture");
    let client = MasqueClient::new(masque_fixture_config(&fixture)).expect("MASQUE client");
    let target = fixture.udp_target().to_string();
    assert_eq!(target.parse::<std::net::SocketAddr>().expect("fixture target").is_ipv6(), target_family == "ipv6");
    let mut udp = client.udp_session();
    let safe_payload = vec![0x39; MASQUE_SAFE_PAYLOAD_LEN];

    let discovery_deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    let mut pre_mtu = None;
    loop {
        masque_echo(&mut udp, &target, &safe_payload).await;
        let snapshot = udp.quic_path_snapshot(&target).expect("H3 QUIC path snapshot");
        pre_mtu.get_or_insert(snapshot.current_mtu);
        if snapshot.current_mtu >= 1_400 {
            break;
        }
        assert!(tokio::time::Instant::now() < discovery_deadline, "MASQUE H3 did not discover a high path MTU");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    let before = udp.quic_path_snapshot(&target).expect("pre-cliff path snapshot");
    let pre_mtu = pre_mtu.expect("initial path MTU observation");
    assert!(pre_mtu >= 1_200 && pre_mtu <= before.current_mtu);
    assert!(before.current_mtu >= 1_400);
    let max_datagram = before.max_datagram_size.expect("negotiated H3 DATAGRAM size");
    let lossy_payload = vec![0xD7; max_datagram.saturating_sub(2)];
    threshold.set(DROP_THRESHOLD);

    let recovery_deadline = tokio::time::Instant::now() + RECOVERY_TIMEOUT;
    let recovered = loop {
        match udp.send_to(&target, &lossy_payload).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::InvalidInput => {}
            Err(error) => panic!("unexpected MASQUE loss injection error: {error}"),
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
        masque_echo(&mut udp, &target, &safe_payload).await;
        let snapshot = udp.quic_path_snapshot(&target).expect("post-cliff path snapshot");
        if snapshot.black_holes_detected > before.black_holes_detected && snapshot.current_mtu <= DROP_THRESHOLD as u16
        {
            break snapshot;
        }
        assert!(tokio::time::Instant::now() < recovery_deadline, "MASQUE H3 did not detect the MTU black hole");
        tokio::time::sleep(Duration::from_millis(50)).await;
    };

    assert!(recovered.lost_packets > before.lost_packets, "the injected cliff must produce observable packet loss");
    assert!(
        recovered.lost_plpmtud_probes >= before.lost_plpmtud_probes,
        "PLPMTUD loss telemetry must remain monotonic",
    );
    let evidence = socket.evidence();
    let oversized_drop_delta = evidence.dropped_rx + evidence.dropped_tx;
    let black_hole_delta = recovered.black_holes_detected - before.black_holes_detected;
    assert!(oversized_drop_delta > 0, "fault injector must record redacted drop evidence");
    assert!(black_hole_delta > 0, "black-hole telemetry must increase");
    assert!(
        evidence.max_dropped_rx_len.max(evidence.max_dropped_tx_len) > DROP_THRESHOLD,
        "drop evidence must cross the configured cliff",
    );
    masque_echo(&mut udp, &target, RECOVERY_PAYLOAD).await;
    assert!(fixture.echoed_datagram_count() > 0, "fixture target path must echo datagrams");
    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert!(observed[0].accepted, "CONNECT-UDP target must match the fixture echo target");
    println!(
        "PMTUD_MEASUREMENT {}",
        serde_json::json!({
            "blackHoleDelta": black_hole_delta,
            "caseId": case_id,
            "highMtu": before.current_mtu,
            "integrity": true,
            "oversizedDropDelta": oversized_drop_delta,
            "payloadLength": RECOVERY_PAYLOAD.len(),
            "payloadSha256": RECOVERY_PAYLOAD_SHA256,
            "postCliffMtu": recovered.current_mtu,
            "preMtu": pre_mtu,
            "targetFamily": target_family,
            "version": "pmtud_measurement_v1",
        })
    );
    drop(udp);
    drop(client);
    fixture.shutdown().await;
}

/// # Cancel safety
///
/// NOT cancel-safe: recovery evidence and joined fixture cleanup must complete.
#[tokio::test(flavor = "multi_thread")]
async fn masque_h3_recovers_from_mid_connection_mtu_black_hole_ipv4() {
    let (socket, threshold, _address) = MtuDropSocket::bind_localhost().expect("bind IPv4 MTU-drop socket");
    run_masque_h3_black_hole(socket, threshold, "masque_h3_black_hole_recovery_ipv4", "ipv4").await;
}

/// # Cancel safety
///
/// NOT cancel-safe: recovery evidence and joined fixture cleanup must complete.
#[tokio::test(flavor = "multi_thread")]
async fn masque_h3_recovers_from_mid_connection_mtu_black_hole_ipv6() {
    let (socket, threshold, _address) = MtuDropSocket::bind_localhost_v6().expect("bind IPv6 MTU-drop socket");
    run_masque_h3_black_hole(socket, threshold, "masque_h3_black_hole_recovery_ipv6_target", "ipv6").await;
}
