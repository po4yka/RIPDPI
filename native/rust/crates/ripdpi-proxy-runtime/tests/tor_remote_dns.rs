#![cfg(not(feature = "loom"))]
#[path = "support/stalled_dns.rs"]
mod stalled_dns;

use ripdpi_proxy_runtime::{create_listener, prepare_embedded, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, UpstreamSocksAuth, UpstreamSocksConfig};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

#[test]
fn tor_domain_inputs_reach_upstream_without_direct_dns() {
    let onion = "2gzyxa5ihm7ru6h2k6vzuzul4qoftkornhmcq4w4y7f2pla55atbdpqd.onion";
    for (protocol, authenticated) in [(4, false), (5, false), (5, true), (6, false)] {
        prepare_embedded();
        let dns = stalled_dns::StalledDns::start();
        let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let endpoint = upstream.local_addr().unwrap();
        let mut config = RuntimeConfig::default();
        config.network.listen.listen_port = 0;
        config.network.mixed = true;
        config.network.remote_dns_upstream = Some(endpoint);
        config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig {
            addr: endpoint,
            auth: authenticated.then(|| UpstreamSocksAuth { username: "user".into(), password: "pass".into() }),
        });
        let listener = create_listener(&config).unwrap();
        let address = listener.local_addr().unwrap();
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(dns.context())));
        let worker_control = control.clone();
        let worker = thread::spawn(move || {
            run_proxy_with_embedded_control_receipt(
                config,
                listener,
                worker_control,
                Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
            )
        });
        let peer = thread::spawn(move || {
            let (mut socket, _) = upstream.accept().unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut greeting = [0; 3];
            socket.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, if authenticated { 2 } else { 0 }]);
            socket.write_all(&[5, if authenticated { 2 } else { 0 }]).unwrap();
            if authenticated {
                let mut auth = [0; 11];
                socket.read_exact(&mut auth).unwrap();
                assert_eq!(&auth, b"\x01\x04user\x04pass");
                socket.write_all(&[1, 0]).unwrap();
            }
            let mut header = [0; 5];
            socket.read_exact(&mut header).unwrap();
            assert_eq!(header, [5, 1, 0, 3, onion.len() as u8]);
            let mut authority = vec![0; onion.len() + 2];
            socket.read_exact(&mut authority).unwrap();
            assert_eq!(&authority[..onion.len()], onion.as_bytes());
            assert_eq!(&authority[onion.len()..], &443u16.to_be_bytes());
            socket.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).unwrap();
            let mut ping = [0; 4];
            socket.read_exact(&mut ping).unwrap();
            assert_eq!(&ping, b"ping");
            socket.write_all(b"pong").unwrap();
            let mut tail = [0];
            assert_eq!(socket.read(&mut tail).unwrap(), 0, "Stop closes the active Tor stream");
        });
        let mut client = TcpStream::connect(address).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        match protocol {
            4 => {
                client.write_all(&[4, 1, 1, 187, 0, 0, 0, 1, 0]).unwrap();
                client.write_all(onion.as_bytes()).unwrap();
                client.write_all(&[0]).unwrap();
                let mut reply = [0; 8];
                client.read_exact(&mut reply).unwrap();
                assert_eq!(reply[1], 0x5a);
            }
            5 => {
                client.write_all(&[5, 1, 0]).unwrap();
                let mut method = [0; 2];
                client.read_exact(&mut method).unwrap();
                assert_eq!(method, [5, 0]);
                client.write_all(&[5, 1, 0, 3, onion.len() as u8]).unwrap();
                client.write_all(onion.as_bytes()).unwrap();
                client.write_all(&443u16.to_be_bytes()).unwrap();
                let mut reply = [0; 10];
                client.read_exact(&mut reply).unwrap();
                assert_eq!(reply[1], 0);
            }
            _ => {
                write!(client, "CONNECT {onion}:443 HTTP/1.1\r\nHost: {onion}:443\r\n\r\n").unwrap();
                let mut reply = Vec::new();
                while !reply.ends_with(b"\r\n\r\n") {
                    let mut b = [0];
                    client.read_exact(&mut b).unwrap();
                    reply.push(b[0]);
                }
                assert!(reply.starts_with(b"HTTP/1.1 200"));
            }
        }
        client.write_all(b"ping").unwrap();
        let mut pong = [0; 4];
        client.read_exact(&mut pong).unwrap();
        assert_eq!(&pong, b"pong");
        control.request_shutdown();
        peer.join().unwrap();
        drop(client);
        assert!(dns.started.recv_timeout(Duration::from_millis(100)).is_err(), "Tor authority reached direct DNS");
        assert!(dns.closed.try_recv().is_err(), "no direct DNS connection should have closed");
        control.request_shutdown();
        let receipt = worker.join().unwrap().unwrap();
        assert!(!receipt.worker_panicked());
        assert!(!receipt.forced_abort(), "Tor Stop must drain without force abort");
    }
}

#[test]
fn tor_domain_failures_preserve_socks_reply_codes_without_dns_or_direct_fallback() {
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
        DestinationRoutingRule,
    };
    for blocked in [false, true] {
        prepare_embedded();
        let dns = stalled_dns::StalledDns::start();
        let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let endpoint = upstream.local_addr().unwrap();
        let mut config = RuntimeConfig::default();
        config.network.listen.listen_port = 0;
        config.network.remote_dns_upstream = Some(endpoint);
        config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: endpoint, auth: None });
        if blocked {
            config.destination_routing.rules.push(DestinationRoutingRule {
                action: DestinationRoutingAction::Block,
                network: DestinationRoutingNetwork::Tcp,
                domains: vec![DestinationDomainMatcher {
                    kind: DestinationDomainMatcherKind::Exact,
                    value: "blocked.onion".into(),
                }],
                ip_ranges: vec![],
                destination_ports: vec![],
            });
        }
        let peer = if blocked {
            None
        } else {
            let listener = upstream.try_clone().unwrap();
            Some(thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                let mut greeting = [0; 3];
                socket.read_exact(&mut greeting).unwrap();
                assert_eq!(greeting, [5, 1, 0]);
                socket.write_all(&[5, 0]).unwrap();
                let mut request = [0; 20];
                socket.read_exact(&mut request).unwrap();
                assert_eq!(&request[..5], &[5, 1, 0, 3, 13]);
                assert_eq!(&request[5..18], b"blocked.onion");
                socket.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
            }))
        };
        let listener = create_listener(&config).unwrap();
        let endpoint = listener.local_addr().unwrap();
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(dns.context())));
        let worker_control = control.clone();
        let worker = thread::spawn(move || {
            run_proxy_with_embedded_control_receipt(
                config,
                listener,
                worker_control,
                Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
            )
        });
        let mut client = TcpStream::connect(endpoint).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        client.write_all(&[5, 1, 0]).unwrap();
        let mut method = [0; 2];
        client.read_exact(&mut method).unwrap();
        client.write_all(b"\x05\x01\x00\x03\x0dblocked.onion\x01\xbb").unwrap();
        let mut reply = [0; 10];
        client.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [5, if blocked { 2 } else { 5 }, 0, 1, 0, 0, 0, 0, 0, 0]);
        if let Some(peer) = peer {
            peer.join().unwrap();
        } else {
            upstream.set_nonblocking(true).unwrap();
            assert!(upstream.accept().is_err(), "blocked domain must not contact Tor");
        }
        assert!(dns.started.recv_timeout(Duration::from_millis(50)).is_err());
        assert!(dns.closed.try_recv().is_err());
        control.request_shutdown();
        worker.join().unwrap().unwrap();
    }
}
