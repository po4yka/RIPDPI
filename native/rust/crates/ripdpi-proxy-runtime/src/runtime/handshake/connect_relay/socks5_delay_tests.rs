use super::*;
use crate::runtime::config::RuntimeConfig;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    (client, listener.accept().unwrap().0)
}

#[test]
fn refused_delayed_socks_connect_never_sends_success() {
    let target = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let addr = target.local_addr().unwrap();
    drop(target);
    let (mut peer, mut client) = pair();
    peer.write_all(b"GET / HTTP/1.0\r\n\r\n").unwrap();
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTPS;
    let err = maybe_delay_connect(&mut client, &RuntimeState::test(config), addr, None, Some(HandshakeKind::Socks5))
        .err()
        .expect("a refused target must fail CONNECT");
    assert_eq!(err.kind(), std::io::ErrorKind::ConnectionRefused);
    assert!(!err.success_reply_sent());
    drop(client);
    match peer.read(&mut [0; 10]) {
        Ok(n) => assert_eq!(n, 0),
        Err(err) => assert_eq!(err.kind(), std::io::ErrorKind::ConnectionReset),
    }
}

fn socks_client(
    target: SocketAddr,
    config: RuntimeConfig,
) -> (TcpStream, std::thread::JoinHandle<std::io::Result<()>>) {
    let (mut peer, client) = pair();
    peer.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
    let worker =
        std::thread::spawn(move || crate::runtime::handshake::handle_client(client, &RuntimeState::test(config)));
    peer.write_all(&[5, 1, 0]).unwrap();
    let mut selected = [0; 2];
    peer.read_exact(&mut selected).unwrap();
    assert_eq!(selected, [5, 0]);
    let mut request = vec![5, 1, 0, 1, 127, 0, 0, 1];
    request.extend_from_slice(&target.port().to_be_bytes());
    peer.write_all(&request).unwrap();
    (peer, worker)
}

#[test]
fn delayed_connect_reuses_the_acknowledged_socket_and_reports_its_bound_endpoint() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let target = origin.local_addr().unwrap();
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTP;
    let (mut peer, worker) = socks_client(target, config);
    let (mut server, source) = origin.accept().unwrap();
    let mut success = [0; 10];
    peer.read_exact(&mut success).unwrap();
    assert_eq!(&success[..4], &[5, 0, 0, 1]);
    assert_eq!(&success[4..8], &[127, 0, 0, 1]);
    assert_eq!(u16::from_be_bytes([success[8], success[9]]), source.port());
    let record = b"GET / HTTP/1.0\r\n\r\n";
    peer.write_all(record).unwrap();
    peer.shutdown(std::net::Shutdown::Write).unwrap();
    let mut got = Vec::new();
    server.read_to_end(&mut got).unwrap();
    assert_eq!(got, record);
    server.write_all(b"after FIN").unwrap();
    drop(server);
    let mut response = Vec::new();
    peer.read_to_end(&mut response).unwrap();
    assert_eq!(response, b"after FIN");
    assert!(worker.join().unwrap().is_ok());
    origin.set_nonblocking(true).unwrap();
    assert_eq!(origin.accept().unwrap_err().kind(), std::io::ErrorKind::WouldBlock);
}

#[test]
fn delayed_connect_relays_a_banner_on_an_arbitrary_server_first_port() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTPS;
    let (mut peer, worker) = socks_client(origin.local_addr().unwrap(), config);
    let (mut server, _) = origin.accept().unwrap();
    server.write_all(b"BANNER").unwrap();
    drop(server);
    let mut success = [0; 10];
    peer.read_exact(&mut success).unwrap();
    assert_eq!(&success[..2], &[5, 0]);
    let mut banner = [0; 6];
    peer.read_exact(&mut banner).unwrap();
    assert_eq!(&banner, b"BANNER");
    drop(peer);
    assert!(worker.join().unwrap().is_ok());
}

#[test]
fn a_payload_block_closes_the_provisional_connection_without_application_bytes() {
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
        DestinationRoutingPolicy, DestinationRoutingRule,
    };
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let config = RuntimeConfig {
        destination_routing: DestinationRoutingPolicy {
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
        },
        ..RuntimeConfig::default()
    };
    let (mut peer, worker) = socks_client(origin.local_addr().unwrap(), config);
    let (mut server, _) = origin.accept().unwrap();
    server.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
    let mut success = [0; 10];
    peer.read_exact(&mut success).unwrap();
    peer.write_all(b"GET / HTTP/1.1\r\nHost: blocked.example\r\n\r\n").unwrap();
    let mut data = Vec::new();
    server.read_to_end(&mut data).unwrap();
    assert!(data.is_empty());
    assert!(worker.join().unwrap().is_err());
}

#[test]
fn a_payload_route_change_replaces_the_provisional_connection() {
    use ripdpi_proxy_runtime_adapter::model::config::{DesyncGroup, UpstreamSocksConfig};
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTPS;
    let mut fallback = DesyncGroup::new(1);
    fallback.matches.proto = ripdpi_packets::IS_HTTP;
    fallback.policy.ext_socks = Some(UpstreamSocksConfig { addr: upstream.local_addr().unwrap(), auth: None });
    config.groups.push(fallback);
    let (mut peer, worker) = socks_client(origin.local_addr().unwrap(), config);
    let (mut provisional, _) = origin.accept().unwrap();
    provisional.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
    let mut success = [0; 10];
    peer.read_exact(&mut success).unwrap();
    peer.write_all(b"GET / HTTP/1.0\r\n\r\n").unwrap();
    let mut initial_data = Vec::new();
    provisional.read_to_end(&mut initial_data).unwrap();
    assert!(initial_data.is_empty());
    let (mut final_route, _) = upstream.accept().unwrap();
    final_route.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
    let mut methods = [0; 3];
    final_route.read_exact(&mut methods).unwrap();
    assert_eq!(methods, [5, 1, 0]);
    final_route.write_all(&[5, 0]).unwrap();
    let mut request = [0; 10];
    final_route.read_exact(&mut request).unwrap();
    assert_eq!(&request[..8], &[5, 1, 0, 1, 127, 0, 0, 1]);
    final_route.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 1]).unwrap();
    let mut payload = [0; 18];
    final_route.read_exact(&mut payload).unwrap();
    assert_eq!(&payload, b"GET / HTTP/1.0\r\n\r\n");
    final_route.write_all(&payload).unwrap();
    drop(final_route);
    let mut response = [0; 18];
    peer.read_exact(&mut response).unwrap();
    assert_eq!(response, payload);
    drop(peer);
    assert!(worker.join().unwrap().is_ok());
}

#[test]
fn refused_delayed_connect_returns_rep05_to_a_client_waiting_for_the_reply() {
    let target = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let addr = target.local_addr().unwrap();
    drop(target);
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTPS;
    let (mut peer, worker) = socks_client(addr, config);
    let mut reply = [0; 10];
    peer.read_exact(&mut reply).unwrap();
    assert_eq!(reply, [5, 5, 0, 1, 0, 0, 0, 0, 0, 0]);
    assert_eq!(peer.read(&mut [0; 1]).unwrap(), 0);
    assert!(worker.join().unwrap().is_err());
}

#[test]
fn syn_data_reconnects_with_the_payload_before_forwarding() {
    use ripdpi_proxy_runtime_adapter::model::config::{OffsetExpr, TcpChainStep, TcpChainStepKind};
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let (mut peer, mut client) = pair();
    let sender = std::thread::spawn(move || {
        let mut reply = [0; 10];
        peer.read_exact(&mut reply).unwrap();
        assert_eq!(&reply[..2], &[5, 0]);
        peer.write_all(b"GET / HTTP/1.0\r\n\r\n").unwrap();
    });
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTP;
    config.groups[0].actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1))];
    let state = RuntimeState::test(config);
    let outcome =
        maybe_delay_connect(&mut client, &state, origin.local_addr().unwrap(), None, Some(HandshakeKind::Socks5));
    let (mut provisional, _) = origin.accept().unwrap();
    assert_eq!(provisional.read(&mut [0; 1]).unwrap(), 0);
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        let DelayConnect::Connected(connected) = outcome.unwrap() else {
            panic!("connected delayed route");
        };
        // A second real connection proves that the provisional socket was replaced.
        origin.set_nonblocking(true).unwrap();
        let (_, source) = origin.accept().unwrap();
        assert_eq!(connected.upstream.local_addr().unwrap(), source);
        let policy = state
            .route_connect_policy(
                connected.route.group_index,
                connected.seed_request.as_deref(),
                true,
                crate::runtime::destination_routing::DestinationEgress::Tunneled,
            )
            .unwrap();
        assert!(policy.tfo_enabled, "SynData requires a TFO-enabled payload connect");
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        // This host rejects TFO at socket construction; reuse would wrongly succeed.
        let err = outcome.err().expect("payload-aware TFO connect must reach the platform");
        assert_eq!(err.kind(), std::io::ErrorKind::Unsupported);
        assert!(err.success_reply_sent());
    }
    sender.join().unwrap();
}

#[test]
fn a_discovered_owned_stack_host_never_receives_application_bytes() {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let target = origin.local_addr().unwrap();
    let (mut peer, mut client) = pair();
    let sender = std::thread::spawn(move || {
        let mut reply = [0; 10];
        peer.read_exact(&mut reply).unwrap();
        peer.write_all(b"GET / HTTP/1.1\r\nHost: example.org\r\n\r\n").unwrap();
    });
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = ripdpi_packets::IS_HTTP;
    let mut context = super::tests::owned_stack_context();
    context.direct_path_capabilities[0].authority = format!("example.org:{}", target.port());
    let state = RuntimeState::test_with_context(config, Some(context));
    let err = maybe_delay_connect(&mut client, &state, target, None, Some(HandshakeKind::Socks5))
        .err()
        .expect("owned stack target");
    assert_eq!(err.policy_rejection(), Some(ConnectPolicyRejection::OwnedStackRequired));
    assert!(err.success_reply_sent());
    let (mut provisional, _) = origin.accept().unwrap();
    assert_eq!(provisional.read(&mut [0; 1]).unwrap(), 0);
    sender.join().unwrap();
}
