//! Real upstream interoperability; invoked by scripts/tests/run-outbound-interop.py.

use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_mieru::{MieruClient, MieruConfig, MieruMux, MieruMuxConnection, MieruProtocol};
use ripdpi_network_time::NetworkTimeProvider;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

/// # Cancel safety
/// Cancel-safe: the test owns and drops the transport and relayed stream.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn tcp_stream_exchanges_payload_with_upstream() {
    let endpoint: SocketAddr = std::env::var("RIPDPI_OUTBOUND_INTEROP_ENDPOINT")
        .expect("run through the upstream oracle script")
        .parse()
        .expect("peer socket address");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    let config = MieruConfig {
        server: endpoint.ip().to_string(),
        port: endpoint.port(),
        username: "outbound-interop".into(),
        password: "loopback-test-password".into(),
        protocol: MieruProtocol::Tcp,
        multiplexing: MieruMux::Off,
        mtu: 1400,
    };
    timeout(Duration::from_secs(12), async {
        let transport = TcpStream::connect(endpoint).await.expect("connect upstream loopback listener");
        let client = MieruClient::connect_over(transport, &config, NetworkTimeProvider::shared())
            .await
            .expect("open upstream session");
        let mut stream = client.tcp_connect("interop.invalid:443").await.expect("upstream SOCKS CONNECT");
        let payload: Vec<u8> = (0..65536).map(|index| (index % 251) as u8).collect();
        stream.write_all(&payload).await.expect("upstream write");
        let mut received = vec![0; payload.len()];
        stream.read_exact(&mut received).await.expect("upstream read");
        assert_eq!(received, payload, "upstream payload must remain byte exact across segments");
        stream.close().await.expect("join direct carrier");
    })
    .await
    .expect("upstream TCP exchange deadline");
}

/// # Cancel safety
/// Cancel-safe: the connection owns the carrier; the test drops all streams on timeout.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn multiplexed_tcp_streams_exchange_without_cross_contamination() {
    let endpoint: SocketAddr = std::env::var("RIPDPI_OUTBOUND_INTEROP_ENDPOINT")
        .expect("run through the upstream oracle script")
        .parse()
        .expect("peer socket address");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    for level in [MieruMux::Low, MieruMux::Middle, MieruMux::High] {
        let config = MieruConfig {
            server: endpoint.ip().to_string(),
            port: endpoint.port(),
            username: "outbound-interop".into(),
            password: "loopback-test-password".into(),
            protocol: MieruProtocol::Tcp,
            multiplexing: level,
            mtu: 1400,
        };
        timeout(Duration::from_secs(12), async {
            let transport = TcpStream::connect(endpoint).await.expect("connect upstream loopback listener");
            let connection = MieruMuxConnection::connect_over(transport, &config, NetworkTimeProvider::shared())
                .await
                .expect("open multiplexed upstream carrier");
            let (first, second) = tokio::join!(
                connection.open_stream("interop.invalid:443"),
                connection.open_stream("interop.invalid:443"),
            );
            let mut first = first.expect("first upstream multiplexed CONNECT");
            let mut second = second.expect("second upstream multiplexed CONNECT");
            let first_payload = vec![0x5a; 65536];
            let second_payload = vec![0xa5; 65536];
            tokio::join!(
                async {
                    first.write_all(&first_payload).await.expect("first stream write");
                    let mut received = vec![0; first_payload.len()];
                    first.read_exact(&mut received).await.expect("first stream read");
                    assert_eq!(received, first_payload);
                },
                async {
                    second.write_all(&second_payload).await.expect("second stream write");
                    let mut received = vec![0; second_payload.len()];
                    second.read_exact(&mut received).await.expect("second stream read");
                    assert_eq!(received, second_payload);
                }
            );
            connection.close().await.expect("join mux carrier and streams");
        })
        .await
        .expect("upstream multiplexed exchange deadline");
    }
}
