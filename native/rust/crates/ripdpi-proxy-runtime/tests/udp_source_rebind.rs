#![cfg(not(feature = "loom"))]

mod support;

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;
use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::socks5::{socks_udp_associate, udp_proxy_roundtrip_with_socket};

#[test]
fn source_rebind_keeps_pinned_socks_working_and_direct_rebind_available() {
    for through_socks in [false, true] {
        check_source_rebind(through_socks);
    }
}

fn check_source_rebind(through_socks: bool) {
    let echo = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("echo socket");
    echo.set_read_timeout(Some(Duration::from_secs(2))).expect("echo timeout");
    let echo_port = echo.local_addr().expect("echo address").port();
    let server = thread::spawn(move || {
        let mut buffer = [0; 100];
        let mut peers = Vec::new();
        for _ in 0..4 {
            let (n, peer) = echo.recv_from(&mut buffer).expect("echo request");
            peers.push(peer);
            echo.send_to(&buffer[..n], peer).expect("echo response");
        }
        peers
    });

    let upstream = through_socks.then(|| start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1"]), None));
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1", "--quic-migrate"]);
    config.groups[0].policy.ext_socks = upstream
        .as_ref()
        .map(|proxy| UpstreamSocksConfig { addr: SocketAddr::from((Ipv4Addr::LOCALHOST, proxy.port)), auth: None });
    let downstream = start_proxy(config, None);
    let (control, relay) = socks_udp_associate(downstream.port);
    let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("client UDP");
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("client timeout");

    for payload in [b"\x40first".as_slice(), b"\x40second", b"\x40third", b"\x40fourth"] {
        assert_eq!(udp_proxy_roundtrip_with_socket(&client, relay, echo_port, payload), payload);
    }

    drop(control);
    drop(downstream);
    drop(upstream);
    let peers = server.join().expect("join echo server");
    assert_eq!(peers[0], peers[1]);
    if through_socks {
        assert_eq!(peers[0], peers[3], "the pinned SOCKS path must stay usable");
    } else {
        assert_ne!(peers[1], peers[2], "direct rebind must remain active");
        assert_eq!(peers[2], peers[3]);
    }
}
