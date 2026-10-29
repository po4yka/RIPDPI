//! Production client against an unmodified pinned Xray peer, entirely on loopback.
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use boring::ssl::{SslConnector, SslMethod, SslVerifyMode, SslVersion};
use ripdpi_vless::VlessRealityClient;
use ripdpi_vless::config::VlessRealityConfig;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};

const DEADLINE: Duration = Duration::from_secs(15);

fn required(name: &str) -> String {
    std::env::var(name).expect("run through the pinned upstream oracle runner")
}

fn endpoint(name: &str) -> SocketAddr {
    let address: SocketAddr = required(name).parse().expect("oracle endpoint");
    assert!(address.ip().is_loopback() && address.port() != 0);
    address
}

fn config(name: &str, flow: &str) -> VlessRealityConfig {
    let endpoint = endpoint(name);
    VlessRealityConfig::from_strings(
        &endpoint.ip().to_string(),
        i32::from(endpoint.port()),
        &required("RIPDPI_OUTBOUND_UUID"),
        "outbound.invalid",
        &required("RIPDPI_OUTBOUND_PUBLIC_KEY"),
        &required("RIPDPI_OUTBOUND_SHORT_ID"),
        "chrome",
    )
    .expect("oracle profile")
    .with_flow_str(flow)
    .expect("flow")
}

fn payload() -> Vec<u8> {
    (0..65536).map(|index| (index % 251) as u8).collect()
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned stream; no partial exchange is resumed.
async fn exchange(config: &VlessRealityConfig) {
    let target = endpoint("RIPDPI_OUTBOUND_TCP").to_string();
    let mut stream = VlessRealityClient::connect(config, &target).await.expect("REALITY connect");
    let payload = payload();
    stream.write_all(&payload).await.expect("write payload");
    stream.flush().await.expect("flush payload");
    let mut echoed = vec![0; payload.len()];
    stream.read_exact(&mut echoed).await.expect("read complete echo");
    assert_eq!(echoed, payload);
    stream.shutdown().await.expect("close application stream");
}

/// # Cancel safety:
/// cancel-safe: timeout drops the complete owned test exchange.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn reality_tcp_exchanges_payload_with_upstream() {
    let _ = tracing_subscriber::fmt().with_max_level(tracing::Level::INFO).with_test_writer().try_init();
    tokio::time::timeout(DEADLINE, exchange(&config("RIPDPI_OUTBOUND_INTEROP_ENDPOINT", "")))
        .await
        .expect("REALITY TCP deadline");
}

/// Split serialized inner TLS writes before they enter the production Vision wrapper.
struct FragmentedWrite<T> {
    inner: T,
    next: usize,
    fragmented: bool,
}

impl<T: AsyncRead + Unpin> AsyncRead for FragmentedWrite<T> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buffer: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(cx, buffer)
    }
}
impl<T: AsyncWrite + Unpin> AsyncWrite for FragmentedWrite<T> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> {
        let sizes = [1, 2, 7, 257, 4096];
        let length = if self.fragmented { bytes.len().min(sizes[self.next % sizes.len()]) } else { bytes.len() };
        let result = Pin::new(&mut self.inner).poll_write(cx, &bytes[..length]);
        if let Poll::Ready(Ok(_)) = result {
            self.next += 1;
        }
        result
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

/// # Cancel safety:
/// cancel-safe: timeout drops both TLS layers and their socket; the exchange is not resumed.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn vision_fragmented_tls_exchanges_payload_with_upstream() {
    tokio::time::timeout(DEADLINE, async {
        for fragmented in [true, false] {
            let profile = config("RIPDPI_OUTBOUND_VISION", "xtls-rprx-vision");
            let target = endpoint("RIPDPI_OUTBOUND_TLS").to_string();
            let stream = VlessRealityClient::connect(&profile, &target).await.expect("Vision connect");
            let mut connector = SslConnector::builder(SslMethod::tls()).expect("inner connector");
            // Only the oracle's ephemeral loopback certificate is used in this test.
            connector.set_verify(SslVerifyMode::NONE);
            connector.set_min_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 minimum");
            connector.set_max_proto_version(Some(SslVersion::TLS1_3)).expect("TLS 1.3 maximum");
            let ssl =
                connector.build().configure().expect("configure inner TLS").into_ssl("outbound.invalid").expect("SNI");
            let mut tls =
                tokio_boring::SslStreamBuilder::new(ssl, FragmentedWrite { inner: stream, next: 0, fragmented })
                    .connect()
                    .await
                    .unwrap_or_else(|error| panic!("fragmented inner TLS handshake: {error}"));
            assert_eq!(tls.ssl().version2(), Some(SslVersion::TLS1_3));
            let expected = payload();
            // One-byte and multi-record application writes traverse the same Vision stream.
            tls.write_all(&expected[..1]).await.expect("small TLS write");
            tls.write_all(&expected[1..]).await.expect("coalesced application write");
            tls.flush().await.expect("flush inner TLS");
            let mut received = vec![0; expected.len()];
            tls.read_exact(&mut received).await.expect("complete inner TLS echo");
            assert_eq!(received, expected);
            tls.shutdown().await.expect("TLS close_notify");
            assert_eq!(tls.read(&mut received).await.expect("TLS EOF"), 0);
        }
    })
    .await
    .expect("Vision deadline");
}

/// # Cancel safety:
/// cancel-safe: timeout drops the dedicated XUDP carrier and its reader/writer tasks.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn xudp_two_targets_exchange_with_upstream() {
    tokio::time::timeout(DEADLINE, async {
        let profile = config("RIPDPI_OUTBOUND_VISION", "xtls-rprx-vision");
        let mut session = VlessRealityClient::connect_xudp(&profile, None).await.expect("XUDP association");
        for (name, bytes) in [
            ("RIPDPI_OUTBOUND_UDP", b"first-single-send".as_slice()),
            ("RIPDPI_OUTBOUND_UDP2", b"second-destination".as_slice()),
        ] {
            let target = endpoint(name).to_string();
            session.send_to(&target, bytes).await.expect("send XUDP datagram");
            // No following send is available to flush the first datagram.
            let (source, echoed) = session.recv_from().await.expect("receive XUDP datagram");
            assert_eq!(source, target);
            assert_eq!(echoed, bytes);
        }
    })
    .await
    .expect("XUDP deadline");
}

/// # Cancel safety:
/// cancel-safe: rejection owns and drops any partially authenticated stream.
async fn assert_rejected(profile: &VlessRealityConfig) {
    let result = tokio::time::timeout(Duration::from_secs(2), async {
        let target = endpoint("RIPDPI_OUTBOUND_TCP").to_string();
        let mut stream = VlessRealityClient::connect(profile, &target).await?;
        stream.write_all(b"must-not-reach-application").await?;
        stream.flush().await?;
        let mut byte = [0];
        stream.read_exact(&mut byte).await
    })
    .await;
    assert!(!matches!(result, Ok(Ok(_))), "invalid admission delivered application data");
    let mut stats = tokio::net::TcpStream::connect(endpoint("RIPDPI_OUTBOUND_STATS")).await.expect("stats");
    let mut counter = String::new();
    stats.read_to_string(&mut counter).await.expect("delivery count");
    assert_eq!(counter.trim(), "0", "invalid admission reached an owned target");
}

/// # Cancel safety:
/// cancel-safe: timeout drops all owned failed admissions and their sockets.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn upstream_rejects_invalid_reality_admission() {
    tokio::time::timeout(DEADLINE, async {
        let good = config("RIPDPI_OUTBOUND_INTEROP_ENDPOINT", "");
        let mut wrong_key = good.clone();
        wrong_key.reality_public_key = [9; 32];
        assert_rejected(&wrong_key).await;
        let mut wrong_short_id = good.clone();
        wrong_short_id.reality_short_id[0] ^= 1;
        assert_rejected(&wrong_short_id).await;
        let mut wrong_uuid = good.clone();
        wrong_uuid.uuid[0] ^= 1;
        assert_rejected(&wrong_uuid).await;
        exchange(&good).await;
    })
    .await
    .expect("authentication rejection deadline");
}

/// # Cancel safety:
/// cancel-safe: excluded admissions are dropped before a fresh accepted exchange.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn upstream_enforces_reality_version_bounds() {
    tokio::time::timeout(DEADLINE, async {
        assert_rejected(&config("RIPDPI_OUTBOUND_MINIMUM", "")).await;
        assert_rejected(&config("RIPDPI_OUTBOUND_MAXIMUM", "")).await;
        exchange(&config("RIPDPI_OUTBOUND_INTEROP_ENDPOINT", "")).await;
    })
    .await
    .expect("version admission deadline");
}

/// # Cancel safety:
/// cancel-safe: each deadline drops the affected stream; no partial exchange is reused.
#[tokio::test]
#[ignore = "requires pinned upstream peer; run scripts/tests/run-outbound-interop.py"]
async fn stalled_or_closed_connections_are_bounded() {
    tokio::time::timeout(DEADLINE, async {
        let target = endpoint("RIPDPI_OUTBOUND_TCP").to_string();
        let stalled = config("RIPDPI_OUTBOUND_STALL_RELAY", "");
        assert!(
            tokio::time::timeout(Duration::from_millis(200), VlessRealityClient::connect(&stalled, &target))
                .await
                .is_err()
        );
        let good = config("RIPDPI_OUTBOUND_INTEROP_ENDPOINT", "");
        let mut stream = VlessRealityClient::connect(&good, &endpoint("RIPDPI_OUTBOUND_STALL").to_string())
            .await
            .expect("stalled target connect");
        stream.write_all(b"waiting").await.expect("stalled target write");
        stream.flush().await.expect("stalled target flush");
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(200), stream.read(&mut byte)).await.is_err());
        drop(stream);
        let mut closed = VlessRealityClient::connect(&good, &endpoint("RIPDPI_OUTBOUND_CLOSED").to_string())
            .await
            .expect("closed target connect");
        let result = closed.read(&mut byte).await;
        assert!(matches!(result, Ok(0) | Err(_)), "closed target unexpectedly returned application data");
        drop(closed);
        exchange(&good).await;
    })
    .await
    .expect("bounded connection failure deadline");
}
