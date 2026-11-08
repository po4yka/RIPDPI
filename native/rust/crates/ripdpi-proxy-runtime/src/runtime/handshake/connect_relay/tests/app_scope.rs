use super::*;
use std::io::{Read, Write};
use std::net::UdpSocket;
use std::time::Duration;

const APP_A: &str = "11111111-1111-4111-8111-111111111111";
const APP_B: &str = "22222222-2222-4222-8222-222222222222";

#[test]
fn cloned_app_scopes_keep_same_ip_learning_independent() {
    let telemetry = StdArc::new(TestTelemetry::default());
    let listener = runtime_state(RuntimeConfig::default(), Some(telemetry.clone()));
    let app_a_udp = listener.with_app_scope(Some(APP_A.to_string()));
    let app_a_tcp = listener.with_app_scope(Some(APP_A.to_string()));
    let app_b = listener.with_app_scope(Some(APP_B.to_string()));
    let target = [SocketAddr::from(([203, 0, 113, 7], 443))];
    app_a_udp.note_direct_path_udp_failure(Some("same.example"), &target);
    app_b.note_direct_path_tcp_success(Some("same.example"), &target, None);
    assert!(
        telemetry.scoped_signals.lock().expect("scope events").is_empty(),
        "another app's TCP must not settle UDP failure"
    );
    app_a_tcp.note_direct_path_tcp_success(Some("same.example"), &target, None);
    listener.note_direct_path_quic_success(Some("same.example"), &target);
    assert_eq!(
        *telemetry.scoped_signals.lock().expect("scope events"),
        vec![("QUIC_BLOCKED_TCP_OK".to_string(), Some(APP_A.to_string())), ("QUIC_SUCCESS".to_string(), None)],
        "same-app flows share fallback evidence and the original listener remains unscoped",
    );
}

#[test]
fn authenticated_udp_association_preserves_app_scope_in_its_worker() {
    let telemetry = StdArc::new(TestTelemetry::default());
    let mut config = RuntimeConfig::default();
    config.network.listen.auth_token = Some("secret".to_string());
    config.network.udp = true;
    let state = runtime_state(config, Some(telemetry.clone()));
    let (mut control, server) = connected_pair();
    control.set_read_timeout(Some(Duration::from_secs(3))).expect("bound control read");
    let handler = std::thread::spawn(move || crate::runtime::handshake::handle_client(server, &state));
    control
        .write_all(b"\x05\x01\x02\x01\x2avpn08:11111111-1111-4111-8111-111111111111\x06secret")
        .expect("send literal authenticated scope");
    let mut auth_reply = [0; 4];
    control.read_exact(&mut auth_reply).expect("read method and password status");
    assert_eq!(auth_reply, [5, 2, 1, 0]);

    let peer = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind echo peer");
    peer.set_read_timeout(Some(Duration::from_secs(3))).expect("bound echo read");
    let peer_addr = peer.local_addr().expect("echo peer address");
    let echo = std::thread::spawn(move || {
        let mut bytes = [0; 32];
        let (count, sender) = peer.recv_from(&mut bytes).expect("receive forwarded datagram");
        assert_eq!(&bytes[..count], b"scope-echo");
        peer.send_to(&bytes[..count], sender).expect("send echo response");
    });
    let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind UDP app socket");
    client.set_read_timeout(Some(Duration::from_secs(3))).expect("bound UDP response");
    let mut request = vec![5, 3, 0, 1, 127, 0, 0, 1];
    request.extend_from_slice(&client.local_addr().expect("UDP app address").port().to_be_bytes());
    control.write_all(&request).expect("send RFC1928 UDP ASSOCIATE");
    let mut reply = [0; 10];
    control.read_exact(&mut reply).expect("read UDP ASSOCIATE reply");
    assert_eq!(&reply[..4], &[5, 0, 0, 1]);
    let relay = SocketAddr::from(([127, 0, 0, 1], u16::from_be_bytes([reply[8], reply[9]])));
    let mut datagram = vec![0, 0, 0, 1, 127, 0, 0, 1];
    datagram.extend_from_slice(&peer_addr.port().to_be_bytes());
    datagram.extend_from_slice(b"scope-echo");
    client.send_to(&datagram, relay).expect("send literal SOCKS UDP frame");
    let mut response = [0; 32];
    let count = client.recv(&mut response).expect("receive relayed UDP echo");
    assert_eq!(&response[..count], datagram.as_slice());
    assert_eq!(
        *telemetry.scoped_signals.lock().expect("scope events"),
        vec![("QUIC_SUCCESS".to_string(), Some(APP_A.to_string()))]
    );
    control.shutdown(std::net::Shutdown::Both).expect("close control");
    handler.join().expect("join association handler").expect("finish association");
    echo.join().expect("join echo peer");
}
