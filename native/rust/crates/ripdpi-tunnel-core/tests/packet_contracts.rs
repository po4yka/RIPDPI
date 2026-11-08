//! Scapy 2.6.1 inputs from the VPN-06 audit, checked with Wireshark 4.6.9.
//! The fixture peer uses only real host loopback sockets. The packet fd is a Unix datagram pair.

mod support;

use std::io::{Read, Write};
use std::net::{TcpListener, UdpSocket};
use std::sync::{Arc, mpsc};
use std::thread::JoinHandle;
use std::time::Duration;

use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use support::fake_tun::FakeTunHarness;
use tokio_util::sync::CancellationToken;

fn packet(name: &str) -> Vec<u8> {
    hex::decode(match name {
        "udp4" => "4500004000010000401134a10a000002cb0071099c4001bb002c298b4142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp4_bad_ip_checksum" => "45000040000100004011b4a10a000002cb0071099c4001bb002c298b4142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp4_ihl15" => "4f00006800010000401116650a000002cb007109010101010101010101010101010101010101010101010101010101010101010101010101010101019c4001bb002c298b4142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp4_truncated" => "4500004000010000401134a10a000002cb0071099c4001bb002c298b4142434445464748494a4b4c4d4e4f505152535455565758595a303132333435363738",
        "udp4_zero_len" => "4500004000010000401134a10a000002cb0071099c4001bb0000298b4142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp4_udp_tail" => "4500004000010000401134a10a000002cb0071099c4001bb000b00004142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp6" => "60000000002c1140fd00000000000000000000000000000220010db80000000000000000000000099c4001bb002c44d24142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp6_hop_discard" => "6000000000340040fd00000000000000000000000000000220010db800000000000000000000000911004002585801009c4001bb002c44d24142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "udp6_dest_discard" => "6000000000343c40fd00000000000000000000000000000220010db800000000000000000000000911004002585801009c4001bb002c44d24142434445464748494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "frag6_first" => "6000000000202c40fd00000000000000000000000000000220010db800000000000000000000000911000001000000649c4001bb002c44d24142434445464748494a4b4c4d4e4f50",
        "frag6_last" => "60000000001c2c40fd00000000000000000000000000000220010db800000000000000000000000911000018000000645152535455565758595a30313233343536373839",
        "frag6_overlap_last" => "6000000000242c40fd00000000000000000000000000000220010db80000000000000000000000091100001000000064494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        "frag6_postdest_first" => "6000000000202c40fd00000000000000000000000000000220010db80000000000000000000000093c0000010000006511000104000000009c4001bb002c44d24142434445464748",
        "frag6_postdest_last" => "6000000000242c40fd00000000000000000000000000000220010db80000000000000000000000093c00001800000065494a4b4c4d4e4f505152535455565758595a30313233343536373839",
        _ => panic!("unknown vector {name}"),
    }).expect("checked packet vector")
}

struct RunningTunnel {
    harness: FakeTunHarness,
    cancel: CancellationToken,
    worker: Option<JoinHandle<std::io::Result<()>>>,
    peer: Option<JoinHandle<()>>,
}

impl Drop for RunningTunnel {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        if let Some(peer) = self.peer.take() {
            let _ = peer.join();
        }
    }
}

impl RunningTunnel {
    fn finish(mut self) {
        self.cancel.cancel();
        self.worker.take().expect("worker").join().expect("worker panic").expect("tunnel stop");
        self.peer.take().expect("peer").join().expect("peer panic");
    }
}

fn start_tunnel() -> (RunningTunnel, UdpSocket) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("SOCKS listener");
    listener.set_nonblocking(true).expect("nonblocking accept");
    let proxy = listener.local_addr().expect("proxy address");
    let udp = UdpSocket::bind("127.0.0.1:0").expect("UDP peer");
    udp.set_read_timeout(Some(Duration::from_millis(500))).expect("UDP timeout");
    let port = udp.local_addr().expect("UDP address").port();
    let (fd, harness) = support::fake_tun::socketpair_tun().expect("packet pair");
    let mut config = support::config::test_tunnel_config(proxy);
    Arc::make_mut(&mut config).tunnel.ipv6 = Some("fd00::1/64".into());
    let mut tunnel = RunningTunnel { harness, cancel: CancellationToken::new(), worker: None, peer: None };
    let peer_cancel = tunnel.cancel.clone();
    tunnel.peer = Some(std::thread::spawn(move || {
        let mut controls = Vec::new();
        while !peer_cancel.is_cancelled() {
            match listener.accept() {
                Ok((mut tcp, _)) => {
                    tcp.set_nonblocking(false).expect("blocking accepted socket");
                    tcp.set_read_timeout(Some(Duration::from_secs(2))).expect("control timeout");
                    let mut greeting = [0; 3];
                    tcp.read_exact(&mut greeting).expect("SOCKS greeting");
                    assert_eq!(greeting, [5, 1, 0]);
                    tcp.write_all(&[5, 0]).expect("method reply");
                    let mut req = [0; 10];
                    tcp.read_exact(&mut req).expect("UDP ASSOCIATE request");
                    assert_eq!(&req[..4], &[5, 3, 0, 1]);
                    let mut reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
                    reply.extend_from_slice(&port.to_be_bytes());
                    tcp.write_all(&reply).expect("association reply");
                    controls.push(tcp);
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(error) => panic!("accept: {error}"),
            }
        }
    }));
    let stop = tunnel.cancel.clone();
    let (tx, rx) = mpsc::channel();
    tunnel.worker = Some(std::thread::spawn(move || {
        tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime").block_on(
            run_tunnel_with_ready(config, fd, stop, Arc::new(Stats::new()), move || {
                tx.send(()).expect("ready receiver");
            }),
        )
    }));
    rx.recv_timeout(Duration::from_secs(5)).expect("native readiness");
    (tunnel, udp)
}

#[test]
fn udp_packet_contracts_hold_through_the_loopback_socket_path() {
    let (tunnel, udp) = start_tunnel();
    let expected = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    for (name, payload) in [
        ("udp4", Some(expected.as_slice())),
        ("udp4_bad_ip_checksum", None),
        ("udp4_ihl15", Some(expected.as_slice())),
        ("udp4_truncated", None),
        ("udp4_zero_len", None),
        ("udp4_udp_tail", Some(&expected[..3])),
        ("udp6", Some(expected.as_slice())),
        ("udp6_hop_discard", None),
        ("udp6_dest_discard", None),
    ] {
        tunnel.harness.inject_packet(&packet(name)).expect("inject packet");
        let mut buffer = [0; 65536];
        match (payload, udp.recv_from(&mut buffer)) {
            (Some(expected), Ok((length, from))) => {
                let offset = if buffer[3] == 1 { 10 } else { 22 };
                assert_eq!(&buffer[offset..length], expected, "{name}");
                udp.send_to(&buffer[..length], from).expect("echo reply");
                let response = tunnel.harness.recv_packet(Duration::from_secs(2)).expect("TUN reply");
                let parsed = etherparse::SlicedPacket::from_ip(&response).expect("valid IP reply");
                let Some(etherparse::TransportSlice::Udp(udp)) = parsed.transport else { panic!("UDP reply") };
                assert_eq!(udp.payload(), expected, "{name}");
            }
            (None, Err(error))
                if matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
            (expected, actual) => panic!("{name}: expected payload {expected:?}, received {actual:?}"),
        }
    }
    for (first, last, delivered) in [
        ("frag6_first", "frag6_last", true),
        ("frag6_first", "frag6_overlap_last", false),
        ("frag6_postdest_first", "frag6_postdest_last", true),
    ] {
        tunnel.harness.inject_packet(&packet(first)).expect("first fragment");
        tunnel.harness.inject_packet(&packet(last)).expect("last fragment");
        let mut buffer = [0; 65536];
        match (delivered, udp.recv_from(&mut buffer)) {
            (true, Ok((length, _))) => assert_eq!(&buffer[22..length], expected),
            (false, Err(error))
                if matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
            (expected, actual) => panic!("{last}: expected delivery {expected}, received {actual:?}"),
        }
    }
    tunnel.finish();
}
