//! End-to-end regression: the Hysteria 2 client must keep its QUIC connection
//! usable for proxy streams AFTER the HTTP/3 auth exchange.
//!
//! Before the `tls_quic::authenticate_connection` fix (keeping the h3
//! `SendRequest` alive), dropping it triggered an h3 graceful shutdown that
//! closed the shared QUIC connection (`H3_NO_ERROR`), so the first
//! `tcp_connect` after `connect` failed with `ConnectionLost(LocallyClosed)`.
//! This drives the real client against the `Hysteria2Loopback` protocol-server
//! fixture and round-trips a payload through a proxy stream.

use local_network_fixture::Hysteria2Loopback;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[tokio::test]
async fn hysteria2_client_round_trips_through_loopback_after_auth() {
    let server = Hysteria2Loopback::start().await.expect("start hysteria2 fixture");
    // insecure=1 trusts the fixture's self-signed cert via the client's runtime
    // flag; the loopback echoes regardless of the requested target.
    let url = format!("hysteria2://test@127.0.0.1:{}/?sni=localhost&insecure=1", server.port());
    let config = ripdpi_hysteria2::Config::from_url(&url).expect("parse hysteria2 url");

    let client = ripdpi_hysteria2::connect(&config).await.expect("hysteria2 connect (auth)");
    let mut stream = client.tcp_connect("127.0.0.1:9").await.expect("open proxy stream after auth");

    stream.write_all(b"hysteria2-roundtrip").await.expect("write proxied payload");
    stream.flush().await.expect("flush proxied payload");
    let mut echoed = [0u8; 19];
    stream.read_exact(&mut echoed).await.expect("read echoed payload");
    assert_eq!(&echoed, b"hysteria2-roundtrip");

    server.shutdown().await;
}

/// # Cancel safety:
/// cancel-safe: the test owns the stream and drops it on the outer deadline;
/// read_exact and write_all are not retried after cancellation.
#[tokio::test]
async fn hopping_stream_keeps_carrier_after_client_handle_is_dropped() {
    let server = Hysteria2Loopback::start().await.expect("start fixture");
    tokio::time::timeout(std::time::Duration::from_secs(12), async {
        let url = format!("hy2://test@127.0.0.1:{}/?sni=localhost&insecure=1&hopInterval=5s", server.port());
        let config = ripdpi_hysteria2::Config::from_url(&url).expect("hopping config");
        let client = ripdpi_hysteria2::connect(&config).await.expect("connect");
        let mut stream = client.tcp_connect("127.0.0.1:9").await.expect("stream");
        drop(client);
        tokio::time::sleep(std::time::Duration::from_secs(6)).await;
        stream.write_all(b"owned-hopping-stream").await.expect("write after hop");
        let mut echoed = [0; 20];
        stream.read_exact(&mut echoed).await.expect("read after hop");
        assert_eq!(&echoed, b"owned-hopping-stream");
    })
    .await
    .expect("owned stream deadline");
    server.shutdown().await;
}
