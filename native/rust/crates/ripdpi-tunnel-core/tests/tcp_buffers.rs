//! Native buffer configuration must reach the TUN-facing TCP receive window.

mod support;

use std::sync::{Arc, mpsc};
use std::time::Duration;

use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use tokio_util::sync::CancellationToken;

use support::config::test_tunnel_config_with_misc;
use support::fake_tun::socketpair_tun;
use support::packets::{build_tcp_syn, tcp_flags};

#[test]
fn configured_tcp_buffers_bound_the_synack_receive_window() {
    for capacity in [1u32, 1024, 65_536] {
        let config = test_tunnel_config_with_misc("127.0.0.1:1080".parse().unwrap(), |misc| {
            misc.tcp_buffer_size = capacity;
        });
        let (fd, tun) = socketpair_tun().unwrap();
        let cancel = CancellationToken::new();
        let worker_cancel = cancel.clone();
        let (ready_tx, ready_rx) = mpsc::channel();
        let worker =
            std::thread::spawn(move || {
                tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(
                    run_tunnel_with_ready(config, fd, worker_cancel, Arc::new(Stats::new()), move || {
                        ready_tx.send(()).unwrap();
                    }),
                )
            });
        ready_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        tun.inject_packet(&build_tcp_syn([10, 0, 0, 99], [203, 0, 113, 7], 40_000, 443)).unwrap();
        let response = tun.recv_packet(Duration::from_secs(2));
        cancel.cancel();
        worker.join().unwrap().unwrap();
        let response = response.unwrap();
        assert_eq!(tcp_flags(&response) & 0x12, 0x12);
        let ip_header_len = usize::from(response[0] & 0x0f) * 4;
        let window = u16::from_be_bytes([response[ip_header_len + 14], response[ip_header_len + 15]]);
        // The client did not offer window scaling, so this is the byte capacity on the wire.
        assert_eq!(u32::from(window), capacity.min(u32::from(u16::MAX)), "configured capacity {capacity}");
    }
}
