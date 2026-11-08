//! Interface restarts must not repeat the TUN-facing TCP sequence stream.

mod support;

use std::io;
use std::sync::{Arc, mpsc};
use std::time::Duration;

use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config;
use support::fake_tun::socketpair_tun;
use support::packets::{build_tcp_syn, tcp_flags, tcp_seq_ack};

fn first_tunnel_syn_ack() -> Vec<u8> {
    let (tun_fd, harness) = socketpair_tun().expect("packet socketpair");
    let config = test_tunnel_config("127.0.0.1:1080".parse().expect("loopback proxy"));
    let cancel = CancellationToken::new();
    let worker_cancel = cancel.clone();
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);
    let worker = std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("test runtime");
        runtime.block_on(run_tunnel_with_ready(config, tun_fd, worker_cancel, Arc::new(Stats::new()), move || {
            ready_tx.send(()).expect("report runtime readiness");
        }))
    });
    let packet = ready_rx.recv_timeout(Duration::from_secs(2)).map_err(io::Error::other).and_then(|()| {
        harness.inject_packet(&build_tcp_syn([10, 0, 0, 99], [127, 0, 0, 1], 40_000, 443))?;
        harness.recv_packet(Duration::from_secs(2))
    });
    cancel.cancel();
    worker.join().expect("join tunnel worker").expect("stop tunnel worker");
    packet.expect("SYN-ACK from fresh tunnel")
}

#[test]
fn fresh_tunnel_instances_do_not_repeat_initial_sequence_numbers() {
    let first = first_tunnel_syn_ack();
    let second = first_tunnel_syn_ack();
    assert_eq!(tcp_flags(&first), 0x12);
    assert_eq!(tcp_flags(&second), 0x12);
    let (first_isn, first_ack) = tcp_seq_ack(&first);
    let (second_isn, second_ack) = tcp_seq_ack(&second);
    assert_eq!((first_ack, second_ack), (1, 1), "both tunnels must accept the same first SYN");
    assert_ne!(first_isn, second_isn, "new tunnel interfaces must not replay their initial sequence stream");
}
