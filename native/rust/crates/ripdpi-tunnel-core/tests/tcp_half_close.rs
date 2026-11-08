mod support;

use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener};
use std::sync::{Arc, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config;
use support::fake_tun::{FakeTunHarness, socketpair_tun};
use support::packets::{TCP_ACK, TCP_FIN, TCP_RST, TCP_SYN, tcp_flags, tcp_payload, tcp_seq_ack};

const CLIENT: [u8; 4] = [10, 0, 0, 99];
const TARGET: [u8; 4] = [127, 0, 0, 1];
const PORT: u16 = 41000;
const DEADLINE: Duration = Duration::from_secs(3);

struct Tunnel {
    packets: FakeTunHarness,
    cancel: CancellationToken,
    worker: Option<JoinHandle<std::io::Result<()>>>,
}

impl Tunnel {
    fn start(proxy: std::net::SocketAddr) -> Self {
        let (fd, packets) = socketpair_tun().expect("packet socketpair");
        let cancel = CancellationToken::new();
        let worker_cancel = cancel.clone();
        let (ready_tx, ready_rx) = mpsc::channel();
        let worker = std::thread::spawn(move || {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("single-owner tunnel runtime")
                .block_on(run_tunnel_with_ready(
                    test_tunnel_config(proxy),
                    fd,
                    worker_cancel,
                    Arc::new(Stats::new()),
                    move || ready_tx.send(()).expect("runtime ready"),
                ))
        });
        ready_rx.recv_timeout(DEADLINE).expect("tunnel readiness");
        Self { packets, cancel, worker: Some(worker) }
    }

    fn send(&self, seq: u32, ack: u32, payload: &[u8], fin: bool, syn: bool) {
        let mut builder = etherparse::PacketBuilder::ipv4(CLIENT, TARGET, 64).tcp(PORT, 443, seq, 65535);
        if syn {
            builder = builder.syn();
        } else {
            builder = builder.ack(ack);
        }
        if fin {
            builder = builder.fin();
        }
        let mut packet = Vec::with_capacity(builder.size(payload.len()));
        builder.write(&mut packet, payload).expect("valid TCP packet");
        let end = Instant::now() + DEADLINE;
        loop {
            match self.packets.inject_packet(&packet) {
                Ok(()) => return,
                Err(error) if error.raw_os_error() == Some(55) && Instant::now() < end => {
                    std::thread::sleep(Duration::from_millis(1));
                }
                Err(error) => panic!("inject TCP packet: {error}"),
            }
        }
    }

    fn recv_until(&self, predicate: impl Fn(&[u8]) -> bool) -> Vec<u8> {
        let end = Instant::now() + DEADLINE;
        while Instant::now() < end {
            if let Ok(packet) = self.packets.recv_packet(Duration::from_millis(25)) {
                assert_eq!(tcp_flags(&packet) & TCP_RST, 0, "unexpected TCP reset");
                if predicate(&packet) {
                    return packet;
                }
            }
        }
        panic!("expected TCP packet before deadline");
    }
}

impl Drop for Tunnel {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(worker) = self.worker.take() {
            worker.join().expect("join tunnel").expect("stop tunnel");
        }
    }
}

fn remote_first_half_close(payload: Vec<u8>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback SOCKS listener");
    let proxy = listener.local_addr().expect("listener endpoint");
    let (received_tx, received_rx) = mpsc::channel();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().expect("SOCKS connection");
        socket.set_read_timeout(Some(DEADLINE)).expect("SOCKS read deadline");
        let mut greeting = [0; 3];
        socket.read_exact(&mut greeting).expect("SOCKS greeting");
        assert_eq!(greeting, [5, 1, 0]);
        socket.write_all(&[5, 0]).expect("SOCKS method");
        let mut request = [0; 10];
        socket.read_exact(&mut request).expect("SOCKS CONNECT");
        assert_eq!(&request[..4], &[5, 1, 0, 1]);
        socket.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).expect("SOCKS success");
        socket.write_all(b"remote-half").expect("upstream response");
        socket.shutdown(Shutdown::Write).expect("upstream half-close");
        let mut received = Vec::new();
        let result = socket.read_to_end(&mut received);
        received_tx.send((result, received)).expect("report upstream bytes");
    });

    let tunnel = Tunnel::start(proxy);
    tunnel.send(0, 0, &[], false, true);
    let syn_ack = tunnel.recv_until(|packet| tcp_flags(packet) & (TCP_SYN | TCP_ACK) == TCP_SYN | TCP_ACK);
    let mut upstream_seq = tcp_seq_ack(&syn_ack).0.wrapping_add(1);
    tunnel.send(1, upstream_seq, &[], false, false);

    let mut response = Vec::new();
    loop {
        let packet = tunnel.recv_until(|packet| !tcp_payload(packet).is_empty() || tcp_flags(packet) & TCP_FIN != 0);
        assert_eq!(tcp_seq_ack(&packet).0, upstream_seq);
        response.extend_from_slice(tcp_payload(&packet));
        upstream_seq = upstream_seq.wrapping_add(tcp_payload(&packet).len() as u32);
        if tcp_flags(&packet) & TCP_FIN != 0 {
            upstream_seq = upstream_seq.wrapping_add(1);
            tunnel.send(1, upstream_seq, &[], false, false);
            break;
        }
        tunnel.send(1, upstream_seq, &[], false, false);
    }
    assert_eq!(response, b"remote-half");

    let mut client_seq = 1;
    for chunk in payload.chunks(1000) {
        tunnel.send(client_seq, upstream_seq, chunk, false, false);
        client_seq = client_seq.wrapping_add(chunk.len() as u32);
    }
    tunnel.send(client_seq, upstream_seq, &[], true, false);
    let (result, received) = received_rx.recv_timeout(DEADLINE).expect("upstream EOF");
    server.join().expect("join SOCKS peer");
    result.expect("normal upstream EOF");
    assert!(
        received == payload,
        "the open send direction must drain before EOF: received {} of {} bytes",
        received.len(),
        payload.len()
    );

    tunnel.recv_until(|packet| tcp_flags(packet) & TCP_ACK != 0 && tcp_seq_ack(packet).1 == client_seq + 1);
    tunnel.packets.drain();
    tunnel.send(client_seq, upstream_seq, &[], true, false);
    tunnel.recv_until(|packet| tcp_flags(packet) & TCP_ACK != 0 && tcp_seq_ack(packet).1 == client_seq + 1);
}

#[test]
fn remote_first_half_close_drains_a_short_tail_and_acknowledges_repeated_fin() {
    remote_first_half_close(b"request-after-fin".to_vec());
}

#[test]
fn remote_first_half_close_drains_more_than_one_bridge_chunk() {
    remote_first_half_close((0..10_000).map(|value| (value % 251) as u8).collect());
}
