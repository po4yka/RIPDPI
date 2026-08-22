use std::net::{IpAddr, Ipv4Addr};

use local_network_fixture::TrojanLoopback;
use ripdpi_trojan::{TrojanAddr, TrojanClient, TrojanClientConfig, TrojanError};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

const PASSWORD: &str = "correct horse battery staple";
const FIRST_PAYLOAD: &[u8] = b"trojan connect first payload";

#[tokio::test(flavor = "multi_thread")]
async fn tls_client_sends_connect_request_and_pipes_payload_to_target() {
    let fixture = TrojanLoopback::start(PASSWORD).await.expect("start trojan fixture");
    let config = TrojanClientConfig {
        server_host: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        password: PASSWORD.to_owned(),
        tls_fingerprint_profile: "chrome_stable".to_owned(),
        root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
    };
    let target = TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST);

    let mut stream = TrojanClient::connect_tcp(&config, &target, fixture.target_port(), FIRST_PAYLOAD)
        .await
        .expect("connect over trojan tls");

    let mut echoed_first_payload = vec![0_u8; FIRST_PAYLOAD.len()];
    stream.read_exact(&mut echoed_first_payload).await.expect("read first echoed payload");
    assert_eq!(echoed_first_payload, FIRST_PAYLOAD);

    let second_payload = b"second payload crosses established tunnel";
    stream.write_all(second_payload).await.expect("write second payload");
    let mut echoed_second_payload = vec![0_u8; second_payload.len()];
    stream.read_exact(&mut echoed_second_payload).await.expect("read second echoed payload");
    assert_eq!(echoed_second_payload, second_payload);

    let observed = fixture.observed();
    assert_eq!(observed.sni.as_deref(), Some(fixture.server_name()));
    assert_eq!(observed.alpn.as_deref(), Some("http/1.1"));
    assert_eq!(observed.command, Some(0x01));
    assert_eq!(observed.target_addr, Some(IpAddr::V4(Ipv4Addr::LOCALHOST)));
    assert_eq!(observed.target_port, Some(fixture.target_port()));
    assert_eq!(observed.initial_payload_len, FIRST_PAYLOAD.len());
}

#[tokio::test(flavor = "multi_thread")]
async fn tls_client_can_connect_over_existing_transport() {
    let fixture = TrojanLoopback::start(PASSWORD).await.expect("start trojan fixture");
    let config = TrojanClientConfig {
        server_host: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        password: PASSWORD.to_owned(),
        tls_fingerprint_profile: "chrome_stable".to_owned(),
        root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
    };
    let transport = TcpStream::connect(("127.0.0.1", fixture.port())).await.expect("connect fixture transport");
    let target = TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST);

    let mut stream = TrojanClient::connect_tcp_over(&config, transport, &target, fixture.target_port(), FIRST_PAYLOAD)
        .await
        .expect("connect over existing transport");

    let mut echoed_first_payload = vec![0_u8; FIRST_PAYLOAD.len()];
    stream.read_exact(&mut echoed_first_payload).await.expect("read first echoed payload");
    assert_eq!(echoed_first_payload, FIRST_PAYLOAD);
}

#[tokio::test(flavor = "multi_thread")]
async fn tls_client_honors_outbound_bind_ip_and_fails_closed_on_family_mismatch() {
    let fixture = TrojanLoopback::start(PASSWORD).await.expect("start trojan fixture");
    let target = TrojanAddr::Ipv4(Ipv4Addr::LOCALHOST);

    let mut config = TrojanClientConfig {
        server_host: "127.0.0.1".to_owned(),
        server_port: fixture.port(),
        server_name: fixture.server_name().to_owned(),
        password: PASSWORD.to_owned(),
        tls_fingerprint_profile: "chrome_stable".to_owned(),
        root_certificate_pem: Some(fixture.certificate_pem().to_owned()),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: Some(IpAddr::V4(Ipv4Addr::LOCALHOST)),
    };

    let mut stream = TrojanClient::connect_tcp(&config, &target, fixture.target_port(), FIRST_PAYLOAD)
        .await
        .expect("connect bound to loopback");
    let mut echoed_first_payload = vec![0_u8; FIRST_PAYLOAD.len()];
    stream.read_exact(&mut echoed_first_payload).await.expect("read first echoed payload");
    assert_eq!(echoed_first_payload, FIRST_PAYLOAD);

    // A v6 bind IP against a v4-only resolved server must fail closed before
    // any connect runs — silently ignoring the bind would route the carrier
    // over the default interface instead of the pinned one.
    config.outbound_bind_ip = Some(IpAddr::V6("::1".parse().expect("v6 loopback")));
    let error = TrojanClient::connect_tcp(&config, &target, fixture.target_port(), FIRST_PAYLOAD)
        .await
        .expect_err("family mismatch must fail closed");
    let TrojanError::Io(io_error) = error else {
        panic!("expected an Io error for the family mismatch, got {error:?}");
    };
    assert_eq!(io_error.kind(), std::io::ErrorKind::AddrNotAvailable);
}
