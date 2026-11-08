use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

use super::*;

fn loopback_target() -> crate::types::DomainTarget {
    crate::types::DomainTarget {
        host: "fixture.test".to_string(),
        connect_ip: Some("127.0.0.1".to_string()),
        connect_ips: Vec::new(),
        https_port: None,
        http_port: None,
        http_path: "/".to_string(),
        is_control: false,
        concurrency_probe: None,
    }
}

#[test]
fn std01_slow_drip_tls_worker_stops_at_the_scan_deadline() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind peer");
    let address = listener.local_addr().expect("peer address");
    let server = std::thread::spawn(move || {
        let (mut peer, _) = listener.accept().expect("accept worker");
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer read timeout");
        let mut first = [0u8; 5];
        peer.read_exact(&mut first).expect("ClientHello record");
        assert_eq!(&first[..2], &[0x16, 0x03]);
        // An incomplete record keeps making progress below each per-read timeout.
        peer.write_all(&[0x16, 0x03, 0x03, 0x40, 0x00]).expect("partial TLS record");
        let until = Instant::now() + Duration::from_secs(2);
        while Instant::now() < until && peer.write_all(&[0]).is_ok() {
            std::thread::sleep(Duration::from_millis(25));
        }
    });
    let started = Instant::now();
    let cell = execute_cell(
        &loopback_target(),
        "fixture",
        address,
        "chrome_stable",
        1,
        Some(started + Duration::from_millis(150)),
        "std01-loopback",
        &AtomicBool::new(false),
    );
    let elapsed = started.elapsed();
    server.join().expect("peer thread");

    assert!(elapsed < Duration::from_secs(1), "worker exceeded deadline: {elapsed:?}");
    assert_eq!(cell.status, ClassifierStatus::Skipped);
    assert!(cell.block_signals.is_empty(), "local deadline is not evidence of network blocking");
}

fn stalled_tls_peer(
    drip: bool,
    ready: std::sync::mpsc::Sender<()>,
) -> (std::net::SocketAddr, std::thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind peer");
    let address = listener.local_addr().expect("peer address");
    let server = std::thread::spawn(move || {
        let (mut peer, _) = listener.accept().expect("accept worker");
        peer.set_read_timeout(Some(Duration::from_secs(2))).expect("peer read timeout");
        peer.set_write_timeout(Some(Duration::from_secs(2))).expect("peer write timeout");
        let mut first = [0u8; 5];
        peer.read_exact(&mut first).expect("ClientHello record");
        assert_eq!(&first[..2], &[0x16, 0x03]);
        ready.send(()).expect("publish active TLS handshake");
        if drip {
            peer.write_all(&[0x16, 0x03, 0x03, 0x40, 0x00]).expect("partial TLS record");
            let until = Instant::now() + Duration::from_secs(9);
            while Instant::now() < until && peer.write_all(&[0]).is_ok() {
                std::thread::sleep(Duration::from_millis(25));
            }
        }
        // Drain the remaining ClientHello and observe closure of the owned socket.
        let mut remaining = Vec::new();
        match peer.read_to_end(&mut remaining) {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::ConnectionReset => {}
            Err(error) => panic!("worker did not close its socket: {error}"),
        }
    });
    (address, server)
}

#[test]
fn std01_silent_tls_worker_stops_at_the_scan_deadline() {
    let (ready, _ready_rx) = std::sync::mpsc::channel();
    let (address, server) = stalled_tls_peer(false, ready);
    let started = Instant::now();
    let cell = execute_cell(
        &loopback_target(),
        "fixture",
        address,
        "chrome_stable",
        1,
        Some(started + Duration::from_millis(150)),
        "std01-loopback",
        &AtomicBool::new(false),
    );
    let elapsed = started.elapsed();
    server.join().expect("peer thread");

    assert!(elapsed < Duration::from_secs(1), "worker exceeded deadline: {elapsed:?}");
    assert_eq!(cell.status, ClassifierStatus::Skipped);
    assert!(cell.block_signals.is_empty());
}

#[test]
fn std01_tls_workers_stop_after_scan_cancellation() {
    for drip in [false, true] {
        let (ready, ready_rx) = std::sync::mpsc::channel();
        let (address, server) = stalled_tls_peer(drip, ready);
        let cancel = AtomicBool::new(false);
        let started = Instant::now();
        let cell = std::thread::scope(|scope| {
            let cancel_ref = &cancel;
            scope.spawn(move || {
                ready_rx.recv_timeout(Duration::from_secs(2)).expect("handshake started");
                // Release publishes the scan owner's cancellation to the worker's Acquire load.
                cancel_ref.store(true, Ordering::Release);
            });
            execute_cell(&loopback_target(), "fixture", address, "chrome_stable", 1, None, "std01-loopback", &cancel)
        });
        let elapsed = started.elapsed();
        server.join().expect("peer thread");

        assert!(elapsed < Duration::from_secs(1), "worker ignored cancellation: {elapsed:?}");
        assert_eq!(cell.status, ClassifierStatus::Skipped);
        assert!(cell.block_signals.is_empty());
        assert_eq!(cell.skip_reason.as_deref(), Some("scan cancelled during cell"));
    }
}

#[test]
fn std01_slow_drip_tls_worker_has_a_cell_deadline_without_a_scan_deadline() {
    let (ready, _ready_rx) = std::sync::mpsc::channel();
    let (address, server) = stalled_tls_peer(true, ready);
    let started = Instant::now();
    let cell = execute_cell(
        &loopback_target(),
        "fixture",
        address,
        "chrome_stable",
        1,
        None,
        "std01-loopback",
        &AtomicBool::new(false),
    );
    let elapsed = started.elapsed();
    server.join().expect("peer thread");

    assert!(elapsed >= CELL_RESULT_DEADLINE, "the peer made progress within every idle timeout");
    assert!(elapsed < CELL_RESULT_DEADLINE + Duration::from_secs(1), "worker exceeded cell deadline: {elapsed:?}");
    assert_eq!(cell.status, ClassifierStatus::Skipped);
    assert!(cell.block_signals.is_empty());
}
