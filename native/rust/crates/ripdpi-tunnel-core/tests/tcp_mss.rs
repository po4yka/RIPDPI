//! RFC 9293 MSS defaults through the packet loop and a real loopback SOCKS stream.

mod support;

use std::io::{Read, Write};
use std::net::{Ipv6Addr, Shutdown, TcpListener};
use std::sync::{Arc, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config;
use support::fake_tun::{FakeTunHarness, socketpair_tun};

const CLIENT_SEQUENCE: u32 = 100;
const MTU: usize = 1280;

#[test]
fn ipv4_default_zero_and_explicit_mss_limit_wire_payloads() {
    for (mss, expected) in [(None, 536), (Some(0), 536), (Some(1000), 1000), (Some(10), 48)] {
        assert_wire_mss(false, mss, expected);
    }
}

#[test]
fn ipv6_default_zero_and_explicit_mss_limit_wire_payloads() {
    for (mss, expected) in [(None, 1220), (Some(0), 1220), (Some(1000), 1000), (Some(10), 48)] {
        assert_wire_mss(true, mss, expected);
    }
}

struct RunningTunnel {
    harness: FakeTunHarness,
    cancel: CancellationToken,
    worker: Option<JoinHandle<std::io::Result<()>>>,
}

impl Drop for RunningTunnel {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

impl RunningTunnel {
    fn finish(mut self) {
        self.cancel.cancel();
        let result = self.worker.take().expect("tunnel worker").join().expect("join tunnel");
        assert!(result.is_ok(), "tunnel stop: {result:?}");
    }
}

fn assert_wire_mss(ipv6: bool, mss: Option<u16>, expected: usize) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback SOCKS listener");
    let proxy = listener.local_addr().expect("proxy address");
    // Two segments also cover tiny MSS without overflowing the host datagram queue.
    let payload: Vec<u8> = (0..expected * 2).map(|index| (index % 251) as u8).collect();
    let expected_payload = payload.clone();
    let peer = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept SOCKS connection");
        stream.set_read_timeout(Some(Duration::from_secs(3))).expect("peer timeout");
        let mut greeting = [0; 3];
        stream.read_exact(&mut greeting).expect("SOCKS greeting");
        assert_eq!(greeting, [5, 1, 0]);
        stream.write_all(&[5, 0]).expect("SOCKS method selection");
        let mut header = [0; 4];
        stream.read_exact(&mut header).expect("SOCKS CONNECT header");
        assert_eq!(header, [5, 1, 0, if ipv6 { 4 } else { 1 }]);
        let mut address = vec![0; if ipv6 { 18 } else { 6 }];
        stream.read_exact(&mut address).expect("SOCKS CONNECT address");
        let expected_address = if ipv6 {
            "2001:db8::7".parse::<Ipv6Addr>().expect("documentation address").octets().to_vec()
        } else {
            vec![203, 0, 113, 7]
        };
        assert_eq!(&address[..address.len() - 2], expected_address);
        assert_eq!(&address[address.len() - 2..], &443u16.to_be_bytes());
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).expect("SOCKS CONNECT reply");
        stream.write_all(&payload).expect("send response");
        stream.shutdown(Shutdown::Write).expect("peer half-close");
    });
    let (fd, harness) = socketpair_tun().expect("socketpair TUN");
    let mut config = test_tunnel_config(proxy);
    let settings = Arc::make_mut(&mut config);
    settings.tunnel.mtu = MTU as u32;
    if ipv6 {
        settings.tunnel.ipv6 = Some("fd00::1/64".to_owned());
    }
    let cancel = CancellationToken::new();
    let worker_cancel = cancel.clone();
    let (ready_tx, ready_rx) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        tokio::runtime::Runtime::new().expect("runtime").block_on(run_tunnel_with_ready(
            config,
            fd,
            worker_cancel,
            Arc::new(Stats::new()),
            move || ready_tx.send(()).expect("ready receiver"),
        ))
    });
    let tunnel = RunningTunnel { harness, cancel, worker: Some(worker) };
    ready_rx.recv_timeout(Duration::from_secs(3)).expect("tunnel ready");
    tunnel.harness.inject_packet(&client_packet(ipv6, CLIENT_SEQUENCE, 0, 0x02, mss)).expect("SYN");
    let syn_ack = tunnel.harness.recv_packet(Duration::from_secs(3)).expect("SYN ACK");
    let (server_sequence, acknowledgment, flags, _) = tcp_parts(&syn_ack);
    assert_eq!(flags & 0x12, 0x12);
    assert_eq!(acknowledgment, CLIENT_SEQUENCE + 1);
    let mut next_server = server_sequence.wrapping_add(1);
    tunnel.harness.inject_packet(&client_packet(ipv6, CLIENT_SEQUENCE + 1, next_server, 0x10, None)).expect("ACK");
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut received = Vec::new();
    let mut max_payload = 0;
    while received.len() < expected_payload.len() {
        let timeout = deadline.checked_duration_since(Instant::now()).expect("response deadline");
        let packet = tunnel.harness.recv_packet(timeout).expect("response packet");
        assert!(packet.len() <= MTU, "packet exceeds TUN MTU");
        let (sequence, _, flags, bytes) = tcp_parts(&packet);
        assert_eq!(flags & 0x04, 0, "unexpected RST");
        if bytes.is_empty() {
            continue;
        }
        assert_eq!(sequence, next_server, "response byte order");
        assert!(bytes.len() <= expected, "payload exceeds peer MSS");
        max_payload = max_payload.max(bytes.len());
        received.extend_from_slice(bytes);
        next_server = next_server.wrapping_add(bytes.len() as u32);
        tunnel
            .harness
            .inject_packet(&client_packet(ipv6, CLIENT_SEQUENCE + 1, next_server, 0x10, None))
            .expect("data ACK");
    }
    assert_eq!(received, expected_payload);
    assert_eq!(max_payload, expected, "RFC default or explicit MSS must reach segmentation");
    tunnel.finish();
    peer.join().expect("SOCKS peer");
}

fn tcp_parts(packet: &[u8]) -> (u32, u32, u8, &[u8]) {
    let ip_len = if packet[0] >> 4 == 6 { 40 } else { usize::from(packet[0] & 15) * 4 };
    let tcp = &packet[ip_len..];
    let header_len = usize::from(tcp[12] >> 4) * 4;
    (
        u32::from_be_bytes(tcp[4..8].try_into().expect("sequence")),
        u32::from_be_bytes(tcp[8..12].try_into().expect("acknowledgment")),
        tcp[13],
        &tcp[header_len..],
    )
}

fn client_packet(ipv6: bool, sequence: u32, acknowledgment: u32, flags: u8, mss: Option<u16>) -> Vec<u8> {
    let ip_len = if ipv6 { 40 } else { 20 };
    let tcp_len = if mss.is_some() { 24 } else { 20 };
    let mut packet = vec![0; ip_len + tcp_len];
    if ipv6 {
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&(tcp_len as u16).to_be_bytes());
        packet[6] = 6;
        packet[7] = 64;
        packet[8..24].copy_from_slice(&"fd00::99".parse::<Ipv6Addr>().expect("client address").octets());
        packet[24..40].copy_from_slice(&"2001:db8::7".parse::<Ipv6Addr>().expect("target address").octets());
    } else {
        packet[0] = 0x45;
        let len = packet.len() as u16;
        packet[2..4].copy_from_slice(&len.to_be_bytes());
        packet[8] = 64;
        packet[9] = 6;
        packet[12..16].copy_from_slice(&[10, 0, 0, 99]);
        packet[16..20].copy_from_slice(&[203, 0, 113, 7]);
        let checksum = finish_checksum(checksum_sum(&packet[..20]));
        packet[10..12].copy_from_slice(&checksum.to_be_bytes());
    }
    let tcp = &mut packet[ip_len..];
    tcp[..2].copy_from_slice(&41000u16.to_be_bytes());
    tcp[2..4].copy_from_slice(&443u16.to_be_bytes());
    tcp[4..8].copy_from_slice(&sequence.to_be_bytes());
    tcp[8..12].copy_from_slice(&acknowledgment.to_be_bytes());
    tcp[12] = ((tcp_len / 4) as u8) << 4;
    tcp[13] = flags;
    tcp[14..16].copy_from_slice(&65535u16.to_be_bytes());
    if let Some(mss) = mss {
        tcp[20..24].copy_from_slice(&[2, 4, (mss >> 8) as u8, mss as u8]);
    }
    let addresses = if ipv6 { &packet[8..40] } else { &packet[12..20] };
    let checksum = finish_checksum(checksum_sum(addresses) + 6 + tcp_len as u32 + checksum_sum(&packet[ip_len..]));
    packet[ip_len + 16..ip_len + 18].copy_from_slice(&checksum.to_be_bytes());
    packet
}

fn checksum_sum(bytes: &[u8]) -> u32 {
    bytes.chunks(2).map(|pair| (u32::from(pair[0]) << 8) + u32::from(*pair.get(1).unwrap_or(&0))).sum()
}

fn finish_checksum(mut sum: u32) -> u16 {
    while sum > 65535 {
        sum = (sum & 65535) + (sum >> 16);
    }
    !(sum as u16)
}
