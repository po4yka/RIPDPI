use super::*;
use std::io;
use std::net::TcpListener;
use std::thread;
use std::time::{Duration, Instant};

const NONCE: &[u8; 16] = b"abcdefghijklmnop";
const TARGET: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 53)), 53);

fn authenticate(stream: &mut TcpStream) {
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

fn association(stream: &mut TcpStream, private: bool) -> SocketAddr {
    let mut request = [0; 10];
    stream.read_exact(&mut request).unwrap();
    assert_eq!(&request[..4], &[5, if private { 0x80 } else { 3 }, 0, 1]);
    let source = SocketAddr::from((
        Ipv4Addr::new(request[4], request[5], request[6], request[7]),
        u16::from_be_bytes([request[8], request[9]]),
    ));
    if private {
        assert_eq!(source.ip(), stream.peer_addr().unwrap().ip());
        assert_ne!(source.port(), 0);
    }
    source
}

fn accept_private(stream: &mut TcpStream, relay: SocketAddr) {
    stream.write_all(&[&[5, 0, 0, 1, 127, 0, 0, 1][..], &relay.port().to_be_bytes(), b"RUF1", NONCE].concat()).unwrap();
}

fn frame(kind: u8, body: &[u8]) -> Vec<u8> {
    [&b"RUF1"[..], &[kind, 0], &(body.len() as u16).to_be_bytes(), NONCE, &[0; 8], body].concat()
}

fn request_body() -> Vec<u8> {
    [&[0, 0, 0, 1, 192, 0, 2, 53, 0, 53][..], b"probe"].concat()
}

fn run_proxy(address: SocketAddr) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    let credentials = Socks5Credentials::new("user", "token").unwrap();
    relay_udp_via_socks5(&address.ip().to_string(), address.port(), TARGET, b"probe", Some(&credentials))
}

#[test]
fn authenticated_local_udp_uses_session_envelope_and_ignores_unrelated_frames() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    relay.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let server = thread::spawn(move || {
        let (mut control, _) = listener.accept().unwrap();
        authenticate(&mut control);
        let source = association(&mut control, true);
        accept_private(&mut control, relay.local_addr().unwrap());
        let mut bytes = [0; 128];
        let (size, sender) = relay.recv_from(&mut bytes).unwrap();
        assert_eq!(sender, source);
        let expected = [b"RUF1".as_slice(), &[1, 0, 0, 15], NONCE, &1u64.to_be_bytes(), &request_body()].concat();
        assert_eq!(&bytes[..size], expected);
        let body = [&[0, 0, 0, 1, 192, 0, 2, 53, 0, 53][..], b"answer"].concat();
        let valid = frame(2, &body);
        let mut wrong_nonce = valid.clone();
        wrong_nonce[8] ^= 1;
        relay.send_to(&wrong_nonce, source).unwrap();
        relay.send_to(&frame(1, &body), source).unwrap();
        relay.send_to(b"malformed", source).unwrap();
        relay.send_to(&valid, source).unwrap();
        let mut end = [0];
        assert_eq!(control.read(&mut end).unwrap(), 0);
    });
    assert_eq!(run_proxy(address).unwrap().0, b"answer");
    server.join().unwrap();
}

#[test]
fn unsupported_local_udp_reauthenticates_before_standard_fallback() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    relay.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let server = thread::spawn(move || {
        let (mut first, _) = listener.accept().unwrap();
        authenticate(&mut first);
        association(&mut first, true);
        first.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
        let mut end = [0];
        assert_eq!(first.read(&mut end).unwrap(), 0);
        let (mut second, _) = listener.accept().unwrap();
        authenticate(&mut second);
        assert_eq!(association(&mut second, false), SocketAddr::from(([0, 0, 0, 0], 0)));
        second
            .write_all(&[&[5, 0, 0, 1, 127, 0, 0, 1][..], &relay.local_addr().unwrap().port().to_be_bytes()].concat())
            .unwrap();
        let mut bytes = [0; 128];
        let (size, source) = relay.recv_from(&mut bytes).unwrap();
        assert_eq!(&bytes[..size], request_body());
        relay.send_to(&[&[0, 0, 0, 1, 192, 0, 2, 53, 0, 53][..], b"legacy"].concat(), source).unwrap();
        assert_eq!(second.read(&mut end).unwrap(), 0);
    });
    assert_eq!(run_proxy(address).unwrap().0, b"legacy");
    server.join().unwrap();
}

#[test]
fn denied_or_malformed_private_association_does_not_downgrade() {
    for reply in [
        vec![5, 2, 0, 1, 0, 0, 0, 0, 0, 0],
        vec![4, 7, 0, 1, 0, 0, 0, 0, 0, 0],
        vec![5, 7, 1, 1, 0, 0, 0, 0, 0, 0],
        vec![5, 7, 0, 1],
        [vec![5, 0, 0, 1, 127, 0, 0, 1, 18, 52], b"NOPEabcdefghijklmnop".to_vec()].concat(),
    ] {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            association(&mut control, true);
            control.write_all(&reply).unwrap();
            drop(control);
            listener.set_nonblocking(true).unwrap();
            thread::sleep(Duration::from_millis(20));
            assert_eq!(listener.accept().unwrap_err().kind(), io::ErrorKind::WouldBlock);
        });
        assert!(run_proxy(address).is_err());
        server.join().unwrap();
    }
}

#[test]
fn private_udp_stops_on_control_eof_without_waiting_for_datagram_timeout() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    relay.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let server = thread::spawn(move || {
        let (mut control, _) = listener.accept().unwrap();
        authenticate(&mut control);
        association(&mut control, true);
        accept_private(&mut control, relay.local_addr().unwrap());
        let mut bytes = [0; 128];
        relay.recv_from(&mut bytes).unwrap();
        drop(control);
    });
    let started = Instant::now();
    assert!(run_proxy(address).is_err());
    server.join().unwrap();
    assert!(started.elapsed() < Duration::from_millis(700));
}

#[test]
fn invalid_private_udp_flood_cannot_extend_probe_deadline() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    relay.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let server = thread::spawn(move || {
        let (mut control, _) = listener.accept().unwrap();
        authenticate(&mut control);
        let source = association(&mut control, true);
        accept_private(&mut control, relay.local_addr().unwrap());
        let mut bytes = [0; 128];
        relay.recv_from(&mut bytes).unwrap();
        control.set_nonblocking(true).unwrap();
        let until = Instant::now() + Duration::from_secs(1);
        while Instant::now() < until {
            match control.peek(&mut [0]) {
                Ok(0) => return,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
                other => panic!("unexpected control result {other:?}"),
            }
            relay.send_to(b"invalid", source).unwrap();
            thread::sleep(Duration::from_millis(1));
        }
        panic!("probe kept its session beyond its deadline");
    });
    let started = Instant::now();
    let result =
        ripdpi_diagnostics_contracts::util::with_scan_io_deadline(Some(started + Duration::from_millis(200)), || {
            run_proxy(address)
        });
    assert!(result.is_err());
    assert!(started.elapsed() < Duration::from_millis(600));
    server.join().unwrap();
}

#[test]
fn private_udp_error_matches_current_destination() {
    for wrong_target in [true, false] {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        relay.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let server = thread::spawn(move || {
            let (mut control, _) = listener.accept().unwrap();
            authenticate(&mut control);
            let source = association(&mut control, true);
            accept_private(&mut control, relay.local_addr().unwrap());
            relay.recv_from(&mut [0; 128]).unwrap();
            // ICMPv4 port unreachable, source 192.0.2.1, target 192.0.2.53.
            let error_body =
                [4, 3, 3, 0, 0, 0, 0, 0, 192, 0, 2, 1, 4, 192, 0, 2, 53, 0, if wrong_target { 54 } else { 53 }];
            let mut error = frame(3, &error_body);
            error[24..32].copy_from_slice(&1u64.to_be_bytes());
            relay.send_to(&error, source).unwrap();
            if wrong_target {
                let body = [&[0, 0, 0, 1, 192, 0, 2, 53, 0, 53][..], b"answer"].concat();
                relay.send_to(&frame(2, &body), source).unwrap();
            }
            assert_eq!(control.read(&mut [0]).unwrap(), 0);
        });
        let result = run_proxy(address);
        server.join().unwrap();
        if wrong_target {
            assert_eq!(result.unwrap().0, b"answer");
        } else {
            assert!(
                matches!(result, Err(TransportError::Io(error)) if error.kind() == io::ErrorKind::ConnectionRefused)
            );
        }
    }
}
