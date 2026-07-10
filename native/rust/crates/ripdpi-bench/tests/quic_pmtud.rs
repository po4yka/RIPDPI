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
//! MASQUE is intentionally absent: its only loopback fixture is H2-CONNECT over
//! TCP (no QUIC datapath, so PMTUD does not apply); a quinn/H3 MASQUE fixture is
//! tracked as deferred in the task issue.
//!
//! These run in the standard `cargo nextest run --workspace` lane.

use std::time::Duration;

use local_network_fixture::{Hysteria2Loopback, TuicLoopback};
use quic_mtu_test_util::MtuDropSocket;
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
