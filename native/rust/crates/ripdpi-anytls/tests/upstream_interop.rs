//! Public client against the pinned, unmodified anytls-go server implementation.
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use ripdpi_anytls::session::{AnyTlsClient, AnyTlsClientConfig, TargetAddr};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn config(password: &str) -> AnyTlsClientConfig {
    let endpoint = endpoint("RIPDPI_OUTBOUND_INTEROP_ENDPOINT");
    AnyTlsClientConfig {
        server_host: endpoint.ip().to_string(),
        server_port: endpoint.port(),
        server_name: "outbound.invalid".into(),
        password: password.into(),
        tls_fingerprint_profile: "chrome".into(),
        root_certificate_pem: Some(
            std::fs::read_to_string(std::env::var("RIPDPI_OUTBOUND_CERTIFICATE").expect("oracle certificate path"))
                .expect("oracle certificate"),
        ),
        client_name: "ripdpi-outbound-interop".into(),
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        outbound_bind_ip: None,
    }
}

fn endpoint(name: &str) -> SocketAddr {
    let endpoint: SocketAddr =
        std::env::var(name).expect("run through upstream oracle script").parse().expect("loopback endpoint");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    endpoint
}

#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn tcp_stream_exchanges_payload_with_upstream() {
    tokio::time::timeout(Duration::from_secs(12), async {
        let client = AnyTlsClient::new(config("loopback-test-password")).expect("client");
        let target = endpoint("RIPDPI_OUTBOUND_TCP");
        let mut stream =
            client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target.port()).await.expect("upstream TCP");
        let payload: Vec<u8> = (0..65536).map(|index| (index % 251) as u8).collect();
        stream.write_all(&payload).await.expect("write");
        assert_eq!(stream.read_exact_len(payload.len()).await.expect("echo"), payload);
        stream.close().await.expect("close");
        drop(stream);
        let first = client
            .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target.port())
            .await
            .expect("first multiplexed stream")
            .into_io()
            .expect("first bridge");
        let mut second = client
            .open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target.port())
            .await
            .expect("second multiplexed stream")
            .into_io()
            .expect("second bridge");
        drop(first);
        second.write_all(&payload).await.expect("write surviving sibling");
        let mut echoed = vec![0; payload.len()];
        second.read_exact(&mut echoed).await.expect("forwarded sibling payload");
        assert_eq!(echoed, payload);
        client.close().await.expect("join live carrier and bridge");
        assert_eq!(second.read(&mut echoed).await.expect("joined bridge EOF"), 0);
    })
    .await
    .expect("upstream TCP deadline");
}

#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn udp_datagrams_exchange_with_upstream() {
    tokio::time::timeout(Duration::from_secs(12), async {
        let client = AnyTlsClient::new(config("loopback-test-password")).expect("client");
        let target = endpoint("RIPDPI_OUTBOUND_UDP");
        let mut stream = client.open_udp_over_tcp().await.expect("upstream UDP association");
        for length in [1, 1200, 8192, 0] {
            let payload: Vec<u8> = (0..length).map(|index| (index % 251) as u8).collect();
            stream
                .send_datagram(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target.port(), &payload)
                .await
                .expect("send datagram");
            let received =
                stream.recv_datagram().await.unwrap_or_else(|error| panic!("receive {length}-byte datagram: {error}"));
            assert_eq!(received.target, TargetAddr::Ipv4(Ipv4Addr::LOCALHOST));
            assert_eq!(received.port, target.port());
            assert_eq!(received.payload, payload);
        }
        client.close().await.expect("join UDP association carrier");
    })
    .await
    .expect("upstream UDP deadline");
}

#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn upstream_rejects_wrong_password() {
    tokio::time::timeout(Duration::from_secs(12), async {
        let client = AnyTlsClient::new(config("incorrect-test-password")).expect("client");
        let target = endpoint("RIPDPI_OUTBOUND_TCP");
        assert!(client.open_tcp(TargetAddr::Ipv4(Ipv4Addr::LOCALHOST), target.port()).await.is_err());
        client.close().await.expect("join rejected carrier");
    })
    .await
    .expect("upstream authentication failure deadline");
}
