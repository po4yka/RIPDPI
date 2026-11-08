mod support;

use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::{Arc, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config_with_misc;
use support::fake_tun::{FakeTunHarness, socketpair_tun};
use support::packets::{TCP_ACK, TCP_FIN, TCP_RST, TCP_SYN, build_tcp_ack, build_tcp_syn, tcp_flags, tcp_seq_ack};

const DEADLINE: Duration = Duration::from_secs(3);

struct Worker {
    cancel: CancellationToken,
    thread: Option<JoinHandle<std::io::Result<()>>>,
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(thread) = self.thread.take() {
            thread.join().expect("join tunnel").expect("stop tunnel");
        }
    }
}

fn receive_flags(packets: &FakeTunHarness, required: u8) -> Vec<u8> {
    let end = Instant::now() + DEADLINE;
    while Instant::now() < end {
        if let Ok(packet) = packets.recv_packet(Duration::from_millis(25))
            && tcp_flags(&packet) & required == required
        {
            return packet;
        }
    }
    panic!("expected TCP flags 0x{required:02x} without another app packet");
}

#[test]
fn relay_idle_after_upstream_fin_retires_without_another_app_packet() {
    check_idle_after_fin(false);
}

#[test]
fn terminal_failure_wakes_the_bridge_while_another_flow_is_in_time_wait() {
    check_idle_after_fin(true);
}

fn accept_half_closed_peer(listener: &TcpListener) -> TcpStream {
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
    socket.shutdown(Shutdown::Write).expect("upstream half-close");
    socket
}

fn check_idle_after_fin(retain_time_wait: bool) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback SOCKS listener");
    let proxy = listener.local_addr().expect("listener endpoint");
    let (closed_tx, closed_rx) = mpsc::channel();
    let server = std::thread::spawn(move || {
        if retain_time_wait {
            let mut first = accept_half_closed_peer(&listener);
            assert_eq!(first.read(&mut [0]).expect("first flow completes normally"), 0);
        }
        let mut socket = accept_half_closed_peer(&listener);
        let mut received = Vec::new();
        let result = socket.read_to_end(&mut received);
        closed_tx.send((result, received)).expect("report peer EOF");
    });

    let (fd, packets) = socketpair_tun().expect("packet socketpair");
    let cancel = CancellationToken::new();
    let worker_cancel = cancel.clone();
    let (ready_tx, ready_rx) = mpsc::channel();
    let thread = std::thread::spawn(move || {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("single-owner tunnel runtime")
            .block_on(run_tunnel_with_ready(
                test_tunnel_config_with_misc(proxy, |misc| misc.tcp_read_write_timeout = 200),
                fd,
                worker_cancel,
                Arc::new(Stats::new()),
                move || ready_tx.send(()).expect("runtime ready"),
            ))
    });
    let _worker = Worker { cancel, thread: Some(thread) };
    ready_rx.recv_timeout(DEADLINE).expect("tunnel readiness");
    let client = [10, 0, 0, 99];
    let target = [127, 0, 0, 1];
    if retain_time_wait {
        packets.inject_packet(&build_tcp_syn(client, target, 41000, 443)).expect("first SYN");
        let syn_ack = receive_flags(&packets, TCP_SYN | TCP_ACK);
        packets
            .inject_packet(&build_tcp_ack(client, target, 41000, 443, 1, tcp_seq_ack(&syn_ack).0.wrapping_add(1)))
            .expect("first handshake ACK");
        let fin = receive_flags(&packets, TCP_FIN | TCP_ACK);
        let builder = etherparse::PacketBuilder::ipv4(client, target, 64)
            .tcp(41000, 443, 1, 65535)
            .ack(tcp_seq_ack(&fin).0.wrapping_add(1))
            .fin();
        let mut packet = Vec::with_capacity(builder.size(0));
        builder.write(&mut packet, &[]).expect("valid first FIN");
        packets.inject_packet(&packet).expect("first FIN ACK");
        let ack = receive_flags(&packets, TCP_ACK);
        assert_eq!(tcp_seq_ack(&ack).1, 2, "first flow entered TIME-WAIT after both FINs");
        assert_eq!(tcp_flags(&ack) & TCP_RST, 0);
    }
    packets.inject_packet(&build_tcp_syn(client, target, 41001, 443)).expect("SYN");
    let syn_ack = receive_flags(&packets, TCP_SYN | TCP_ACK);
    packets
        .inject_packet(&build_tcp_ack(client, target, 41001, 443, 1, tcp_seq_ack(&syn_ack).0.wrapping_add(1)))
        .expect("handshake ACK");
    let fin = receive_flags(&packets, TCP_FIN | TCP_ACK);
    packets
        .inject_packet(&build_tcp_ack(client, target, 41001, 443, 1, tcp_seq_ack(&fin).0.wrapping_add(1)))
        .expect("ACK upstream FIN");

    let (result, received) = closed_rx.recv_timeout(DEADLINE).expect("idle expiry closes SOCKS peer");
    server.join().expect("join SOCKS peer");
    result.expect("SOCKS peer receives EOF");
    assert!(received.is_empty());
    let reset = receive_flags(&packets, TCP_RST);
    let headers = etherparse::PacketHeaders::from_ip_slice(&reset).expect("valid reset packet");
    let Some(etherparse::NetHeaders::Ipv4(ip, _)) = headers.net else { panic!("IPv4 reset") };
    let Some(etherparse::TransportHeader::Tcp(tcp)) = headers.transport else { panic!("TCP reset") };
    assert_eq!((ip.source, ip.destination), (target, client));
    assert_eq!((tcp.source_port, tcp.destination_port), (443, 41001));
}
