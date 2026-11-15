//! Real upstream interoperability; invoked by scripts/tests/run-outbound-interop.py.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_mieru::{MieruConfig, MieruMux, MieruMuxConnection, MieruProtocol};
use ripdpi_network_time::NetworkTimeProvider;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

/// # Cancel safety:
/// cancel-safe: the test owns and drops the transport and relayed stream.
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
        handshake_no_wait: false,
        traffic_pattern: None,
    };
    timeout(Duration::from_secs(12), async {
        let transport = TcpStream::connect(endpoint).await.expect("connect upstream loopback listener");
        let client = MieruMuxConnection::connect_over(transport, &config, Arc::new(NetworkTimeProvider::system()))
            .await
            .expect("open upstream session");
        let mut stream = client.open_stream("interop.invalid:443").await.expect("upstream SOCKS CONNECT");
        let payload: Vec<u8> = (0..65536).map(|index| (index % 251) as u8).collect();
        stream.write_all(&payload).await.expect("upstream write");
        let mut received = vec![0; payload.len()];
        stream.read_exact(&mut received).await.expect("upstream read");
        assert_eq!(received, payload, "upstream payload must remain byte exact across segments");
        drop(stream);
        client.close().await.expect("join direct carrier");
    })
    .await
    .expect("upstream TCP exchange deadline");
}

/// # Cancel safety:
/// cancel-safe: the connection owns the carrier; the test drops all streams on timeout.
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
            handshake_no_wait: false,
            traffic_pattern: None,
        };
        timeout(Duration::from_secs(12), async {
            let transport = TcpStream::connect(endpoint).await.expect("connect upstream loopback listener");
            let connection =
                MieruMuxConnection::connect_over(transport, &config, Arc::new(NetworkTimeProvider::system()))
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

/// # Cancel safety:
/// cancel-safe: the connection owns the carrier; the test drops the stream on timeout.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn slow_reader_receives_every_byte_with_upstream() {
    let endpoint: SocketAddr = std::env::var("RIPDPI_OUTBOUND_INTEROP_ENDPOINT")
        .expect("run through the upstream oracle script")
        .parse()
        .expect("peer socket address");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    for level in [MieruMux::Off, MieruMux::Middle] {
        let config = MieruConfig {
            server: endpoint.ip().to_string(),
            port: endpoint.port(),
            username: "outbound-interop".into(),
            password: "loopback-test-password".into(),
            protocol: MieruProtocol::Tcp,
            multiplexing: level,
            mtu: 1400,
            handshake_no_wait: false,
            traffic_pattern: None,
        };
        timeout(Duration::from_secs(40), async {
            let transport = TcpStream::connect(endpoint).await.expect("connect upstream loopback listener");
            let connection =
                MieruMuxConnection::connect_over(transport, &config, Arc::new(NetworkTimeProvider::system()))
                    .await
                    .expect("open upstream carrier");
            let stream = connection.open_stream("interop.invalid:443").await.expect("upstream CONNECT");
            let (mut read_half, mut write_half) = tokio::io::split(stream);
            let payload: Vec<u8> = (0..8 << 20).map(|index| (index % 251) as u8).collect();
            let expected = payload.clone();
            let write = async move {
                write_half.write_all(&payload).await.expect("upstream write");
                write_half
            };
            let read = async move {
                // The echo fills every buffer while nobody reads.
                tokio::time::sleep(Duration::from_secs(2)).await;
                let mut received = vec![0; expected.len()];
                read_half.read_exact(&mut received).await.expect("a paused reader must not lose the stream");
                assert!(received == expected, "echo must stay byte exact after the pause");
            };
            let (_write_half, ()) = tokio::join!(write, read);
            connection.close().await.expect("join mux carrier and streams");
        })
        .await
        .expect("slow reader deadline");
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the test closes each owned stream and carrier after its byte-exact exchange.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn advanced_tcp_settings_exchange_with_upstream() {
    let endpoint: SocketAddr = std::env::var("RIPDPI_OUTBOUND_INTEROP_ENDPOINT")
        .expect("run through the pinned peer script")
        .parse()
        .expect("loopback endpoint");
    assert!(endpoint.ip().is_loopback() && endpoint.port() != 0);
    for mode in 1u8..=4 {
        let config = MieruConfig {
            server: endpoint.ip().to_string(),
            port: endpoint.port(),
            username: "outbound-interop".into(),
            password: "loopback-test-password".into(),
            protocol: MieruProtocol::Tcp,
            multiplexing: MieruMux::High,
            mtu: 1400,
            handshake_no_wait: mode.is_multiple_of(2),
            traffic_pattern: Some(ripdpi_mieru::MieruTrafficPattern {
                fragment: true,
                fragment_max_sleep_ms: 1,
                nonce_type: mode - 1,
                nonce_min_len: 6,
                nonce_max_len: 12,
                nonce_prefixes: vec![vec![0, 1, 2, 3, 4, 5]],
                max_middle_padding: 3,
                max_end_padding: 4,
                low_entropy_mode: mode,
                low_entropy_rotation: 3,
            }),
        };
        timeout(Duration::from_secs(20), async {
            let transport = TcpStream::connect(endpoint).await.expect("upstream connect");
            let carrier = MieruMuxConnection::connect_over(transport, &config, Arc::new(NetworkTimeProvider::system()))
                .await
                .expect("advanced carrier");
            let mut stream = carrier.open_stream("interop.invalid:443").await.expect("advanced CONNECT");
            let payload: Vec<u8> = (0..98304).map(|index| (index % 251) as u8).collect();
            let (mut read, mut write) = tokio::io::split(&mut stream);
            let mut response = vec![0; payload.len()];
            let (written, received) = tokio::join!(write.write_all(&payload), read.read_exact(&mut response));
            written.expect("advanced upstream write");
            received.expect("advanced upstream read");
            assert_eq!(response, payload, "mode {mode} preserves payload against actual Go peer");
            stream.close().await.expect("advanced stream close");
            carrier.close().await.expect("advanced carrier close");
        })
        .await
        .expect("advanced upstream deadline");
    }
}
