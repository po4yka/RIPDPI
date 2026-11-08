use super::*;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::net::UdpSocket;

use ripdpi_proxy_runtime_adapter::model::session::udp_feedback;

fn state_with_auth(token: Option<&str>, port: u16) -> RuntimeState {
    let mut config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
    config.network.listen.auth_token = token.map(str::to_owned);
    config.network.listen.listen_port = port;
    runtime_state(config)
}

fn authenticate(client: &mut TcpStream, auth: bool) {
    if auth {
        client.write_all(b"\x05\x01\x02\x01\x06ripdpi\x06secret").expect("authenticate");
        let mut reply = [0; 4];
        client.read_exact(&mut reply).expect("auth reply");
        assert_eq!(reply, [5, 2, 1, 0]);
    } else {
        client.write_all(&[5, 1, 0]).expect("offer no auth");
        let mut reply = [0; 2];
        client.read_exact(&mut reply).expect("auth reply");
        assert_eq!(reply, [5, 0]);
    }
}

fn associate_request(source: SocketAddr) -> Vec<u8> {
    let SocketAddr::V4(source) = source else { panic!("local IPv4 control fixture") };
    let mut request = vec![5, udp_feedback::COMMAND, 0, 1];
    request.extend_from_slice(&source.ip().octets());
    request.extend_from_slice(&source.port().to_be_bytes());
    request
}

#[test]
fn private_udp_requires_owned_authenticated_listener_and_exact_source() {
    for (token, port, source) in [
        (None, 0, SocketAddr::from(([127, 0, 0, 1], 5000))),
        (Some("secret"), 1080, SocketAddr::from(([127, 0, 0, 1], 5000))),
        (Some("secret"), 0, SocketAddr::from(([127, 0, 0, 1], 0))),
        (Some("secret"), 0, SocketAddr::from(([127, 0, 0, 2], 5000))),
    ] {
        let state = state_with_auth(token, port);
        let (mut client, server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(2))).expect("bound reply wait");
        let worker = thread::spawn(move || super::super::handle_client(server, &state));
        authenticate(&mut client, token.is_some());
        client.write_all(&associate_request(source)).expect("associate request");
        let mut reply = [0; 10];
        client.read_exact(&mut reply).expect("failure reply");
        assert_eq!(&reply[..3], &[5, 7, 0]);
        drop(client);
        worker.join().expect("worker").expect("unsupported command handled");
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
#[test]
fn private_udp_is_not_advertised_without_remote_error_queue_support() {
    let state = state_with_auth(Some("secret"), 0);
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("bound reply wait");
    let worker = thread::spawn(move || super::super::handle_client(server, &state));
    authenticate(&mut client, true);
    client.write_all(&associate_request(SocketAddr::from(([127, 0, 0, 1], 5000)))).expect("request");
    let mut reply = [0; 10];
    client.read_exact(&mut reply).expect("unsupported reply");
    assert_eq!(reply[1], 7);
    drop(client);
    worker.join().expect("worker").expect("unsupported handled");
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[test]
fn remote_udp_errors_and_data_share_the_authenticated_association() {
    for ip in [IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(std::net::Ipv6Addr::LOCALHOST)] {
        let state = state_with_auth(Some("secret"), 0);
        let (mut control, server) = connected_pair();
        control.set_read_timeout(Some(Duration::from_secs(3))).expect("bound reply wait");
        let worker_state = state.clone();
        let worker = thread::spawn(move || super::super::handle_client(server, &worker_state));
        authenticate(&mut control, true);
        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("UDP client");
        client.set_read_timeout(Some(Duration::from_secs(3))).expect("bounded feedback wait");
        control.write_all(&associate_request(client.local_addr().expect("source"))).expect("request");
        let mut reply = [0; 10];
        control.read_exact(&mut reply).expect("associate reply");
        assert_eq!(&reply[..4], &[5, 0, 0, 1]);
        let relay =
            SocketAddr::from(([reply[4], reply[5], reply[6], reply[7]], u16::from_be_bytes([reply[8], reply[9]])));
        client.connect(relay).expect("connect local carrier");
        let mut preface = [0; udp_feedback::PREFACE_LEN];
        control.read_exact(&mut preface).expect("feedback capability");
        let nonce = udp_feedback::decode_preface(&preface).expect("valid preface");
        let closed = UdpSocket::bind((ip, 0)).expect("reserve closed port");
        let target = closed.local_addr().expect("closed target");
        drop(closed);
        let payload = RuntimeState::encode_socks5_udp_packet(target, b"remote-error");
        let wire = udp_feedback::encode_request(&nonce, 1, &payload).expect("request envelope");
        client.send(&wire).expect("send closed-port request");
        let mut bytes = [0; 2048];
        let n = client.recv(&mut bytes).expect("remote ICMP feedback");
        let udp_feedback::Frame::Error { id, error } = udp_feedback::decode(&bytes[..n], &nonce).expect("feedback")
        else {
            panic!("expected remote error");
        };
        assert_eq!(id, 1);
        assert_eq!(error.target, target);
        assert_eq!(error.source, ip);
        assert_eq!((error.icmp_type, error.icmp_code), if ip.is_ipv4() { (3, 3) } else { (1, 4) });
        assert_eq!(error.info, 0);

        let upstream = UdpSocket::bind((ip, 0)).expect("live other flow");
        upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bound upstream wait");
        let target = upstream.local_addr().expect("live target");
        let payload = RuntimeState::encode_socks5_udp_packet(target, b"live-flow");
        let wire = udp_feedback::encode_request(&nonce, 2, &payload).expect("request envelope");
        client.send(&wire).expect("send other flow");
        let (n, sender) = upstream.recv_from(&mut bytes).expect("other flow remains usable");
        assert_eq!(&bytes[..n], b"live-flow");
        upstream.send_to(b"reply", sender).expect("live response");
        let n = client.recv(&mut bytes).expect("data response");
        let udp_feedback::Frame::Response { payload } = udp_feedback::decode(&bytes[..n], &nonce).expect("response")
        else {
            panic!("data must retain its own frame kind");
        };
        let (source, payload) = state.parse_socks5_udp_packet(payload, |_, _| None).expect("SOCKS response");
        assert_eq!(source, target);
        assert_eq!(payload, b"reply");

        client.send(&wire).expect("replay previous ID");
        let spoof = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("different local UDP port");
        let spoof_payload = RuntimeState::encode_socks5_udp_packet(target, b"wrong-source");
        spoof
            .send_to(&udp_feedback::encode_request(&nonce, 3, &spoof_payload).expect("spoof frame"), relay)
            .expect("send from wrong source");
        let good_payload = RuntimeState::encode_socks5_udp_packet(target, b"correct-source");
        client
            .send(&udp_feedback::encode_request(&nonce, 3, &good_payload).expect("frame"))
            .expect("valid source retains the ID");
        let (n, _) = upstream.recv_from(&mut bytes).expect("valid source forwarded");
        assert_eq!(&bytes[..n], b"correct-source");
        let mut wrong_nonce = nonce;
        wrong_nonce[0] ^= 1;
        client
            .send(&udp_feedback::encode_request(&wrong_nonce, 4, &spoof_payload).expect("wrong nonce frame"))
            .expect("invalid nonce");
        let good_payload = RuntimeState::encode_socks5_udp_packet(target, b"correct-nonce");
        client
            .send(&udp_feedback::encode_request(&nonce, 4, &good_payload).expect("frame"))
            .expect("valid nonce retains the ID");
        let (n, _) = upstream.recv_from(&mut bytes).expect("valid nonce forwarded");
        assert_eq!(&bytes[..n], b"correct-nonce");
        upstream.set_read_timeout(Some(Duration::from_millis(100))).expect("bound rejection check");
        let error = upstream.recv_from(&mut bytes).expect_err("replay and spoofed packets are not forwarded");
        assert!(matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut));
        control.shutdown(Shutdown::Both).expect("close control");
        worker.join().expect("worker").expect("association stops");
    }
}
