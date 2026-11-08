use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use std::{
    io::{Read, Write},
    net::TcpListener,
    sync::{Arc, atomic::Ordering, mpsc},
    time::{Duration, Instant},
};
use tokio_util::sync::CancellationToken;
mod support;
#[test]
fn local_udp_setup_failure_returns_truthful_icmp_for_both_families() {
    for family in [4, 6] {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let (seen_tx, seen_rx) = mpsc::channel();
        let server = std::thread::spawn(move || {
            let (mut tcp, _) = listener.accept().unwrap();
            tcp.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            let mut greeting = [0u8; 3];
            tcp.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, 0]);
            tcp.write_all(&[5, 0]).unwrap();
            let mut req = [0u8; 10];
            tcp.read_exact(&mut req).unwrap();
            assert_eq!(&req[..4], &[5, 3, 0, 1]);
            tcp.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
            seen_tx.send(()).unwrap();
        });
        let (tun_fd, harness) = support::fake_tun::socketpair_tun().unwrap();
        let mut config = support::config::test_tunnel_config(addr);
        if family == 6 {
            Arc::make_mut(&mut config).tunnel.ipv6 = Some("fd00::1/64".to_string());
        }
        let cancel = CancellationToken::new();
        let stop = cancel.clone();
        let stats = Arc::new(Stats::new());
        let measured = stats.clone();
        let (ready_tx, ready_rx) = mpsc::channel();
        let worker = std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
            rt.block_on(run_tunnel_with_ready(config, tun_fd, stop, stats, move || {
                ready_tx.send(()).unwrap();
            }))
        });
        ready_rx.recv_timeout(Duration::from_secs(3)).unwrap();
        // Scapy 2.6.1: headers deliberately differ from serializer defaults.
        let input: &[u8] = if family == 4 {
            include_bytes!("fixtures/udp-error-ipv4.bin")
        } else {
            include_bytes!("fixtures/udp-error-ipv6.bin")
        };
        harness.inject_packet(input).unwrap();
        seen_rx.recv_timeout(Duration::from_secs(3)).unwrap();
        server.join().unwrap();
        let received = harness.recv_packet(Duration::from_millis(500));

        assert!(measured.tx_packets.load(Ordering::Relaxed) >= 1);
        let end = Instant::now();
        cancel.cancel();
        assert!(worker.join().unwrap().is_ok());
        assert!(end.elapsed() < Duration::from_secs(2));
        let response = received.expect("local UDP failure must reach TUN");
        if family == 4 {
            let ip = smoltcp::wire::Ipv4Packet::new_checked(&response[..]).unwrap();
            assert_eq!(ip.src_addr(), std::net::Ipv4Addr::new(10, 0, 0, 1));
            assert_eq!(ip.dst_addr(), std::net::Ipv4Addr::new(10, 0, 0, 2));
            assert!(ip.verify_checksum());
            let icmp = smoltcp::wire::Icmpv4Packet::new_checked(ip.payload()).unwrap();
            assert!(icmp.verify_checksum());
            assert_eq!(&ip.payload()[..2], &[3, 1]);
            assert!(ip.payload().len() >= 36);
            assert_eq!(&ip.payload()[8..], &input[..ip.payload().len() - 8]);
        } else {
            let ip = smoltcp::wire::Ipv6Packet::new_checked(&response[..]).unwrap();
            assert_eq!(ip.src_addr(), "fd00::1".parse::<std::net::Ipv6Addr>().unwrap());
            assert_eq!(ip.dst_addr(), "fd00::2".parse::<std::net::Ipv6Addr>().unwrap());
            let icmp = smoltcp::wire::Icmpv6Packet::new_checked(ip.payload()).unwrap();
            assert!(icmp.verify_checksum(&ip.src_addr(), &ip.dst_addr()));
            assert_eq!(&ip.payload()[..2], &[1, 3]);
            assert_eq!(&ip.payload()[8..], input);
        }
    }
}
