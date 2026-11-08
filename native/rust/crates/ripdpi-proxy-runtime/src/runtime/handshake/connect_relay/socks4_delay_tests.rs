use super::*;
use crate::runtime::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener};
use std::thread;
use std::time::Duration;

fn config() -> RuntimeConfig {
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTP;
    config
}

fn client(
    target: SocketAddr,
    domain: bool,
    config: RuntimeConfig,
) -> (TcpStream, thread::JoinHandle<std::io::Result<()>>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut peer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    let accepted = listener.accept().unwrap().0;
    let worker = thread::spawn(move || crate::runtime::handshake::handle_client(accepted, &RuntimeState::test(config)));
    let mut request = vec![4, 1];
    request.extend_from_slice(&target.port().to_be_bytes());
    request.extend_from_slice(if domain { &[0, 0, 0, 1] } else { &[127, 0, 0, 1] });
    request.push(0);
    if domain {
        request.extend_from_slice(b"127.0.0.1\0");
    }
    peer.write_all(&request).unwrap();
    (peer, worker)
}

fn refused_addr() -> SocketAddr {
    let target = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    target.local_addr().unwrap()
}

#[test]
fn delayed_socks4_and_socks4a_refused_targets_send_only_rejection() {
    for domain in [false, true] {
        let (mut peer, worker) = client(refused_addr(), domain, config());
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        let mut trailing = Vec::new();
        if reply[1] == 0x5b {
            peer.read_to_end(&mut trailing).unwrap();
        }
        drop(peer);
        let result = worker.join().unwrap();
        assert_eq!(reply, [0, 0x5b, 0, 0, 0, 0, 0, 0], "SOCKS4a={domain}");
        assert!(trailing.is_empty(), "CONNECT sends only one reply");
        assert!(result.is_err());
    }
}

#[test]
fn delayed_socks4_and_socks4a_refused_upstreams_send_only_rejection() {
    for domain in [false, true] {
        let mut config = config();
        config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: refused_addr(), auth: None });
        let (mut peer, worker) = client(refused_addr(), domain, config);
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        let mut trailing = Vec::new();
        if reply[1] == 0x5b {
            peer.read_to_end(&mut trailing).unwrap();
        }
        drop(peer);
        let result = worker.join().unwrap();
        assert_eq!(reply, [0, 0x5b, 0, 0, 0, 0, 0, 0], "SOCKS4a={domain}");
        assert!(trailing.is_empty(), "CONNECT sends only one reply");
        assert!(result.is_err());
    }
}

#[test]
fn delayed_socks4_and_socks4a_reuse_the_acknowledged_socket() {
    for domain in [false, true] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let (mut peer, worker) = client(origin.local_addr().unwrap(), domain, config());
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
        let (mut server, _) = origin.accept().unwrap();
        server.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let payload = b"GET / HTTP/1.0\r\n\r\n";
        peer.write_all(payload).unwrap();
        peer.shutdown(Shutdown::Write).unwrap();
        let mut received = Vec::new();
        server.read_to_end(&mut received).unwrap();
        assert_eq!(received, payload);
        server.write_all(b"response").unwrap();
        drop(server);
        let mut response = Vec::new();
        peer.read_to_end(&mut response).unwrap();
        assert_eq!(response, b"response");
        assert!(worker.join().unwrap().is_ok());
        origin.set_nonblocking(true).unwrap();
        assert_eq!(origin.accept().unwrap_err().kind(), std::io::ErrorKind::WouldBlock);
    }
}

#[test]
fn delayed_socks4_and_socks4a_forward_server_first_on_arbitrary_ports() {
    for domain in [false, true] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let (mut peer, worker) = client(origin.local_addr().unwrap(), domain, config());
        let (mut server, _) = origin.accept().unwrap();
        server.write_all(b"BANNER").unwrap();
        drop(server);
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
        let mut banner = [0; 6];
        peer.read_exact(&mut banner).unwrap();
        assert_eq!(&banner, b"BANNER");
        drop(peer);
        assert!(worker.join().unwrap().is_ok());
    }
}

#[test]
fn delayed_socks4_and_socks4a_wait_for_upstream_connect_acceptance() {
    for domain in [false, true] {
        let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut config = config();
        config.groups[0].policy.ext_socks =
            Some(UpstreamSocksConfig { addr: upstream.local_addr().unwrap(), auth: None });
        let gateway = thread::spawn(move || {
            let (mut stream, _) = upstream.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            let mut methods = [0; 3];
            stream.read_exact(&mut methods).unwrap();
            assert_eq!(methods, [5, 1, 0]);
            stream.write_all(&[5, 0]).unwrap();
            let mut request = [0; 10];
            stream.read_exact(&mut request).unwrap();
            assert_eq!(&request[..8], &[5, 1, 0, 1, 127, 0, 0, 1]);
            stream.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
        });
        let (mut peer, worker) = client(refused_addr(), domain, config);
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        let mut trailing = Vec::new();
        peer.read_to_end(&mut trailing).unwrap();
        assert_eq!(reply, [0, 0x5b, 0, 0, 0, 0, 0, 0], "SOCKS4a={domain}");
        assert!(trailing.is_empty());
        assert!(worker.join().unwrap().is_err());
        gateway.join().unwrap();
    }
}

#[test]
fn delayed_socks4_and_socks4a_apply_payload_policy_before_forwarding() {
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
        DestinationRoutingPolicy, DestinationRoutingRule,
    };
    for domain in [false, true] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut config = config();
        config.destination_routing = DestinationRoutingPolicy {
            rules: vec![DestinationRoutingRule {
                action: DestinationRoutingAction::Block,
                network: DestinationRoutingNetwork::Tcp,
                domains: vec![DestinationDomainMatcher {
                    kind: DestinationDomainMatcherKind::Exact,
                    value: "blocked.example".into(),
                }],
                ip_ranges: vec![],
                destination_ports: vec![],
            }],
            default_action: DestinationRoutingAction::Tunneled,
            canonical_digest: "test".into(),
        };
        let (mut peer, worker) = client(origin.local_addr().unwrap(), domain, config);
        let (mut server, _) = origin.accept().unwrap();
        server.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
        peer.write_all(b"GET / HTTP/1.1\r\nHost: blocked.example\r\n\r\n").unwrap();
        let mut received = Vec::new();
        server.read_to_end(&mut received).unwrap();
        assert!(received.is_empty());
        assert!(worker.join().unwrap().is_err());
    }
}

#[test]
fn delayed_socks4_and_socks4a_empty_fin_preserve_the_origin_response() {
    for domain in [false, true] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let target = origin.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = origin.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            let mut received = Vec::new();
            stream.read_to_end(&mut received).unwrap();
            assert!(received.is_empty());
            stream.write_all(b"after empty FIN").unwrap();
        });
        let (mut peer, worker) = client(target, domain, config());
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
        peer.shutdown(Shutdown::Write).unwrap();
        let mut response = Vec::new();
        peer.read_to_end(&mut response).unwrap();
        assert_eq!(response, b"after empty FIN");
        assert!(worker.join().unwrap().is_ok());
        server.join().unwrap();
    }
}
