use std::io::{Read, Write};
use std::net::{TcpListener, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use super::{TargetAddress, TransportConfig, relay_udp_payload_observed};
use crate::util::with_scan_io_deadline;

#[test]
fn dpi24_filtered_udp_waits_for_matching_payload_and_generic_keeps_first_reply() {
    for experiment in [false, true] {
        for matching in [false, true] {
            let server = UdpSocket::bind("127.0.0.1:0").unwrap();
            let address = server.local_addr().unwrap();
            let worker = thread::spawn(move || {
                let mut bytes = [0; 16];
                let (_, peer) = server.recv_from(&mut bytes).unwrap();
                server.send_to(b"stale", peer).unwrap();
                server.send_to(b"valid", peer).unwrap();
            });
            let config = TransportConfig::Direct {
                route_experiment: experiment.then_some(crate::transport::RouteExperimentConfig {
                    stable_flow_attempts: 1,
                    diversity_buckets: 1,
                    diversity_on_failure_only: false,
                    session_seed: 987,
                }),
            };
            let targets = [TargetAddress::Ip(address.ip())];
            let result = if matching {
                super::relay_udp_payload_matching_observed(&targets, address.port(), &config, b"probe", &|bytes| {
                    bytes == b"valid"
                })
            } else {
                relay_udp_payload_observed(&targets, address.port(), &config, b"probe")
            }
            .unwrap();
            worker.join().unwrap();
            assert_eq!(result.payload, if matching { b"valid" } else { b"stale" });
        }
    }
}

#[test]
fn dpi24_rejected_udp_payloads_do_not_extend_the_receive_deadline() {
    for experiment in [false, true] {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let address = server.local_addr().unwrap();
        let worker = thread::spawn(move || {
            let mut bytes = [0; 16];
            let (_, peer) = server.recv_from(&mut bytes).unwrap();
            let until = Instant::now() + Duration::from_millis(200);
            while Instant::now() < until {
                let _ = server.send_to(b"stale", peer);
                thread::sleep(Duration::from_millis(2));
            }
        });
        let config = TransportConfig::Direct {
            route_experiment: experiment.then_some(crate::transport::RouteExperimentConfig {
                stable_flow_attempts: 1,
                diversity_buckets: 1,
                diversity_on_failure_only: false,
                session_seed: 988,
            }),
        };
        let started = Instant::now();
        let result = with_scan_io_deadline(Some(started + Duration::from_millis(80)), || {
            super::relay_udp_payload_matching_observed(
                &[TargetAddress::Ip(address.ip())],
                address.port(),
                &config,
                b"probe",
                &|_| false,
            )
        });
        let elapsed = started.elapsed();
        worker.join().unwrap();
        assert!(result.is_err());
        assert!(elapsed < Duration::from_millis(180), "rejected traffic extended wait to {elapsed:?}");
    }
}

#[test]
fn dpi24_socks_udp_filter_waits_for_matching_encapsulated_payload() {
    let relay = UdpSocket::bind("127.0.0.1:0").unwrap();
    let relay_addr = relay.local_addr().unwrap();
    let control = TcpListener::bind("127.0.0.1:0").unwrap();
    let control_addr = control.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = control.accept().unwrap();
        let mut greeting = [0; 3];
        stream.read_exact(&mut greeting).unwrap();
        assert_eq!(greeting, [5, 1, 0]);
        stream.write_all(&[5, 0]).unwrap();
        let mut associate = [0; 10];
        stream.read_exact(&mut associate).unwrap();
        assert_eq!(associate[1], 3);
        let port = relay_addr.port().to_be_bytes();
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).unwrap();
        let mut frame = [0; 256];
        let (size, peer) = relay.recv_from(&mut frame).unwrap();
        frame[size - 5..size].copy_from_slice(b"stale");
        relay.send_to(&frame[..size], peer).unwrap();
        frame[size - 5..size].copy_from_slice(b"valid");
        relay.send_to(&frame[..size], peer).unwrap();
    });
    let result = super::relay_udp_payload_matching_observed(
        &[TargetAddress::Ip("192.0.2.1".parse().unwrap())],
        443,
        &TransportConfig::Socks5 { host: "127.0.0.1".into(), port: control_addr.port(), credentials: None },
        b"probe",
        &|bytes| bytes == b"valid",
    )
    .unwrap();
    server.join().unwrap();
    assert_eq!(result.payload, b"valid");
}

#[test]
fn direct_and_route_udp_receive_the_complete_datagram() {
    use crate::transport::RouteExperimentConfig;

    for host in ["127.0.0.1", "::1"] {
        for route_experiment in [false, true] {
            let server = UdpSocket::bind((host, 0)).unwrap();
            server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let address = server.local_addr().unwrap();
            let worker = thread::spawn(move || {
                let mut query = [0; 5];
                for size in [2048, 2049, 3000] {
                    let (read, peer) = server.recv_from(&mut query).unwrap();
                    assert_eq!(&query[..read], b"probe");
                    server.send_to(&vec![42; size], peer).unwrap();
                }
            });
            let config = TransportConfig::Direct {
                route_experiment: route_experiment.then_some(RouteExperimentConfig {
                    stable_flow_attempts: 1,
                    diversity_buckets: 1,
                    diversity_on_failure_only: true,
                    session_seed: u64::from(address.port()),
                }),
            };
            for size in [2048, 2049, 3000] {
                let result =
                    relay_udp_payload_observed(&[TargetAddress::Ip(address.ip())], address.port(), &config, b"probe")
                        .unwrap();
                assert_eq!(result.payload, vec![42; size]);
            }
            worker.join().unwrap();
        }
    }
}

#[test]
fn cancelled_udp_exchange_does_not_send_another_datagram() {
    use std::sync::atomic::{AtomicBool, Ordering};

    let cancel = AtomicBool::new(false);
    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let address = server.local_addr().unwrap();
    thread::scope(|scope| {
        let worker = scope.spawn(|| {
            let mut packet = [0; 5];
            let (read, _) = server.recv_from(&mut packet).unwrap();
            assert_eq!(&packet[..read], b"probe");
            cancel.store(true, Ordering::Release);
            server.set_read_timeout(Some(Duration::from_millis(800))).unwrap();
            assert!(server.recv_from(&mut packet).is_err(), "sent after cancellation");
        });
        let started = Instant::now();
        let result = super::relay_udp_with_cancel(
            &[TargetAddress::Ip(address.ip())],
            address.port(),
            &TransportConfig::Direct { route_experiment: None },
            b"probe",
            &|_| true,
            Some(&cancel),
        );
        assert!(matches!(result, Err(super::TransportError::ProbeAborted("cancelled"))));
        assert!(started.elapsed() < Duration::from_millis(500));
        worker.join().unwrap();
    });
}

#[test]
fn socks_udp_control_shares_scan_deadline_across_fragmented_reads() {
    let proxy = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = proxy.local_addr().unwrap();
    let worker = thread::spawn(move || {
        let (mut control, _) = proxy.accept().unwrap();
        control.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut greeting = [0; 3];
        control.read_exact(&mut greeting).unwrap();
        assert_eq!(greeting, [5, 1, 0]);
        thread::sleep(Duration::from_millis(100));
        control.write_all(&[5]).unwrap();
        thread::sleep(Duration::from_millis(100));
        // The server is still preparing the second byte. The client must have closed its control
        // stream by the shared deadline, even though every individual read could still wait.
        assert_eq!(control.read(&mut greeting).unwrap(), 0);
    });
    let started = Instant::now();
    let result = with_scan_io_deadline(Some(started + Duration::from_millis(175)), || {
        relay_udp_payload_observed(
            &[TargetAddress::Ip("192.0.2.1".parse().unwrap())],
            443,
            &TransportConfig::Socks5 { host: address.ip().to_string(), port: address.port(), credentials: None },
            b"probe",
        )
    });
    assert!(matches!(result, Err(super::TransportError::ScanDeadlineExceeded)));
    assert!(started.elapsed() < Duration::from_millis(450));
    worker.join().unwrap();
}

#[test]
fn direct_udp_repeats_with_the_new_datagram_and_same_context() {
    use std::sync::atomic::AtomicBool;

    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let address = server.local_addr().unwrap();
    let worker = thread::spawn(move || {
        let mut bytes = [0; 5];
        let (size, first_peer) = server.recv_from(&mut bytes).unwrap();
        assert_eq!(&bytes[..size], b"first");
        let (size, repeat_peer) = server.recv_from(&mut bytes).unwrap();
        assert_eq!(repeat_peer, first_peer);
        assert_eq!(&bytes[..size], b"next");
        server.send_to(b"answer", repeat_peer).unwrap();
    });
    let cancel = AtomicBool::new(false);
    let (response, context) = super::relay_quic_probe_observed(
        &[TargetAddress::Ip(address.ip())],
        address.port(),
        &TransportConfig::Direct { route_experiment: None },
        &cancel,
        &mut || Some((b"first".to_vec(), b"next".to_vec())),
        &|request, answer| request == b"first" && answer == b"answer",
    )
    .unwrap();
    worker.join().unwrap();
    assert_eq!(context, b"first");
    assert_eq!(response.payload, b"answer");
}

#[test]
fn stable_route_attempts_do_not_accept_the_previous_context() {
    use crate::transport::RouteExperimentConfig;
    use std::sync::atomic::AtomicBool;

    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let address = server.local_addr().unwrap();
    let worker = thread::spawn(move || {
        let mut bytes = [0; 5];
        let (size, first_peer) = server.recv_from(&mut bytes).unwrap();
        assert_eq!(&bytes[..size], b"first");
        let (size, other_peer) = server.recv_from(&mut bytes).unwrap();
        assert_eq!(other_peer, first_peer);
        assert_eq!(&bytes[..size], b"other");
        server.send_to(b"first", other_peer).unwrap();
        server.send_to(b"other", other_peer).unwrap();
    });
    let cancel = AtomicBool::new(false);
    let mut contexts = [b"first", b"other"].into_iter();
    let (response, context) = super::relay_quic_probe_observed(
        &[TargetAddress::Ip(address.ip())],
        address.port(),
        &TransportConfig::Direct {
            route_experiment: Some(RouteExperimentConfig {
                stable_flow_attempts: 2,
                diversity_buckets: 1,
                diversity_on_failure_only: true,
                session_seed: u64::from(address.port()),
            }),
        },
        &cancel,
        &mut || contexts.next().map(|bytes| (bytes.to_vec(), b"repeat".to_vec())),
        &|request, answer| request == answer,
    )
    .unwrap();
    worker.join().unwrap();
    assert_eq!(context, b"other");
    assert_eq!(response.payload, b"other");
}

#[test]
fn candidate_fallback_preserves_the_matching_peer_failure() {
    use std::sync::atomic::AtomicBool;

    let first = UdpSocket::bind("127.0.0.1:0").unwrap();
    first.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let peer = first.local_addr().unwrap();
    let worker = thread::spawn(move || {
        let mut request = [0; 5];
        let (size, client) = first.recv_from(&mut request).unwrap();
        assert_eq!(&request[..size], b"probe");
        first.send_to(b"invalid", client).unwrap();
        // Keep the first receiver open until the exchange has genuinely waited for a reply.
        first.recv_from(&mut request).unwrap();
        thread::sleep(Duration::from_millis(700));
    });
    let cancel = AtomicBool::new(false);
    let mut probe = super::UdpProbe {
        cancel: Some(&cancel),
        prepare: &mut || Some(super::UdpDatagrams::fixed(b"probe")),
        matcher: &|_, response| response == b"valid",
    };
    let groups = [Ok(vec![peer, "127.0.0.1:0".parse().unwrap()])].into_iter();
    let result = super::relay_udp_probe(groups, &TransportConfig::Direct { route_experiment: None }, &mut probe);
    assert!(matches!(result, Err(super::TransportError::UdpRecvUnmatched)));
    worker.join().unwrap();
}
