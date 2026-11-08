mod support;

use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime_adapter::model::config::{
    DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
    DestinationRoutingRule,
};
use support::proxy::{ephemeral_proxy_config, start_proxy};

fn address_header(port: u16) -> Vec<u8> {
    let mut bytes = vec![1, 127, 0, 0, 1];
    bytes.extend(port.to_be_bytes());
    bytes
}

fn assert_no_application_bytes(listener: &TcpListener) {
    listener.set_nonblocking(true).unwrap();
    let deadline = Instant::now() + Duration::from_millis(400);
    loop {
        match listener.accept() {
            Ok((mut stream, _)) => {
                stream.set_nonblocking(false).unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                let mut bytes = [0; 1024];
                match stream.read(&mut bytes) {
                    Ok(0) => {}
                    Err(err) if err.kind() == std::io::ErrorKind::ConnectionReset => {}
                    actual => panic!("blocked origin received application bytes: {actual:?}"),
                }
                return;
            }
            Err(err) if err.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                thread::sleep(Duration::from_millis(5));
            }
            Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => return,
            Err(err) => panic!("origin accept failed: {err}"),
        }
    }
}

fn assert_blocked_payload(payload: &[u8]) {
    for mixed in [false, true] {
        for split in [0, 1, 2] {
            let origin = TcpListener::bind(("127.0.0.1", 0)).unwrap();
            let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
            config.network.shadowsocks = true;
            config.network.mixed = mixed;
            config.destination_routing.rules = vec![DestinationRoutingRule {
                action: DestinationRoutingAction::Block,
                network: DestinationRoutingNetwork::Tcp,
                domains: vec![DestinationDomainMatcher {
                    kind: DestinationDomainMatcherKind::Exact,
                    value: "blocked.example".to_string(),
                }],
                ip_ranges: vec![],
                destination_ports: vec![],
            }];
            let proxy = start_proxy(config, None);
            let mut client = TcpStream::connect(("127.0.0.1", proxy.port)).unwrap();
            let mut bytes = address_header(origin.local_addr().unwrap().port());
            if split == 0 {
                bytes.extend(payload);
                client.write_all(&bytes).unwrap();
            } else {
                if split == 2 {
                    bytes.extend(&payload[..20]);
                }
                client.write_all(&bytes).unwrap();
                thread::sleep(Duration::from_millis(if split == 1 { 150 } else { 15 }));
                let _ = client.write_all(if split == 1 { payload } else { &payload[20..] });
            }
            assert_no_application_bytes(&origin);
            let _ = client.shutdown(Shutdown::Both);
            drop(proxy);
        }
    }
}

#[test]
fn plain_address_blocks_coalesced_late_and_fragmented_http() {
    assert_blocked_payload(b"GET / HTTP/1.1\r\nHost: blocked.example\r\n\r\n");
}

#[test]
fn plain_address_blocks_coalesced_late_and_fragmented_tls() {
    let config = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(rustls::RootCertStore::empty())
        .with_no_client_auth();
    let mut connection = rustls::ClientConnection::new(
        std::sync::Arc::new(config),
        rustls::pki_types::ServerName::try_from("blocked.example").unwrap(),
    )
    .unwrap();
    let mut hello = Vec::new();
    connection.write_tls(&mut hello).unwrap();
    assert_blocked_payload(&hello);
}

#[test]
fn plain_address_preserves_server_first_and_half_close() {
    for mixed in [false, true] {
        let origin = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let target = origin.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = origin.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            stream.write_all(b"GREETING").unwrap();
            let mut body = Vec::new();
            stream.read_to_end(&mut body).unwrap();
            assert_eq!(body, (0..16_385).map(|n| (n % 256) as u8).collect::<Vec<_>>());
            stream.write_all(b"AFTER-FIN").unwrap();
        });
        let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
        config.network.shadowsocks = true;
        config.network.mixed = mixed;
        let proxy = start_proxy(config, None);
        let mut client = TcpStream::connect(("127.0.0.1", proxy.port)).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        client.write_all(&address_header(target.port())).unwrap();
        let mut greeting = [0; 8];
        client.read_exact(&mut greeting).unwrap();
        assert_eq!(&greeting, b"GREETING");
        client.write_all(&(0..16_385).map(|n| (n % 256) as u8).collect::<Vec<_>>()).unwrap();
        client.shutdown(Shutdown::Write).unwrap();
        let mut response = Vec::new();
        client.read_to_end(&mut response).unwrap();
        assert_eq!(response, b"AFTER-FIN");
        server.join().unwrap();
        drop(proxy);
    }
}

#[test]
fn plain_address_blocks_http_after_server_greeting() {
    for mixed in [false, true] {
        let origin = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let target = origin.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = origin.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            stream.write_all(b"GREETING").unwrap();
            let mut bytes = [0; 1024];
            let read = stream.read(&mut bytes);
            assert!(
                matches!(read, Ok(0)) || matches!(read,Err(ref e) if e.kind()==std::io::ErrorKind::ConnectionReset),
                "blocked bytes after greeting: {read:?}"
            );
        });
        let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
        config.network.shadowsocks = true;
        config.network.mixed = mixed;
        config.destination_routing.rules = vec![DestinationRoutingRule {
            action: DestinationRoutingAction::Block,
            network: DestinationRoutingNetwork::Tcp,
            domains: vec![DestinationDomainMatcher {
                kind: DestinationDomainMatcherKind::Exact,
                value: "blocked.example".into(),
            }],
            ip_ranges: vec![],
            destination_ports: vec![],
        }];
        let proxy = start_proxy(config, None);
        let mut client = TcpStream::connect(("127.0.0.1", proxy.port)).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        client.write_all(&address_header(target.port())).unwrap();
        let mut greeting = [0; 8];
        client.read_exact(&mut greeting).unwrap();
        assert_eq!(&greeting, b"GREETING");
        let _ = client.write_all(b"GET / HTTP/1.1\r\nHost: blocked.example\r\n\r\n");
        let _ = client.shutdown(Shutdown::Write);
        let observed = server.join();
        let _ = client.shutdown(Shutdown::Both);
        drop(proxy);
        observed.unwrap();
    }
}
