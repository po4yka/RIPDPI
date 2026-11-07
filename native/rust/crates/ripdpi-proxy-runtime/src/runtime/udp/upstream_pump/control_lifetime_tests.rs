use super::*;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::thread;
use std::time::Duration;

use crate::runtime::config::RuntimeConfig;
use crate::runtime::udp::client_receive::UdpClientPacket;
use crate::runtime::udp::flow_selection::ensure_udp_flow_selected;
use crate::sync::{Arc, AtomicBool};
use ripdpi_proxy_runtime_adapter::model::config::UpstreamSocksConfig;

#[test]
fn fresh_associate_reply_then_fin_cannot_send_its_first_payload() {
    let (state, mut flows, _client, relay, _listener) = closed_fresh_association();
    let entry = flows.values_mut().next().expect("fresh flow");
    let error =
        send_udp_flow_payload(&state, entry, b"first", Instant::now(), None).expect_err("control is already closed");
    assert_eq!(error.kind(), io::ErrorKind::ConnectionAborted);
    assert_no_datagram(&relay);
}

#[test]
fn queued_downlink_is_not_delivered_after_control_fin() {
    let (state, mut flows, client, relay, _listener) = closed_fresh_association();
    let upstream = flows.values().next().expect("fresh flow").upstream.local_addr().expect("upstream socket");
    relay.send_to(b"\0\0\0\x01\xc0\0\x02\x0a\x11\x5creply", upstream).expect("queue reply");
    thread::sleep(Duration::from_millis(10));
    let client_relay = udp();
    pump_udp_upstream_responses(
        &state,
        &client_relay,
        &mut [0; 128],
        &mut Vec::new(),
        &mut flows,
        &mut UdpUpstreamPollScratch::default(),
        None,
    )
    .expect("closed flow is discarded");
    assert_no_datagram(&client);
    assert!(flows.is_empty(), "the invalid binding must be retired");
}

#[test]
fn closed_control_retirement_and_expiry_do_not_replay_cached_payload_to_a_fallback() {
    for expiry in [false, true] {
        let (state, mut flows, _client, relay, listener) = closed_fresh_association();
        let key = flows.keys().next().expect("flow key").clone();
        let entry = flows.get_mut(&key).expect("fresh flow");
        entry.payload = b"previous-datagram".to_vec();
        entry.awaiting_response = true;
        entry.target_candidates.push(SocketAddr::from(([192, 0, 2, 20], 4444)));
        listener.set_nonblocking(true).expect("nonblocking fallback listener");
        let relay_port = relay.local_addr().expect("relay address").port();
        let peer = thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_millis(400);
            loop {
                match listener.accept() {
                    Ok((mut control, _)) => {
                        control.set_nonblocking(false).expect("blocking fallback stream");
                        control.set_read_timeout(Some(Duration::from_secs(1))).expect("fallback timeout");
                        let mut greeting = [0; 3];
                        control.read_exact(&mut greeting).expect("fallback greeting");
                        assert_eq!(greeting, [5, 1, 0]);
                        control.write_all(&[5, 0]).expect("fallback no auth");
                        let mut request = [0; 10];
                        control.read_exact(&mut request).expect("fallback ASSOCIATE");
                        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
                        let mut reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
                        reply.extend_from_slice(&relay_port.to_be_bytes());
                        control.write_all(&reply).expect("fallback relay");
                        let _ = control.read(&mut [0; 1]);
                        return true;
                    }
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(10));
                    }
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => return false,
                    error => panic!("fallback accept: {error:?}"),
                }
            }
        });
        if expiry {
            crate::runtime::udp::flow::expire_udp_flows(
                &state,
                &mut flows,
                None,
                Instant::now() + crate::runtime::state::UDP_FLOW_IDLE_TIMEOUT,
                &mut Vec::new(),
            )
            .expect("expire closed control");
        } else {
            retire_failed_udp_flow(&state, &mut flows, &key, None, Instant::now()).expect("retire closed control");
        }
        let retained = !flows.is_empty();
        let mut buffer = [0; 128];
        let wire = relay.recv_from(&mut buffer);
        if let Ok((length, _)) = wire {
            assert_eq!(&buffer[10..length], b"previous-datagram", "observe the exact cached payload");
        }
        flows.clear();
        let contacted_fallback = peer.join().expect("fallback peer");
        assert!(!contacted_fallback, "control closure must not select another target; wire: {wire:?}");
        assert!(!retained, "closed control must be removed");
        assert!(
            matches!(wire, Err(ref error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut)),
            "old payload must not be replayed: {wire:?}"
        );
    }
}

fn closed_fresh_association()
-> (RuntimeState, HashMap<UdpFlowKey, UdpFlowActivationState>, UdpSocket, UdpSocket, TcpListener) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("control listener");
    let relay = udp();
    let relay_port = relay.local_addr().expect("relay address").port();
    let mut config = RuntimeConfig::default();
    config.groups[0].policy.ext_socks =
        Some(UpstreamSocksConfig { addr: listener.local_addr().expect("control address"), auth: None });
    let peer_listener = listener.try_clone().expect("clone listener");
    let peer = thread::spawn(move || {
        let (mut control, _) = peer_listener.accept().expect("control client");
        let mut greeting = [0; 3];
        control.read_exact(&mut greeting).expect("greeting");
        assert_eq!(greeting, [5, 1, 0]);
        control.write_all(&[5, 0]).expect("no auth");
        let mut request = [0; 10];
        control.read_exact(&mut request).expect("ASSOCIATE");
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        let mut reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
        reply.extend_from_slice(&relay_port.to_be_bytes());
        control.write_all(&reply).expect("successful reply");
    });
    let state = RuntimeState::test(config);
    let client = udp();
    let packet = UdpClientPacket {
        sender: client.local_addr().expect("client address"),
        original_target: SocketAddr::from(([192, 0, 2, 10], 4444)),
        payload: b"first",
        host: None,
        preserve_host_in_response: false,
        cache_host: false,
        host_is_authoritative: false,
    };
    let mut flows = HashMap::new();
    assert!(
        ensure_udp_flow_selected(
            &state,
            None,
            &mut flows,
            8,
            &packet,
            Instant::now(),
            None,
            &Arc::new(AtomicBool::new(true)),
        )
        .expect("fresh association")
    );
    peer.join().expect("closed control peer");
    let control = flows.values().next().expect("selected flow").upstream_socks.as_ref().expect("SOCKS session");
    let deadline = Instant::now() + Duration::from_secs(1);
    while control.is_open() && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
    assert!(!control.is_open(), "FIN must be queued before the boundary under test");
    (state, flows, client, relay, listener)
}

fn udp() -> UdpSocket {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("UDP socket");
    socket.set_read_timeout(Some(Duration::from_millis(100))).expect("UDP timeout");
    socket
}

fn assert_no_datagram(socket: &UdpSocket) {
    let received = socket.recv_from(&mut [0; 128]);
    assert!(
        matches!(received, Err(ref error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut)),
        "closed association emitted a datagram: {received:?}"
    );
}
