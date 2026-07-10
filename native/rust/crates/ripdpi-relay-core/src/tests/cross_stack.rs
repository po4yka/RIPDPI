//! Cross-stack VLESS-over-xHTTP-over-Reality integration coverage.
//!
//! Extracted from `tests.rs` to keep that file under the per-file LoC budget
//! enforced by `scripts/ci/check_file_loc_limits.py`.

use super::*;

/// Cross-stack: VLESS-over-xHTTP-over-Reality, single stream. Drives the real
/// `ripdpi-xhttp` client (through the relay VLESS-Reality xHTTP backend) against
/// a loopback that emulates the whole server stack — Reality TLS, the HTTP/2
/// stream-up wire shape, and the VLESS handshake carried in the H2 bodies — then
/// proxies to its echo. A correctness regression in any one layer (Reality TLS,
/// xHTTP framing, or VLESS) breaks this even when the per-crate tests pass.
/// Closes the single-stream cross-stack criterion on
/// `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`.
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

    let backend = build_backend(&config).await.expect("build VLESS-over-xHTTP-over-Reality backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("connect VLESS-over-xHTTP-over-Reality");

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
