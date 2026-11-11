use std::io;
use std::net::TcpListener;
use std::time::Duration;

use ripdpi_relay_mux::RelaySessionFactory;

use super::VlessRealitySessionFactory;

/// # Cancel safety:
/// cancel-safe: the deadline drops incomplete construction; the local peer owns no task.
#[tokio::test(flavor = "current_thread")]
async fn independent_xudp_constructor_does_not_establish_a_yamux_carrier() {
    let peer = TcpListener::bind("127.0.0.1:0").expect("bind unused relay peer");
    peer.set_nonblocking(true).expect("nonblocking peer");
    let config = ripdpi_vless::config::VlessRealityConfig::from_strings(
        "127.0.0.1",
        i32::from(peer.local_addr().expect("relay peer address").port()),
        "11111111-1111-1111-1111-111111111111",
        "relay.example",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "",
        "chrome_stable",
    )
    .expect("valid synthetic Reality config")
    .with_flow(ripdpi_vless::addons::VlessFlow::Vision)
    .with_mux_strings("yamux", 2, 0, 0)
    .expect("valid yamux profile");
    let factory = VlessRealitySessionFactory {
        config,
        outbound_bind_ip: None,
        session_limiter: ripdpi_vless::VlessRealityCarrierLimiter::default(),
        udp_enabled: true,
    };
    assert!(!factory.datagrams_use_shared_session(), "XUDP has an independent physical carrier");
    let session = tokio::time::timeout(Duration::from_millis(100), factory.create_datagram_session())
        .await
        .expect("independent construction must not wait for a peer handshake")
        .expect("independent config-only session");
    assert!(session.mux.is_none(), "a datagram wrapper must not own yamux");
    assert!(session.config.mux.is_some(), "the profile's TCP mux selection stays intact");
    assert!(matches!(peer.accept(), Err(error) if error.kind() == io::ErrorKind::WouldBlock));
}
