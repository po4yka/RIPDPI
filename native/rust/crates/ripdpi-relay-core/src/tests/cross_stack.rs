//! Cross-stack VLESS-over-xHTTP-over-Reality integration coverage.
//!
//! Extracted from `tests.rs` to keep that file under the per-file LoC budget
//! enforced by `scripts/ci/check_file_loc_limits.py`.

use super::*;
use crate::socks::UdpCarrier;
use std::time::Duration;

/// Cross-stack: VLESS-over-xHTTP-over-Reality, single stream. Drives the real
/// `ripdpi-xhttp` client (through the relay VLESS-Reality xHTTP backend) against
/// a loopback that emulates the whole server stack — Reality TLS, the HTTP/2
/// stream-up wire shape, and the VLESS handshake carried in the H2 bodies — then
/// proxies to its echo. A correctness regression in any one layer (Reality TLS,
/// xHTTP framing, or VLESS) breaks this even when the per-crate tests pass.
/// Closes the single-stream cross-stack criterion on
/// `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`.
///
/// # Cancel safety:
/// NOT cancel-safe: the test owns and discards the carrier after any partial I/O.
#[tokio::test]
async fn cross_stack_vless_over_xhttp_over_reality_single_stream() {
    let server = XhttpRealityLoopback::start().await.expect("start xhttp/reality fixture");

    let mut config = sample_config("vless_reality");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(server.port());
    config.common.server_name = server.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.vless_flow = "none".to_string();
    vless.reality_public_key = valid_reality_public_key();
    vless.reality_short_id = String::new();
    vless.uuid = Some("11111111-1111-1111-1111-111111111111".to_string());
    vless.xhttp_path = "/tunnel".to_string();
    vless.xhttp_mode = "stream-up".to_string();

    let backend = build_backend(&config).await.expect("build VLESS-over-xHTTP-over-Reality backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.target_port()));
    let mut stream = tokio::time::timeout(Duration::from_secs(2), backend.connect_tcp(&target))
        .await
        .expect("VLESS-over-xHTTP connect must not wait for the first downstream payload")
        .expect("connect VLESS-over-xHTTP-over-Reality");

    for payload in
        [b"cross-stack xhttp reality payload".as_slice(), b"second round-trip over the same xhttp stream".as_slice()]
    {
        stream.write_all(payload).await.expect("write cross-stack payload");
        let mut echoed = vec![0_u8; payload.len()];
        stream.read_exact(&mut echoed).await.expect("read cross-stack payload");
        assert_eq!(echoed, payload, "VLESS-over-xHTTP-over-Reality must echo byte-for-byte in both directions");
    }
}

#[tokio::test]
async fn cross_stack_vless_over_xhttp_rejects_vision_flow() {
    let mut config = sample_config("vless_reality");
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.vless_flow = "xtls-rprx-vision".to_string();
    let error = build_backend(&config).await.err().expect("Vision over xHTTP must fail before opening a socket");
    assert_eq!(io::ErrorKind::Unsupported, error.kind());
    assert!(error.to_string().contains("does not support XTLS Vision"));
}

/// Cross-stack XUDP: a UDP-enabled VLESS-over-xHTTP-over-Reality profile opens
/// one destination-less VLESS `Mux` request on an xHTTP stream and exchanges
/// XUDP datagrams with two UDP targets through it, as Xray does for UDP over a
/// non-Vision stream transport.
///
/// # Cancel safety:
/// NOT cancel-safe: the test owns and discards the association after any partial I/O.
#[tokio::test]
async fn cross_stack_xudp_over_xhttp_over_reality_reaches_two_targets() {
    let server = XhttpRealityLoopback::start().await.expect("start xhttp/reality fixture");
    let mut targets = Vec::new();
    for _ in 0..2 {
        let socket = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind UDP echo");
        targets.push(RelayTargetAddr::Ip(socket.local_addr().expect("UDP echo address")));
        tokio::spawn(async move {
            let mut buffer = [0_u8; 2048];
            while let Ok((read, peer)) = socket.recv_from(&mut buffer).await {
                let _ = socket.send_to(&buffer[..read], peer).await;
            }
        });
    }

    let mut config = sample_config("vless_reality");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(server.port());
    config.common.server_name = server.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.vless_transport = "xhttp".to_string();
    vless.vless_flow = String::new();
    vless.reality_public_key = valid_reality_public_key();
    vless.reality_short_id = String::new();
    vless.uuid = Some("11111111-1111-1111-1111-111111111111".to_string());
    vless.xhttp_path = "/tunnel".to_string();
    vless.xhttp_mode = "stream-up".to_string();

    assert!(planned_backend_capabilities(&config).udp, "UDP-enabled xHTTP profile must plan XUDP");
    let backend = build_backend(&config).await.expect("build VLESS-over-xHTTP-over-Reality backend");
    validate_runtime_config(&config, &backend).expect("XUDP-enabled xHTTP should validate");
    let mut udp = backend.open_udp_session().await.expect("open XUDP over xHTTP");
    for (target, payload) in targets.iter().zip([b"first xhttp xudp".as_slice(), b"second destination".as_slice()]) {
        UdpCarrier::send_to(&mut udp, target, payload).await.expect("send XUDP datagram");
        let (source, echoed) = tokio::time::timeout(Duration::from_secs(5), UdpCarrier::recv_from(&mut udp))
            .await
            .expect("XUDP reply deadline")
            .expect("receive XUDP datagram");
        assert_eq!(&source, target);
        assert_eq!(echoed, payload);
    }
}

/// # Cancel safety:
/// cancel-safe: the test builds a backend but never opens a carrier.
#[tokio::test]
async fn plain_vless_xhttp_plans_udp_only_when_enabled_without_flow() {
    let mut config = sample_config("vless");
    let vless = plain_vless_config_mut(&mut config);
    vless.vless_flow = String::new();
    vless.xhttp_mode = "stream-up".to_string();
    assert!(!planned_backend_capabilities(&config).udp, "UDP stays off until the profile enables it");
    config.common.udp_enabled = true;
    assert!(planned_backend_capabilities(&config).udp);
    let backend = build_backend(&config).await.expect("plain VLESS xHTTP backend");
    assert!(backend.udp_capable(), "built xHTTP factory must report XUDP");
    validate_runtime_config(&config, &backend).expect("plain VLESS XUDP profile should validate");
    plain_vless_config_mut(&mut config).vless_transport = "tcp".to_string();
    assert!(!planned_backend_capabilities(&config).udp, "only xHTTP carries plain VLESS UDP");
}
