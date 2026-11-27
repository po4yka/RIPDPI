use super::*;
use std::net::{TcpListener, TcpStream};
use std::thread;

fn reply_stream(reply: Vec<u8>) -> (ProbeStream, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut request = [0; 10];
        stream.read_exact(&mut request).unwrap();
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        stream.write_all(&reply).unwrap();
    });
    (TcpStream::connect(address).unwrap().into(), server)
}

#[test]
fn socks5_associate_rejects_invalid_fixed_header() {
    for header in [[4, 0, 0, 1], [5, 0, 1, 1], [5, 0, 255, 1]] {
        let (mut stream, server) = reply_stream([header.as_slice(), &[127, 0, 0, 1, 0, 53]].concat());
        assert!(socks5_udp_associate(&mut stream).is_err(), "header {header:?}");
        server.join().unwrap();
    }
}

#[test]
fn socks5_associate_accepts_domain_relay_endpoints() {
    for (host, expected) in [
        ("localhost", "127.0.0.1:5353"),
        ("LOCALHOST.", "127.0.0.1:5353"),
        ("127.0.0.1", "127.0.0.1:5353"),
        ("::1", "[::1]:5353"),
    ] {
        let mut reply = vec![5, 0, 0, 3, host.len() as u8];
        reply.extend_from_slice(host.as_bytes());
        reply.extend_from_slice(&[0x14, 0xe9]);
        let (mut stream, server) = reply_stream(reply);
        assert_eq!(socks5_udp_associate(&mut stream).unwrap(), expected.parse().unwrap());
        server.join().unwrap();
    }
}

#[test]
fn socks5_associate_rejects_invalid_domain_relay() {
    for reply in [vec![5, 0, 0, 3, 0], vec![5, 0, 0, 3, 1, 255], vec![5, 0, 0, 3, 1, b'a', 0, 0]] {
        let (mut stream, server) = reply_stream(reply);
        assert!(socks5_udp_associate(&mut stream).is_err());
        server.join().unwrap();
    }
}

#[test]
fn cancelled_tcp_negotiation_closes_the_proxy_socket() {
    use crate::transport::{TransportConfig, connect_transport_observed_with_abort};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{Duration, Instant};

    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let proxy = listener.local_addr().unwrap();
    let cancelled = AtomicBool::new(false);
    thread::scope(|scope| {
        let server = scope.spawn(|| {
            let (mut stream, _) = listener.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut greeting = [0; 3];
            stream.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, 0]);
            let cancel_started = Instant::now();
            cancelled.store(true, Ordering::Release);
            assert_eq!(stream.read(&mut greeting).unwrap(), 0);
            assert!(cancel_started.elapsed() < Duration::from_millis(300));
        });

        let result = connect_transport_observed_with_abort(
            &[TargetAddress::Host("loopback.invalid".into())],
            443,
            &TransportConfig::Socks5 { host: proxy.ip().to_string(), port: proxy.port(), credentials: None },
            &|| cancelled.load(Ordering::Acquire).then_some("cancelled"),
        )
        .unwrap_err();
        assert_eq!(result.stage, TransportFailureStage::Socks5Negotiation);
        assert_eq!(result.message, "probe_aborted:cancelled");
        server.join().unwrap();
    });
}
