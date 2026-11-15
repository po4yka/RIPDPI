#![cfg(not(feature = "loom"))]

mod support;

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;
use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::socks5::socks_udp_associate;

const V1_REQUEST: &[u8] = include_bytes!("../../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin");
const V1_RESPONSE: &[u8] = include_bytes!("../../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin");
const V2_REQUEST: &[u8] = include_bytes!("../../ripdpi-packets/tests/fixtures/quic/rfc9369-client-initial.bin");
const V2_RESPONSE: &[u8] = include_bytes!("../../ripdpi-packets/tests/fixtures/quic/rfc9369-server-initial.bin");

#[test]
fn source_rebind_requires_quic_context_and_keeps_pinned_socks_working() {
    for plain in [b"\x00plain-first".as_slice(), b"\x40ordinary-first"] {
        check_source_rebind(false, plain, plain, false);
    }
    for (request, response) in [(V1_REQUEST, V1_RESPONSE), (V2_REQUEST, V2_RESPONSE)] {
        for through_socks in [false, true] {
            check_source_rebind(through_socks, request, response, !through_socks);
        }
    }
}

fn check_source_rebind(through_socks: bool, initial: &[u8], initial_response: &[u8], expect_rebind: bool) {
    let echo = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("echo socket");
    echo.set_read_timeout(Some(Duration::from_secs(2))).expect("echo timeout");
    let echo_port = echo.local_addr().expect("echo address").port();
    let initial = initial.to_vec();
    let first_response = initial_response.to_vec();
    let initial_for_server = initial.clone();
    let server = thread::spawn(move || {
        let mut buffer = [0; 4096];
        let mut peers = Vec::new();
        for index in 0..4 {
            let (n, peer) = echo.recv_from(&mut buffer).expect("echo request");
            peers.push(peer);
            if index == 0 {
                assert_eq!(&buffer[..n], initial_for_server);
                echo.send_to(&first_response, peer).expect("Initial response");
            } else {
                echo.send_to(&buffer[..n], peer).expect("echo response");
            }
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

    for (index, payload) in [initial.as_slice(), b"\x40second", b"\x40third", b"\x40fourth"].into_iter().enumerate() {
        let expected = if index == 0 { initial_response } else { payload };
        assert_eq!(exchange_udp_packet(&client, relay, echo_port, payload), expected);
    }

    drop(control);
    drop(downstream);
    drop(upstream);
    let peers = server.join().expect("join echo server");
    assert_eq!(peers[0], peers[1]);
    if expect_rebind {
        assert_ne!(peers[1], peers[2], "direct QUIC rebind must remain active");
        assert_eq!(peers[2], peers[3]);
    } else {
        assert!(
            peers.iter().all(|peer| *peer == peers[0]),
            "generic UDP and pinned SOCKS must keep their tuple: {peers:?}"
        );
    }
}

fn exchange_udp_packet(client: &UdpSocket, relay: SocketAddr, port: u16, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0, 0, 0, 1, 127, 0, 0, 1];
    packet.extend(port.to_be_bytes());
    packet.extend(payload);
    client.send_to(&packet, relay).expect("send UDP packet");
    let mut response = [0; 4096];
    let (n, sender) = client.recv_from(&mut response).expect("receive UDP response");
    assert_eq!(sender, relay);
    assert!(n >= 10);
    assert_eq!(&response[..8], &[0, 0, 0, 1, 127, 0, 0, 1]);
    assert_eq!(&response[8..10], &port.to_be_bytes());
    response[10..n].to_vec()
}
