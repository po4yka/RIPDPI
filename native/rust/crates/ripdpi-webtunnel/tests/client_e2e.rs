use std::io::{Read, Write};

use local_network_fixture::WebTunnelFixture;
use ripdpi_webtunnel::bridge_line::parse_bridge_line;
use ripdpi_webtunnel::client::{connect_webtunnel, connect_webtunnel_async};

#[test]
fn webtunnel_client_round_trips_over_tls_upgrade_fixture() {
    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = parse_bridge_line(&format!(
        "Bridge webtunnel 192.0.2.3:1 url={} addr={} servername=localhost utls=hellochrome_auto",
        fixture.url(),
        fixture.addr(),
    ))
    .expect("bridge line");

    let mut stream = connect_webtunnel(&bridge, false).expect("connect WebTunnel fixture");
    stream.write_all(b"ping-over-webtunnel").expect("write tunnel payload");
    stream.flush().expect("flush tunnel payload");

    let mut echoed = [0_u8; 19];
    stream.read_exact(&mut echoed).expect("read echoed tunnel payload");
    assert_eq!(&echoed, b"ping-over-webtunnel");

    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].path, "/secret");
    assert_eq!(observed[0].host, "localhost");
}

#[tokio::test]
async fn webtunnel_async_client_round_trips_over_tls_upgrade_fixture() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
    let bridge = parse_bridge_line(&format!(
        "Bridge webtunnel 192.0.2.3:1 url={} addr={} servername=localhost utls=hellochrome_auto",
        fixture.url(),
        fixture.addr(),
    ))
    .expect("bridge line");

    let mut stream = connect_webtunnel_async(&bridge, false).await.expect("connect WebTunnel fixture (async)");
    stream.write_all(b"ping-over-webtunnel").await.expect("write tunnel payload");
    stream.flush().await.expect("flush tunnel payload");

    let mut echoed = [0_u8; 19];
    stream.read_exact(&mut echoed).await.expect("read echoed tunnel payload");
    assert_eq!(&echoed, b"ping-over-webtunnel");

    let observed = fixture.observed_requests();
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].path, "/secret");
    assert_eq!(observed[0].host, "localhost");
}
