#![cfg(not(feature = "loom"))]

mod support;

#[cfg(any(target_os = "linux", target_os = "android"))]
use std::io::Read;
use std::io::Write;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::net::TcpListener;
use std::net::{Ipv4Addr, TcpStream, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::{UdpChainStep, UdpChainStepKind};
#[cfg(any(target_os = "linux", target_os = "android"))]
use ripdpi_proxy_runtime_adapter::model::runtime_api::{DesyncExecutionDisposition, DesyncOffsetMarkerBase};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    DesyncExecutionTransport, DesyncStrategyFamily, EmbeddedProxyControl,
};
use support::proxy::ephemeral_proxy_config;
use support::socks5::{recv_exact, recv_socks5_reply, udp_proxy_client, udp_proxy_roundtrip_with_socket};

#[allow(dead_code)]
#[path = "../../ripdpi-packets/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn authenticated_socks_split_host_attempt_receipt_preserves_candidate_identity_without_endpoint_join() {
    let generation = 1_786_885_745_241_507_u64;
    let attempt_fixture = "attempt-token-red-a";
    let auth_fixture = "runtime-secret-red-a";
    let host = "receipt-red.example.test";
    let payload = format!("GET /receipt-red HTTP/1.1\r\nHost: {host}\r\n\r\n").into_bytes();
    let split_boundary =
        payload.windows(host.len()).position(|window| window == host.as_bytes()).expect("host marker in request") + 1;
    let upstream_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind receipt upstream");
    let upstream_addr = upstream_listener.local_addr().expect("receipt upstream address");
    let upstream_payload = payload.clone();
    let upstream = std::thread::spawn(move || {
        let mut streams = Vec::with_capacity(2);
        for _ in 0..2 {
            streams.push(upstream_listener.accept().expect("accept receipt upstream").0);
        }
        for mut stream in streams {
            let mut received = vec![0_u8; upstream_payload.len()];
            stream.read_exact(&mut received).expect("read complete split payload");
            assert_eq!(received, upstream_payload);
            stream.write_all(&received).expect("echo complete split payload");
        }
    });

    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1", "--split", "host+1"]);
    config.network.listen.auth_token = Some(auth_fixture.to_string());
    let listener = ripdpi_proxy_runtime::create_listener(&config).expect("create proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy listener addr");
    let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, generation));
    let worker_control = control.clone();
    let worker = std::thread::spawn(move || {
        ripdpi_proxy_runtime::run_proxy_with_embedded_control_receipt(config, listener, worker_control)
    });

    let start = Arc::new(std::sync::Barrier::new(3));
    let clients = (0..2)
        .map(|_| {
            let start = start.clone();
            let payload = payload.clone();
            std::thread::spawn(move || {
                let mut stream = socks_connect_with_userpass_ip(
                    proxy_addr.port(),
                    attempt_fixture,
                    auth_fixture,
                    upstream_addr.port(),
                );
                start.wait();
                stream.write_all(&payload).expect("write HTTP payload through authenticated SOCKS");
                let mut echoed = vec![0u8; payload.len()];
                stream.read_exact(&mut echoed).expect("read authenticated split echo");
                echoed
            })
        })
        .collect::<Vec<_>>();
    start.wait();
    let echoed_payloads =
        clients.into_iter().map(|client| client.join().expect("join split client")).collect::<Vec<_>>();

    control.request_shutdown();
    let _ = TcpStream::connect(proxy_addr);
    let terminal_result = worker.join().expect("join proxy worker");
    upstream.join().expect("join receipt upstream");
    assert!(echoed_payloads.iter().all(|echoed| echoed == &payload));
    let terminal = terminal_result.expect("proxy terminal receipt");
    let mut terminal_evidence = terminal.desync_execution_evidence().to_vec();
    terminal_evidence.sort_by_key(DesyncExecutionEvidence::connection_ordinal);

    assert_eq!(terminal_evidence.len(), 2, "terminal receipt must retain both connection receipts");
    assert_eq!(
        terminal_evidence.iter().map(DesyncExecutionEvidence::connection_ordinal).collect::<Vec<_>>(),
        vec![1, 2],
    );

    for evidence in &terminal_evidence {
        assert_eq!(evidence.generation(), generation);
        assert_eq!(evidence.attempt_token().as_opaque_str(), attempt_fixture);
        let receipt = evidence.receipt();
        assert_eq!(receipt.transport(), DesyncExecutionTransport::Tcp);
        assert_eq!(receipt.disposition(), DesyncExecutionDisposition::Applied);
        assert_eq!(receipt.configured_family(), Some(DesyncStrategyFamily::Split));
        assert_eq!(receipt.effective_family(), Some(DesyncStrategyFamily::Split));
        assert_eq!(receipt.marker_base(), Some(DesyncOffsetMarkerBase::Host));
        assert_eq!(receipt.marker_delta(), Some(1));
        assert_eq!(receipt.resolved_offset(), Some(split_boundary as u16));
        assert_eq!(receipt.planned_steps(), 1);
        assert_eq!(receipt.attempted_actions(), 3);
        assert_eq!(receipt.completed_actions(), 3);
        assert_eq!(receipt.real_writes_committed(), 2);
        assert_eq!(receipt.completed_awaits(), 1);
        assert_eq!(receipt.payload_bytes_committed(), payload.len());
        assert!(!receipt.tls_record_prelude_applied());
        assert_eq!(receipt.fallback_reason(), None);
        assert_eq!(receipt.terminal_reason(), None);
    }
    assert!(!terminal.desync_execution_evidence_overflowed());

    let evidence_debug = format!("{terminal_evidence:?}");
    assert!(!evidence_debug.contains(host), "receipt must not join domain/host into evidence");
    assert!(!evidence_debug.contains("127.0.0.1"), "receipt must not join IP into evidence");
    assert!(!evidence_debug.contains(&upstream_addr.port().to_string()), "receipt must not join port into evidence");
    assert!(
        !evidence_debug.contains(std::str::from_utf8(&payload).expect("payload utf8")),
        "receipt must not join raw payload into evidence"
    );
    assert!(!evidence_debug.contains(auth_fixture), "receipt debug must redact SOCKS password/runtime secret");
}

#[test]
fn udp_associate_quic_desync_records_execution_receipt_for_attempt_token() {
    let generation = 1_786_885_745_241_508_u64;
    let attempt_fixture = "attempt-token-red-udp";
    let auth_fixture = "runtime-secret-red-udp";
    let logical_payload = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "receipt-udp.example.test");
    let upstream = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind UDP receipt upstream");
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("set upstream timeout");
    let upstream_port = upstream.local_addr().expect("UDP receipt upstream address").port();
    let expected_payload = logical_payload.clone();
    let upstream_worker = std::thread::spawn(move || {
        let mut buffer = [0_u8; 4096];
        loop {
            let (read, sender) = upstream.recv_from(&mut buffer).expect("receive UDP desync payload");
            upstream.send_to(&buffer[..read], sender).expect("echo UDP desync payload");
            if buffer[..read] == expected_payload {
                break;
            }
        }
    });

    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.network.listen.auth_token = Some(auth_fixture.to_string());
    config.groups[0].matches.proto = ripdpi_packets::IS_UDP;
    config.groups[0].actions.ttl = Some(8);
    config.groups[0].actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicSniSplit,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let listener = ripdpi_proxy_runtime::create_listener(&config).expect("create proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy listener address");
    let control = Arc::new(EmbeddedProxyControl::new_with_desync_execution_evidence(None, None, generation));
    let worker_control = control.clone();
    let worker = std::thread::spawn(move || {
        ripdpi_proxy_runtime::run_proxy_with_embedded_control_receipt(config, listener, worker_control)
    });

    let (_control_stream, relay) = socks_udp_associate_with_userpass(proxy_addr.port(), attempt_fixture, auth_fixture);
    let udp = udp_proxy_client();
    assert_eq!(
        udp_proxy_roundtrip_with_socket(&udp, relay, upstream_port, &logical_payload),
        logical_payload,
        "the logical QUIC payload must be committed after the decoy"
    );

    control.request_shutdown();
    let _ = TcpStream::connect(proxy_addr);
    let terminal = worker.join().expect("join proxy worker").expect("proxy terminal receipt");
    upstream_worker.join().expect("join UDP receipt upstream");

    let evidence =
        terminal.desync_execution_evidence().first().expect("one UDP logical payload must retain one receipt");
    assert_eq!(evidence.generation(), generation);
    assert_eq!(evidence.attempt_token().as_opaque_str(), attempt_fixture);
    assert_eq!(evidence.connection_ordinal(), 1);
    assert_eq!(evidence.receipt().transport(), DesyncExecutionTransport::Udp);
    assert_eq!(evidence.receipt().configured_family(), Some(DesyncStrategyFamily::QuicSniSplit));
    assert_eq!(evidence.receipt().effective_family(), Some(DesyncStrategyFamily::QuicSniSplit));
    assert_eq!(evidence.receipt().completed_awaits(), 0);
    assert!(!evidence.receipt().tls_record_prelude_applied());
    assert_eq!(evidence.receipt().fallback_reason(), None);
    assert!(evidence.receipt().real_writes_committed() >= 1);
    assert_eq!(evidence.receipt().payload_bytes_committed(), logical_payload.len());
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn socks_connect_with_userpass_ip(proxy_port: u16, username: &str, password: &str, dst_port: u16) -> TcpStream {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream.set_read_timeout(Some(support::SOCKET_TIMEOUT)).expect("set socks auth read timeout");
    stream.set_write_timeout(Some(support::SOCKET_TIMEOUT)).expect("set socks auth write timeout");
    stream.write_all(&[0x05, 0x01, 0x02]).expect("write socks userpass greeting");
    assert_eq!(recv_exact(&mut stream, 2), b"\x05\x02");

    let username = username.as_bytes();
    let password = password.as_bytes();
    assert!(username.len() <= u8::MAX as usize, "SOCKS username fits RFC1929");
    assert!(password.len() <= u8::MAX as usize, "SOCKS password fits RFC1929");
    let mut auth = Vec::with_capacity(3 + username.len() + password.len());
    auth.extend([0x01, username.len() as u8]);
    auth.extend(username);
    auth.push(password.len() as u8);
    auth.extend(password);
    stream.write_all(&auth).expect("write socks userpass auth");
    assert_eq!(recv_exact(&mut stream, 2), b"\x01\x00");

    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    request.extend(Ipv4Addr::LOCALHOST.octets());
    request.extend(dst_port.to_be_bytes());
    stream.write_all(&request).expect("write socks connect");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 connect failed: {reply:?}");
    stream
}

fn socks_udp_associate_with_userpass(
    proxy_port: u16,
    username: &str,
    password: &str,
) -> (TcpStream, std::net::SocketAddr) {
    let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, proxy_port)).expect("connect to proxy");
    stream.set_read_timeout(Some(support::SOCKET_TIMEOUT)).expect("set socks auth read timeout");
    stream.set_write_timeout(Some(support::SOCKET_TIMEOUT)).expect("set socks auth write timeout");
    stream.write_all(&[0x05, 0x01, 0x02]).expect("write SOCKS userpass greeting");
    assert_eq!(recv_exact(&mut stream, 2), b"\x05\x02");
    let username = username.as_bytes();
    let password = password.as_bytes();
    let mut auth = Vec::with_capacity(3 + username.len() + password.len());
    auth.extend([0x01, username.len() as u8]);
    auth.extend(username);
    auth.push(password.len() as u8);
    auth.extend(password);
    stream.write_all(&auth).expect("write SOCKS userpass auth");
    assert_eq!(recv_exact(&mut stream, 2), b"\x01\x00");
    stream.write_all(&[0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).expect("write SOCKS UDP associate");
    let reply = recv_socks5_reply(&mut stream);
    assert_eq!(reply[1], 0, "SOCKS5 UDP associate failed: {reply:?}");
    let relay = support::socks5::parse_socks5_reply_addr(&reply);
    (stream, relay)
}
