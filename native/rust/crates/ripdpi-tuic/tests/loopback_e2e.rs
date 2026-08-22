//! End-to-end regression for the public `Config::root_certificate_pem` trust
//! anchor: pinning a self-signed TUIC server cert lets the real client connect
//! with TLS verification left ON, and an unrelated pinned cert must FAIL — i.e.
//! the option pins trust, it does not disable verification.

use local_network_fixture::TuicLoopback;
use ripdpi_tuic::{Config, TuicClient};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn config(port: u16, root_certificate_pem: Option<String>) -> Config {
    Config {
        server: "127.0.0.1".to_owned(),
        server_port: i32::from(port),
        server_name: "localhost".to_owned(),
        uuid: "11111111-1111-1111-1111-111111111111".to_owned(),
        password: "tuic-test-password".to_owned(),
        zero_rtt: false,
        congestion_control: "bbr".to_owned(),
        udp_enabled: false,
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
        keepalive_interval_ms: 0,
        root_certificate_pem,
    }
}

#[tokio::test]
async fn tuic_client_with_pinned_root_certificate_verifies_and_tunnels() {
    let server = TuicLoopback::start().await.expect("start TUIC fixture");
    let config = config(server.port(), Some(server.certificate_pem().to_string()));

    let client = TuicClient::connect(config).await.expect("pinned-cert handshake must verify");
    let Err(udp_error) = client.udp_session().await else {
        panic!("disabled UDP must fail before creating a black-holed session");
    };
    assert_eq!(udp_error.kind(), std::io::ErrorKind::Unsupported);
    assert!(udp_error.to_string().contains("disabled by configuration"));
    let mut stream = client.tcp_connect("127.0.0.1:9").await.expect("open proxy stream");

    stream.write_all(b"tuic-roundtrip").await.expect("write proxied payload");
    stream.flush().await.expect("flush proxied payload");
    let mut echoed = [0u8; 14];
    stream.read_exact(&mut echoed).await.expect("read echoed payload");
    assert_eq!(&echoed, b"tuic-roundtrip");

    server.shutdown().await;
}

#[tokio::test]
async fn tuic_client_with_unrelated_root_certificate_fails_verification() {
    let server = TuicLoopback::start().await.expect("start TUIC fixture");
    // A different fixture's self-signed cert: syntactically valid, but not the
    // cert `server` presents — verification must reject it.
    let other = TuicLoopback::start().await.expect("start second TUIC fixture");
    let config = config(server.port(), Some(other.certificate_pem().to_string()));

    let result = TuicClient::connect(config).await;
    assert!(result.is_err(), "pinning an unrelated cert must fail TLS verification, not succeed");
}
