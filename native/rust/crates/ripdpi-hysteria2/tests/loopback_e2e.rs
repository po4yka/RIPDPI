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
