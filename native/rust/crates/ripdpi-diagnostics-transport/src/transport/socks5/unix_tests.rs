use super::*;
use std::io;
use std::os::fd::AsFd;
use std::os::unix::net::{UnixDatagram, UnixListener};
use std::sync::atomic::{AtomicU32, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_socks5_core::descriptor::{is_unix_datagram, recv_fd};
use ripdpi_socks5_core::udp_feedback::{self, Frame, RemoteError};

use crate::transport::{TransportConfig, connect_transport_observed, relay_udp_payload_matching_observed};
use crate::util::with_scan_io_deadline;

const NONCE: [u8; 16] = *b"abcdefghijklmnop";
const TARGET: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 53)), 53);

fn socket_path() -> String {
    static NEXT: AtomicU32 = AtomicU32::new(0);
    format!("/tmp/rdpi-dt-{}-{}.sock", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed))
}

struct Fixture {
    listener: UnixListener,
    path: InPathUnixPath,
}

impl Fixture {
    fn new() -> Self {
        let path = socket_path();
        let listener = UnixListener::bind(&path).unwrap();
        Self { listener, path: InPathUnixPath::new(path) }
    }

    fn transport(&self) -> TransportConfig {
        TransportConfig::Socks5Unix { path: self.path.clone(), credentials: Socks5Credentials::new("user", "token") }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(self.path.as_str());
    }
}

fn authenticate(stream: &mut UnixStream) {
    stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let mut hello = [0; 3];
    stream.read_exact(&mut hello).unwrap();
    assert_eq!(hello, [5, 1, 2]);
    stream.write_all(&[5, 2]).unwrap();
    let mut credentials = [0; 12];
    stream.read_exact(&mut credentials).unwrap();
    assert_eq!(&credentials, b"\x01\x04user\x05token");
    stream.write_all(&[1, 0]).unwrap();
}

fn expect_private_request(stream: &mut UnixStream) {
    let mut request = [0; 10];
    stream.read_exact(&mut request).unwrap();
    assert_eq!(request, [5, 0x80, 0, 1, 0, 0, 0, 0, 0, 0]);
}

fn accept_private(stream: &mut UnixStream) {
    stream.write_all(&[&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0][..], &udp_feedback::encode_preface(&NONCE)].concat()).unwrap();
}

fn receive_datagram_socket(stream: &UnixStream) -> UnixDatagram {
    let fd = recv_fd(stream.as_fd()).unwrap();
    assert!(is_unix_datagram(fd.as_fd()).unwrap());
    UnixDatagram::from(fd)
}

fn udp_body(payload: &[u8]) -> Vec<u8> {
    [&[0, 0, 0, 1, 192, 0, 2, 53, 0, 53][..], payload].concat()
}

fn relay(transport: &TransportConfig, accept: &dyn Fn(&[u8]) -> bool) -> Result<Vec<u8>, TransportError> {
    relay_udp_payload_matching_observed(&[TargetAddress::Ip(TARGET.ip())], TARGET.port(), transport, b"probe", accept)
        .map(|result| {
            assert_eq!(result.local_addr, None);
            assert_eq!(result.connected_addr, Some(TARGET));
            result.payload
        })
}

#[test]
fn tcp_connect_authenticates_and_relays_over_a_unix_stream() {
    let fixture = Fixture::new();
    let server = thread::spawn({
        let listener = fixture.listener.try_clone().unwrap();
        move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            let mut request = [0; 10];
            control.read_exact(&mut request).unwrap();
            assert_eq!(request, [5, 1, 0, 1, 192, 0, 2, 7, 1, 187]);
            control.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
            let mut ping = [0; 4];
            control.read_exact(&mut ping).unwrap();
            assert_eq!(&ping, b"ping");
            control.write_all(b"pong").unwrap();
        }
    });
    let mut result =
        connect_transport_observed(&[TargetAddress::Ip("192.0.2.7".parse().unwrap())], 443, &fixture.transport())
            .unwrap();
    assert!(matches!(result.stream, ProbeStream::Unix(_)));
    assert_eq!(result.local_addr, None);
    assert_eq!(result.connected_addr, Some("192.0.2.7:443".parse().unwrap()));
    assert!(result.stream.read_timeout().unwrap().is_some());
    assert!(result.stream.write_timeout().unwrap().is_some());
    result.stream.write_all(b"ping").unwrap();
    let mut pong = [0; 4];
    result.stream.read_exact(&mut pong).unwrap();
    assert_eq!(&pong, b"pong");
    server.join().unwrap();
}

#[test]
fn tcp_connect_failure_does_not_name_the_path() {
    let fixture = Fixture::new();
    let transport = fixture.transport();
    let path = fixture.path.as_str().to_string();
    drop(fixture);
    let error =
        connect_transport_observed(&[TargetAddress::Ip("192.0.2.7".parse().unwrap())], 443, &transport).unwrap_err();
    assert_eq!(error.stage, TransportFailureStage::TcpConnect);
    assert!(!error.message.contains(&path));
    assert!(!format!("{transport:?}").contains(&path));
}

#[test]
fn tcp_connect_stops_at_the_scan_deadline_when_the_proxy_stalls() {
    let fixture = Fixture::new();
    let server = thread::spawn({
        let listener = fixture.listener.try_clone().unwrap();
        move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            let mut request = [0; 10];
            control.read_exact(&mut request).unwrap();
            let mut end = [0];
            let _ = control.read(&mut end);
        }
    });
    let started = Instant::now();
    let error = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(300)), || {
        connect_transport_observed(&[TargetAddress::Ip("192.0.2.7".parse().unwrap())], 443, &fixture.transport())
            .unwrap_err()
    });
    assert_eq!(error.stage, TransportFailureStage::Socks5Negotiation);
    assert!(started.elapsed() < Duration::from_secs(2));
    server.join().unwrap();
}

#[test]
fn private_udp_passes_a_datagram_socket_and_exchanges_frames() {
    let fixture = Fixture::new();
    let server = thread::spawn({
        let listener = fixture.listener.try_clone().unwrap();
        move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            expect_private_request(&mut control);
            accept_private(&mut control);
            let datagrams = receive_datagram_socket(&control);
            datagrams.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut bytes = [0; 128];
            let size = datagrams.recv(&mut bytes).unwrap();
            let Frame::Request { id, payload } = udp_feedback::decode(&bytes[..size], &NONCE).unwrap() else {
                panic!("expected a request frame");
            };
            assert_eq!((id, payload), (1, udp_body(b"probe").as_slice()));
            datagrams.send(&udp_feedback::encode_response(&NONCE, &udp_body(b"stray")).unwrap()).unwrap();
            datagrams.send(b"malformed").unwrap();
            datagrams.send(&udp_feedback::encode_response(&NONCE, &udp_body(b"answer")).unwrap()).unwrap();
            let mut end = [0];
            assert_eq!(control.read(&mut end).unwrap(), 0);
        }
    });
    let payload = relay(&fixture.transport(), &|bytes| bytes == b"answer").unwrap();
    assert_eq!(payload, b"answer");
    server.join().unwrap();
}

#[test]
fn private_udp_error_frame_maps_to_connection_refused() {
    let fixture = Fixture::new();
    let server = thread::spawn({
        let listener = fixture.listener.try_clone().unwrap();
        move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            expect_private_request(&mut control);
            accept_private(&mut control);
            let datagrams = receive_datagram_socket(&control);
            datagrams.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            datagrams.recv(&mut [0; 128]).unwrap();
            let error = RemoteError {
                source: "198.51.100.1".parse().unwrap(),
                target: TARGET,
                icmp_type: 3,
                icmp_code: 3,
                info: 0,
            };
            datagrams.send(&udp_feedback::encode_error(&NONCE, 1, &error).unwrap()).unwrap();
            let mut end = [0];
            let _ = control.read(&mut end);
        }
    });
    match relay(&fixture.transport(), &|_| true) {
        Err(TransportError::UdpRecvFailed { kind: io::ErrorKind::ConnectionRefused, .. }) => {}
        other => panic!("unexpected result: {other:?}"),
    }
    server.join().unwrap();
}

#[test]
fn refused_private_udp_fails_without_a_second_attempt() {
    let fixture = Fixture::new();
    let server = thread::spawn({
        let listener = fixture.listener.try_clone().unwrap();
        move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            expect_private_request(&mut control);
            control.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
            drop(control);
            listener.set_nonblocking(true).unwrap();
            thread::sleep(Duration::from_millis(50));
            assert_eq!(listener.accept().unwrap_err().kind(), io::ErrorKind::WouldBlock);
        }
    });
    assert!(matches!(relay(&fixture.transport(), &|_| true), Err(TransportError::SocksUdpAssociateFailed(7))));
    server.join().unwrap();
}

#[test]
fn unix_connect_to_a_full_backlog_fails_within_the_scan_deadline() {
    let path = socket_path();
    let address = SockAddr::unix(&path).unwrap();
    let listener = Socket::new(Domain::UNIX, Type::STREAM, None).unwrap();
    listener.bind(&address).unwrap();
    listener.listen(0).unwrap();
    let mut held = Vec::new();
    for _ in 0..512 {
        let client = Socket::new(Domain::UNIX, Type::STREAM, None).unwrap();
        client.set_nonblocking(true).unwrap();
        if client.connect(&address).is_err() {
            break;
        }
        held.push(client);
    }
    assert!(held.len() < 512, "the listener backlog never filled");
    let transport = TransportConfig::Socks5Unix { path: InPathUnixPath::new(path.clone()), credentials: None };
    let started = Instant::now();
    let error = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(400)), || {
        connect_transport_observed(&[TargetAddress::Ip("192.0.2.7".parse().unwrap())], 443, &transport).unwrap_err()
    });
    let _ = std::fs::remove_file(&path);
    assert_eq!(error.stage, TransportFailureStage::TcpConnect);
    assert!(matches!(error.message.as_str(), "timed out" | "connection refused"), "{}", error.message);
    assert!(started.elapsed() < Duration::from_secs(2));
}

#[test]
fn unix_connect_checks_the_abort_flag_before_connecting() {
    let fixture = Fixture::new();
    let abort = || Some("cancelled");
    let error = connect_unix(&fixture.path, Some(&abort)).unwrap_err();
    assert_eq!(error.stage, TransportFailureStage::TcpConnect);
    assert_eq!(error.message, "probe_aborted:cancelled");
}

#[test]
fn bad_or_missing_preface_fails_and_no_descriptor_is_sent() {
    for preface in [Some([&b"NOPE"[..], &NONCE].concat()), None] {
        let fixture = Fixture::new();
        let server = thread::spawn({
            let listener = fixture.listener.try_clone().unwrap();
            move || {
                let (mut control, _) = listener.accept().unwrap();
                authenticate(&mut control);
                expect_private_request(&mut control);
                control.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
                if let Some(preface) = preface {
                    control.write_all(&preface).unwrap();
                }
                assert!(recv_fd(control.as_fd()).is_err());
            }
        });
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(400)), || {
            relay(&fixture.transport(), &|_| true)
        });
        assert!(result.is_err());
        server.join().unwrap();
    }
}

#[test]
fn response_with_the_wrong_nonce_ends_like_an_unanswered_datagram() {
    let mut outcomes = Vec::new();
    for wrong_nonce in [false, true] {
        let fixture = Fixture::new();
        let server = thread::spawn({
            let listener = fixture.listener.try_clone().unwrap();
            move || {
                let (mut control, _) = listener.accept().unwrap();
                authenticate(&mut control);
                expect_private_request(&mut control);
                accept_private(&mut control);
                let datagrams = receive_datagram_socket(&control);
                datagrams.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                datagrams.recv(&mut [0; 128]).unwrap();
                if wrong_nonce {
                    let frame = udp_feedback::encode_response(b"zzzzzzzzzzzzzzzz", &udp_body(b"answer")).unwrap();
                    datagrams.send(&frame).unwrap();
                }
                let mut end = [0];
                let _ = control.read(&mut end);
            }
        });
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(400)), || {
            relay(&fixture.transport(), &|_| true)
        });
        outcomes.push(result.unwrap_err().to_string());
        server.join().unwrap();
    }
    assert_eq!(outcomes[0], outcomes[1]);
}
