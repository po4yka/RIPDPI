use super::*;
use crate::runtime::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::sync::Arc;
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(3);

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let peer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    peer.set_read_timeout(Some(TIMEOUT)).unwrap();
    peer.set_write_timeout(Some(TIMEOUT)).unwrap();
    (peer, listener.accept().unwrap().0)
}

fn payload_config(protocol: u32) -> RuntimeConfig {
    let mut config = RuntimeConfig::default();
    config.network.delay_conn = true;
    config.groups[0].matches.proto = protocol;
    config.groups.push(DesyncGroup::new(1));
    config
}

#[test]
fn compatible_payload_group_and_discovered_host_keep_the_accepted_socket() {
    use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyConnectionConcurrencyPolicy, ProxyRuntimeContext};

    for host_hint in [None, Some("authority.example"), Some("PAYLOAD.EXAMPLE.")] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let target = origin.local_addr().unwrap();
        let (mut peer, mut client) = pair();
        let payload = b"GET / HTTP/1.1\r\nHost: payload.example\r\n\r\n";
        let server = std::thread::spawn(move || {
            let mut response = [0; 19];
            peer.read_exact(&mut response).unwrap();
            assert_eq!(&response, b"HTTP/1.1 200 OK\r\n\r\n");
            let (mut accepted, source) = origin.accept().unwrap();
            drop(origin);
            accepted.set_read_timeout(Some(TIMEOUT)).unwrap();
            peer.write_all(payload).unwrap();
            let mut delivered = vec![0; payload.len()];
            accepted.read_exact(&mut delivered).unwrap();
            assert_eq!(delivered, payload);
            accepted.write_all(b"same accepted socket").unwrap();
            source
        });
        let context = ProxyRuntimeContext {
            connection_concurrency: Some(ProxyConnectionConcurrencyPolicy {
                selected_profile_id: "test".to_string(),
                per_profile_caps: std::collections::BTreeMap::from([("pass-through".to_string(), 1)]),
            }),
            ..Default::default()
        };
        let state = RuntimeState::test_with_context(payload_config(ripdpi_packets::IS_HTTP), Some(context));
        let initial = crate::runtime::routing::select_route(&state, target, None, host_hint, false).unwrap();
        assert_eq!(initial.group_index, 1);
        let outcome = maybe_delay_connect(&mut client, &state, target, host_hint, Some(HandshakeKind::HttpConnect))
            .expect("compatible payload routing must retain the accepted connection");
        let DelayConnect::Connected(mut connected) = outcome else { panic!("expected connected route") };
        assert_eq!(connected.route.group_index, 0, "relay must use the payload group");
        assert_eq!(connected.seed_request.as_deref(), Some(payload.as_slice()));
        assert!(connected.same_sni_guard.is_some());
        assert!(state.try_acquire_pass_through_sni_session("payload.example").is_none());
        assert!(state.try_acquire_pass_through_sni_session("authority.example").is_some());
        connected.upstream.write_all(connected.seed_request.as_deref().unwrap()).unwrap();
        let mut reply = [0; 20];
        connected.upstream.read_exact(&mut reply).unwrap();
        assert_eq!(&reply, b"same accepted socket");
        assert_eq!(connected.upstream.local_addr().unwrap(), server.join().unwrap());
    }
}

#[test]
fn delayed_http_tls_group_completes_on_a_single_accepted_connection() {
    use rustls::pki_types::{PrivatePkcs8KeyDer, ServerName};

    let certificate = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
    let provider = rustls::crypto::ring::default_provider();
    let server_config = rustls::ServerConfig::builder_with_provider(Arc::new(provider.clone()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(
            vec![certificate.cert.der().clone()],
            PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into(),
        )
        .unwrap();
    let mut roots = rustls::RootCertStore::empty();
    roots.add(certificate.cert.der().clone()).unwrap();
    let client_config = rustls::ClientConfig::builder_with_provider(Arc::new(provider))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let target = origin.local_addr().unwrap();
    let payload = b"one-accept TLS payload\0\xff";
    let server = std::thread::spawn(move || {
        let (accepted, _) = origin.accept().unwrap();
        drop(origin);
        accepted.set_read_timeout(Some(TIMEOUT)).unwrap();
        accepted.set_write_timeout(Some(TIMEOUT)).unwrap();
        let connection = rustls::ServerConnection::new(Arc::new(server_config)).unwrap();
        let mut tls = rustls::StreamOwned::new(connection, accepted);
        let mut delivered = [0; 24];
        tls.read_exact(&mut delivered).unwrap();
        assert_eq!(&delivered, payload);
        assert_eq!(tls.conn.protocol_version(), Some(rustls::ProtocolVersion::TLSv1_3));
        tls.write_all(payload).unwrap();
        tls.flush().unwrap();
        let mut trailing = Vec::new();
        tls.read_to_end(&mut trailing).unwrap();
        assert!(trailing.is_empty());
        tls.conn.send_close_notify();
        tls.flush().unwrap();
    });
    let (mut peer, client) = pair();
    let mut config = payload_config(ripdpi_packets::IS_HTTPS);
    config.network.http_connect = true;
    let worker =
        std::thread::spawn(move || crate::runtime::handshake::handle_client(client, &RuntimeState::test(config)));
    write!(peer, "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n").unwrap();
    let mut success = [0; 19];
    peer.read_exact(&mut success).unwrap();
    assert_eq!(&success, b"HTTP/1.1 200 OK\r\n\r\n");
    let connection =
        rustls::ClientConnection::new(Arc::new(client_config), ServerName::try_from("localhost").unwrap()).unwrap();
    let mut tls = rustls::StreamOwned::new(connection, peer);
    tls.write_all(payload).unwrap();
    let mut response = [0; 24];
    tls.read_exact(&mut response).unwrap();
    assert_eq!(&response, payload);
    assert_eq!(tls.conn.protocol_version(), Some(rustls::ProtocolVersion::TLSv1_3));
    tls.conn.send_close_notify();
    tls.flush().unwrap();
    let mut trailing = Vec::new();
    tls.read_to_end(&mut trailing).unwrap();
    assert!(trailing.is_empty());
    tls.sock.shutdown(std::net::Shutdown::Write).unwrap();
    drop(tls);
    server.join().unwrap();
    let result = worker.join().unwrap();
    assert!(result.is_ok(), "relay result: {result:?}");
}

#[test]
fn changed_pre_connect_receive_buffer_replaces_the_socket() {
    use ripdpi_proxy_runtime_adapter::model::config::WsizeConfig;

    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let target = origin.local_addr().unwrap();
    let (mut peer, mut client) = pair();
    let sender = std::thread::spawn(move || {
        let mut success = [0; 19];
        peer.read_exact(&mut success).unwrap();
        peer.write_all(b"GET / HTTP/1.0\r\n\r\n").unwrap();
    });
    let mut config = payload_config(ripdpi_packets::IS_HTTP);
    config.groups[0].actions.wsize = Some(WsizeConfig { window: 4096, scale: None });
    let state = RuntimeState::test(config);
    let outcome = maybe_delay_connect(&mut client, &state, target, None, Some(HandshakeKind::HttpConnect)).unwrap();
    let DelayConnect::Connected(connected) = outcome else { panic!("expected connected route") };
    let (mut provisional, source) = origin.accept().unwrap();
    provisional.set_read_timeout(Some(TIMEOUT)).unwrap();
    assert_eq!(provisional.read(&mut [0; 1]).unwrap(), 0);
    origin.set_nonblocking(true).unwrap();
    let (_, final_source) = origin.accept().unwrap();
    assert_ne!(final_source, source);
    assert_eq!(connected.upstream.local_addr().unwrap(), final_source);
    assert_eq!(connected.route.group_index, 0);
    assert_eq!(connected.seed_request.as_deref(), Some(b"GET / HTTP/1.0\r\n\r\n".as_slice()));
    sender.join().unwrap();
}
