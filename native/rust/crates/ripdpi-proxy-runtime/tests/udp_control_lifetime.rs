#![cfg(not(feature = "loom"))]

mod support;

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;
use socket2::SockRef;
use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::socks5::socks_udp_associate;

#[test]
fn closed_upstream_control_is_replaced_before_the_next_datagram() {
    for (reset, extra_data) in [(false, false), (true, false), (false, true)] {
        check_control_close(reset, extra_data);
    }
}

fn check_control_close(reset: bool, extra_data: bool) {
    let server = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("upstream listener");
    let old_relay = udp();
    let new_relay = udp();
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].policy.ext_socks =
        Some(UpstreamSocksConfig { addr: server.local_addr().expect("server address"), auth: None });
    let proxy = start_proxy(config, None);
    let (_client_control, client_relay) = socks_udp_associate(proxy.port);
    let client = udp();
    let before = b"\0\0\0\x01\xc0\0\x02\x0a\x11\x5cbefore-close";
    client.send_to(before, client_relay).expect("send first datagram");
    let (mut control, _) = server.accept().expect("first association");
    handshake(&mut control, old_relay.local_addr().expect("old relay address"));
    let mut packet = [0; 128];
    let (len, original_sender) = old_relay.recv_from(&mut packet).expect("first upstream datagram");
    assert_eq!(&packet[..len], before);
    old_relay.send_to(before, original_sender).expect("first reply");
    let (len, _) = client.recv_from(&mut packet).expect("first client reply");
    assert_eq!(&packet[..len], before);

    if extra_data {
        control.write_all(&[0x7f]).expect("unexpected control data before FIN");
    }
    if reset {
        SockRef::from(&control).set_linger(Some(Duration::ZERO)).expect("reset on drop");
    }
    drop(control);
    thread::sleep(Duration::from_millis(100));
    let after = b"\0\0\0\x01\xc0\0\x02\x0a\x11\x5cafter-close";
    client.send_to(after, client_relay).expect("send after control close");
    server.set_nonblocking(true).expect("nonblocking listener");
    let deadline = Instant::now() + Duration::from_secs(1);
    let mut replacement = loop {
        match server.accept() {
            Ok((stream, _)) => break stream,
            Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                thread::sleep(Duration::from_millis(10));
            }
            result => panic!("reset={reset}: a closed UDP association must be replaced: {result:?}"),
        }
    };
    handshake(&mut replacement, new_relay.local_addr().expect("new relay address"));
    let (len, sender) = new_relay.recv_from(&mut packet).expect("replacement relay datagram");
    assert_eq!(&packet[..len], after);
    assert!(
        matches!(old_relay.recv_from(&mut packet), Err(ref error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut)),
        "no datagram may use the invalid binding"
    );
    new_relay.send_to(after, sender).expect("replacement reply");
    let (len, _) = client.recv_from(&mut packet).expect("replacement client reply");
    assert_eq!(&packet[..len], after);
}

fn udp() -> UdpSocket {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("UDP socket");
    socket.set_read_timeout(Some(Duration::from_millis(250))).expect("UDP timeout");
    socket
}

fn handshake(control: &mut TcpStream, relay: SocketAddr) {
    control.set_nonblocking(false).expect("blocking accepted control");
    control.set_read_timeout(Some(Duration::from_secs(1))).expect("control timeout");
    let mut greeting = [0; 3];
    control.read_exact(&mut greeting).expect("upstream greeting");
    assert_eq!(greeting, [5, 1, 0]);
    control.write_all(&[5, 0]).expect("select no auth");
    let mut request = [0; 10];
    control.read_exact(&mut request).expect("upstream ASSOCIATE");
    assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
    let mut reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
    reply.extend_from_slice(&relay.port().to_be_bytes());
    control.write_all(&reply).expect("advertise relay");
}
