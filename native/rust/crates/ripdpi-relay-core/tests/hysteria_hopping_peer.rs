//! Independent Hysteria release peer, driven through the Android JSON boundary.
//! Run with test-lab/acceptance/peers/run.py; peer credentials are ephemeral.
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use ripdpi_relay_core::{RelayRuntime, ResolvedRelayRuntimeConfig};
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

fn required(name: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| panic!("missing independent peer input: {name}"))
}

/// # Cancel safety:
/// NOT cancel-safe: read_exact and write_all can make partial progress.
/// The caller drops this owned stream after cancellation; it never retries it.
async fn socks(listener: &str, command: u8, target: SocketAddr) -> io::Result<(TcpStream, SocketAddr)> {
    let mut stream = TcpStream::connect(listener).await?;
    stream.write_all(&[5, 1, 0]).await?;
    let mut greeting = [0; 2];
    stream.read_exact(&mut greeting).await?;
    assert_eq!(greeting, [5, 0]);
    let SocketAddr::V4(target) = target else { panic!("destination is IPv4 loopback") };
    let mut request = vec![5, command, 0, 1];
    request.extend_from_slice(&target.ip().octets());
    request.extend_from_slice(&target.port().to_be_bytes());
    stream.write_all(&request).await?;
    let mut response = [0; 10];
    stream.read_exact(&mut response).await?;
    assert_eq!(&response[..4], &[5, 0, 0, 1], "SOCKS operation must succeed");
    let bound = SocketAddr::from((
        Ipv4Addr::new(response[4], response[5], response[6], response[7]),
        u16::from_be_bytes([response[8], response[9]]),
    ));
    Ok((stream, bound))
}

fn runtime_config() -> ResolvedRelayRuntimeConfig {
    let endpoint: SocketAddr = required("RIPDPI_HYSTERIA_ENDPOINT").parse().expect("endpoint");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    let obfs = required("RIPDPI_HYSTERIA_SALAMANDER");
    // These are the serialized RelayNativeConfig fields, including the version
    // and strict fingerprint field required by the native activation boundary.
    let specimen = json!({
        "schemaVersion": 10, "enabled": true, "kind": "hysteria2", "profileId": "independent-hopping",
        "server": endpoint.ip().to_string(), "serverPort": endpoint.port(), "serverName": "localhost",
        "localSocksHost": "127.0.0.1", "localSocksPort": 0, "udpEnabled": true, "tcpFallbackEnabled": true,
        "tlsFingerprintProfile": "chrome_stable", "hysteriaPassword": required("RIPDPI_HYSTERIA_AUTH"),
        "hysteriaInsecure": true, "hysteriaSalamanderKey": if obfs.is_empty() { None } else { Some(obfs) },
        "hysteriaPortHopPorts": required("RIPDPI_HYSTERIA_HOP_PORTS"),
        "hysteriaPortHopIntervalMillis": 5000,
        "hysteriaPortHopIntervalMaxMillis": required("RIPDPI_HYSTERIA_HOP_MAX_MILLIS").parse::<u64>().expect("max interval")
    });
    let mut wire = match std::env::var_os("RIPDPI_HYSTERIA_WIRE_FIXTURE") {
        Some(path) => {
            let bytes = std::fs::read(path).expect("requested Kotlin serializer fixture must exist");
            let value = serde_json::from_slice(&bytes).expect("Kotlin serializer fixture must be valid JSON");
            println!("wire input: kotlin serializer");
            value
        }
        None => specimen.clone(),
    };
    let _: ResolvedRelayRuntimeConfig = serde_json::from_value(wire.clone()).expect("unmodified wire shape");
    assert_eq!(wire["kind"], "hysteria2");
    assert_eq!(wire["enabled"], true);
    assert_eq!(wire["udpEnabled"], true);
    assert_eq!(wire["hysteriaPortHopPorts"], specimen["hysteriaPortHopPorts"]);
    assert_eq!(wire["hysteriaPortHopIntervalMillis"], specimen["hysteriaPortHopIntervalMillis"]);
    assert_eq!(
        wire["hysteriaPortHopIntervalMaxMillis"].as_u64().unwrap_or(5000),
        specimen["hysteriaPortHopIntervalMaxMillis"].as_u64().unwrap()
    );
    // Preserve the exact serialized policy. Only bind/auth/TLS inputs belong to
    // this ephemeral peer; a requested fixture never falls back to the specimen.
    for field in [
        "server",
        "serverPort",
        "serverName",
        "localSocksHost",
        "localSocksPort",
        "hysteriaPassword",
        "hysteriaSalamanderKey",
        "hysteriaPinSha256",
    ] {
        wire[field] = specimen[field].clone();
    }
    serde_json::from_slice::<ResolvedRelayRuntimeConfig>(&serde_json::to_vec(&wire).expect("JSON serialization"))
        .expect("Android wire deserialization")
}

/// # Cancel safety:
/// Conditionally cancel-safe: the outer deadline drops both SOCKS streams,
/// then stop joins the relay runtime. The subprocess runner bounds a test panic.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires the pinned independent Hysteria peer runner"]
async fn relay_json_preserves_tcp_and_udp_across_remote_hops() {
    let config = runtime_config();
    let runtime = RelayRuntime::new(config);
    let run = tokio::spawn(Arc::clone(&runtime).run());
    let outcome = tokio::time::timeout(Duration::from_secs(40), async {
        let listener = loop {
            if let Some(address) = runtime.telemetry().listener_address {
                break address;
            }
            assert!(!run.is_finished(), "runtime failed before readiness");
            tokio::time::sleep(Duration::from_millis(10)).await;
        };
        let tcp_target = required("RIPDPI_HYSTERIA_TCP").parse().expect("TCP target");
        let udp_target: SocketAddr = required("RIPDPI_HYSTERIA_UDP").parse().expect("UDP target");
        let (mut tcp, _) = socks(&listener, 1, tcp_target).await.expect("TCP CONNECT");
        let (udp_control, udp_relay) = socks(&listener, 3, "0.0.0.0:0".parse().unwrap()).await.expect("UDP ASSOCIATE");
        let udp = UdpSocket::bind("127.0.0.1:0").await.expect("UDP bind");
        let payload = required("RIPDPI_HYSTERIA_PAYLOAD").into_bytes();
        assert_eq!(payload.len(), 8208, "fixed TCP and aggregate UDP payload size");
        let SocketAddr::V4(udp_target) = udp_target else { panic!("IPv4 destination") };
        let mut packet = vec![0, 0, 0, 1];
        packet.extend_from_slice(&udp_target.ip().octets());
        packet.extend_from_slice(&udp_target.port().to_be_bytes());
        packet.extend_from_slice(&payload[..2736]);
        let mut tick = tokio::time::interval(Duration::from_secs(1));
        for round in 0u16..20 {
            tick.tick().await;
            tcp.write_all(&payload).await.expect("TCP uplink on same stream");
            let mut echoed = vec![0; payload.len()];
            tokio::time::timeout(Duration::from_secs(3), tcp.read_exact(&mut echoed))
                .await
                .expect("TCP echo deadline")
                .expect("TCP echo");
            assert_eq!(echoed, payload);
            // The pinned peer limits UDP replies to a 4096-byte buffer including
            // its header. Each packet still requires several QUIC fragments.
            for index in 0u16..3 {
                packet[10..12].copy_from_slice(&round.to_be_bytes());
                packet[12..14].copy_from_slice(&index.to_be_bytes());
                udp.send_to(&packet, udp_relay).await.expect("UDP uplink on same association");
                let mut received = [0; 65535];
                let (count, _) = tokio::time::timeout(Duration::from_secs(3), udp.recv_from(&mut received))
                    .await
                    .expect("UDP echo deadline")
                    .expect("UDP echo");
                assert_eq!(&received[..count], packet.as_slice());
            }
        }
        assert_eq!(runtime.telemetry().active_sessions, 2, "same SOCKS sessions remain active");
        // Keep both sessions alive through stop so shutdown must drain them.
        (tcp, udp_control, udp)
    })
    .await;
    runtime.stop();
    tokio::time::timeout(Duration::from_secs(8), run)
        .await
        .expect("bounded runtime shutdown")
        .expect("runtime join")
        .expect("runtime shutdown");
    assert_eq!(runtime.telemetry().active_sessions, 0);
    assert!(outcome.is_ok(), "payload flow deadline exceeded");
}
