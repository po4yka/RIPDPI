#![cfg(not(feature = "loom"))]

mod support;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, UdpSocket};
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;
use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::socks5::socks_udp_associate;

#[test]
fn upstream_udp_reply_with_another_peer_is_not_relabelled() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("upstream listener");
    let upstream_addr = listener.local_addr().expect("upstream address");
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("upstream UDP relay");
    relay.set_read_timeout(Some(Duration::from_secs(2))).expect("relay timeout");
    let relay_port = relay.local_addr().expect("relay address").port();
    let server = thread::spawn(move || {
        let (mut control, _) = listener.accept().expect("upstream control");
        control.set_read_timeout(Some(Duration::from_secs(2))).expect("control timeout");
        let mut greeting = [0; 3];
        control.read_exact(&mut greeting).expect("greeting");
        assert_eq!(greeting, [5, 1, 0]);
        control.write_all(&[5, 0]).expect("auth reply");
        let mut associate = [0; 10];
        control.read_exact(&mut associate).expect("association request");
        assert_eq!(associate, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        let mut reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
        reply.extend_from_slice(&relay_port.to_be_bytes());
        control.write_all(&reply).expect("association reply");

        let mut request = [0; 100];
        let (n, client) = relay.recv_from(&mut request).expect("client datagram");
        assert_eq!(&request[..n], b"\0\0\0\x01\xcb\0\x71\x07\x01\xbbrequest");
        relay.send_to(b"\0\0\0\x01\xc6\x33\x64\x63\x15\xb3wrong-peer", client).expect("unexpected peer reply");
        relay.send_to(b"\0\0\0\x01\xcb\0\x71\x07\x01\xbbvalid", client).expect("valid peer reply");
        let mut sink = [0];
        let _ = control.read(&mut sink);
    });

    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: upstream_addr, auth: None });
    let proxy = start_proxy(config, None);
    let (control, bound) = socks_udp_associate(proxy.port);
    let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("client UDP");
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("client timeout");
    client.send_to(b"\0\0\0\x01\xcb\0\x71\x07\x01\xbbrequest", bound).expect("request");
    let mut reply = [0; 100];
    let (n, _) = client.recv_from(&mut reply).expect("valid reply");
    assert_eq!(&reply[..n], b"\0\0\0\x01\xcb\0\x71\x07\x01\xbbvalid");
    drop(control);
    server.join().expect("join upstream server");
}
