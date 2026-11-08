use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UdpSocket;
use tokio::sync::oneshot;
use tokio::task::JoinSet;

use ripdpi_flow_app_attribution::{
    FlowResolveRequest, evict_flow_if_current, note_flow, store_uid_and_scope_resolution, take_pending_request,
};
use ripdpi_tunnel_intercept::egress::{RawTunPacketInjector, TunEgressInterceptor};

use super::super::super::udp_assoc::UdpEvent;
use super::*;

struct PureDropScript(std::path::PathBuf);

impl PureDropScript {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "vpn08-udp-raw-owner-{}-{:?}",
            std::process::id(),
            std::thread::current().id(),
        ));
        std::fs::create_dir_all(&path).unwrap();
        std::fs::write(
            path.join("candidate.lua"),
            r#"
function candidate(desync)
    if desync.payload == "pass" then return VERDICT_PASS end
    return VERDICT_DROP
end
"#,
        )
        .unwrap();
        Self(path)
    }

    fn interceptor(&self, lua_socket_owned: bool) -> TunEgressInterceptor<RawTunPacketInjector> {
        // The injector constructor stores configuration only. Pure DROP has no send actions.
        TunEgressInterceptor::new_with_lua_owner(
            Some(
                "version: 1\nstrategies:\n  - id: raw-owner\n    steps:\n      - type: lua\n        function: candidate\n        script_paths: [candidate.lua]\n",
            ),
            &self.0,
            lua_socket_owned,
            RawTunPacketInjector::new(None),
        )
    }
}

impl Drop for PureDropScript {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the owning peer closes each stream after a partial handshake.
async fn no_auth_command(stream: &mut tokio::net::TcpStream) -> u8 {
    let mut greeting = [0; 2];
    stream.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting[0], 5);
    let mut methods = vec![0; usize::from(greeting[1])];
    stream.read_exact(&mut methods).await.unwrap();
    assert!(methods.contains(&0));
    stream.write_all(&[5, 0]).await.unwrap();
    let mut request = [0; 4];
    stream.read_exact(&mut request).await.unwrap();
    assert_eq!((request[0], request[2]), (5, 0));
    let size = match request[3] {
        1 => 6,
        4 => 18,
        other => panic!("unexpected endpoint type {other}"),
    };
    stream.read_exact(&mut vec![0; size]).await.unwrap();
    request[1]
}

/// # Cancel safety:
/// cancel-safe: the future owns all sockets; its JoinSet aborts it on drop.
async fn observe_plain_pass(
    listener: TcpListener,
    relay: UdpSocket,
    receipt: oneshot::Sender<Vec<u8>>,
    cancel: CancellationToken,
) {
    for _ in 0..2 {
        let (mut stream, _) = listener.accept().await.unwrap();
        if no_auth_command(&mut stream).await != 3 {
            stream.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
            continue;
        }
        let port = relay.local_addr().unwrap().port().to_be_bytes();
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        let mut datagram = vec![0; 2048];
        let (length, source) = relay.recv_from(&mut datagram).await.unwrap();
        datagram.truncate(length);
        receipt.send(datagram).unwrap();
        relay.send_to(b"\0\0\0\x01\x5d\xb8\xd8\x22\x01\xbbold owner", source).await.unwrap();
        cancel.cancelled().await;
        return;
    }
    panic!("client did not fall back to standard UDP ASSOCIATE");
}

struct AttributionSession(ripdpi_flow_app_attribution::AttributionGeneration);

impl Drop for AttributionSession {
    fn drop(&mut self) {
        ripdpi_flow_app_attribution::end_attribution_session_if(self.0);
    }
}

#[test]
fn lua_drop_retires_prior_udp_owner_without_waiting_for_attribution() {
    run_drop_case("lua_drop_retires_prior_udp_owner_without_waiting_for_attribution", false);
}

#[test]
fn stun_drop_retires_prior_udp_owner_without_waiting_for_attribution() {
    run_drop_case("stun_drop_retires_prior_udp_owner_without_waiting_for_attribution", true);
}

fn run_drop_case(case: &str, stun: bool) {
    crate::io_loop::test_isolation::run_isolated_test(module_path!(), case, || {
        let _session = AttributionSession(ripdpi_flow_app_attribution::begin_attribution_session());
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(exercise_policy_drop_owner(stun));
    });
}

/// # Cancel safety:
/// NOT cancel-safe: the owning loop must reach shutdown to join every UDP worker.
async fn exercise_policy_drop_owner(stun: bool) {
    // RFC 8489 section 5: Binding request, zero attributes, cookie, fixed 96-bit test transaction ID.
    const STUN_BINDING_REQUEST: &[u8] = &[0, 1, 0, 0, 0x21, 0x12, 0xa4, 0x42, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    let drop_payload = if stun { STUN_BINDING_REQUEST } else { b"drop" };
    let source_port = if stun { 55676 } else { 55674 };
    let script = PureDropScript::new();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let mut state = test_loop_state(Box::new(script.interceptor(stun)));
    state.runtime.proxy_sockaddr = listener.local_addr().unwrap();
    state.runtime.udp_idle_timeout = Duration::from_secs(30);
    state.runtime.webrtc_protection_enabled = stun;
    let request = FlowResolveRequest {
        protocol: 17,
        local: SocketAddr::from(([10, 0, 0, 2], source_port)),
        remote: "93.184.216.34:443".parse().unwrap(),
    };
    let original = note_flow(17, request.local, request.remote).registration_id;
    let job = take_pending_request(request).unwrap();
    store_uid_and_scope_resolution(&job, Some(10123), Some("81c5afbf-9a33-4680-880a-06c47840e52b"));
    let (receipt_tx, receipt_rx) = oneshot::channel();
    let peer_cancel = CancellationToken::new();
    let mut peers = JoinSet::new();
    peers.spawn(observe_plain_pass(listener, relay, receipt_tx, peer_cancel.clone()));
    route_tun_packet(&ipv4_udp_packet(source_port, 443, b"pass"), &mut state);
    let first_wire = tokio::time::timeout(Duration::from_secs(3), receipt_rx).await.unwrap().unwrap();
    let old_event = tokio::time::timeout(Duration::from_secs(3), state.udp_rx.recv()).await.unwrap().unwrap();
    assert!(matches!(&old_event, UdpEvent::Packet { .. }));
    tokio::time::sleep(Duration::from_millis(5050)).await;
    route_tun_packet(&ipv4_udp_packet(source_port, 443, drop_payload), &mut state);
    handle_wait_event(&mut state, WaitEvent::Udp(Some(old_event)));
    let leaked = state.device.tx_queue.pop_front();
    let successor_job = take_pending_request(request);
    let successor = note_flow(17, request.local, request.remote).registration_id;
    let raw_has_no_destination = state.udp_associations.test_destination_snapshot(&successor).is_none();
    let raw_has_no_pending_packet = state.pending_uid_packets.is_empty();
    route_tun_packet(&ipv4_udp_packet(source_port, 443, drop_payload), &mut state);
    let repeated = note_flow(17, request.local, request.remote).registration_id;
    let duplicate_job = take_pending_request(request);
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10123]));
    let armed_port = source_port + 1;
    let armed_request = FlowResolveRequest { local: SocketAddr::from(([10, 0, 0, 2], armed_port)), ..request };
    route_tun_packet(&ipv4_udp_packet(armed_port, 443, drop_payload), &mut state);
    let armed_pending = state.pending_uid_packets.len();
    let armed_job = take_pending_request(armed_request);
    let armed_registered = armed_job.is_some();
    let armed_registration = note_flow(17, armed_request.local, armed_request.remote).registration_id;
    if let Some(job) = armed_job {
        store_uid_and_scope_resolution(&job, Some(10123), None);
    }
    retry_pending_uid_packets(&mut state);
    let armed_released = matches!(
        ripdpi_flow_app_attribution::lookup_registered_flow_uid(&armed_registration),
        ripdpi_flow_app_attribution::FlowUidLookup::Missing,
    );
    let armed_no_destination = state.udp_associations.test_destination_snapshot(&armed_registration).is_none();
    state.shutdown().await;
    peer_cancel.cancel();
    peers.join_next().await.unwrap().expect("real SOCKS peer joined");
    evict_flow_if_current(repeated);
    evict_flow_if_current(armed_registration);
    assert_eq!(first_wire, b"\0\0\0\x01\x5d\xb8\xd8\x22\x01\xbbpass");
    assert!(leaked.is_none(), "policy drop must retire the old owner's queued UDP reply after tuple refresh");
    assert!(successor_job.is_some(), "consumed policy drop must still register its new finite owner generation");
    assert_ne!(successor, original);
    assert!(raw_has_no_destination, "policy drop must not forward its packet through SOCKS");
    assert!(raw_has_no_pending_packet, "policy drop must not wait for the attribution grace");
    assert_eq!(repeated, successor, "policy drop must not evict and recreate its UID generation per packet");
    assert!(duplicate_job.is_none(), "repeated policy drop must not schedule duplicate owner jobs");
    assert!(armed_registered, "armed policy drop must still observe the exact tuple generation");
    assert!(armed_no_destination, "armed policy drop must not reach SOCKS");
    if stun {
        assert_eq!(armed_pending, 0, "STUN protection must drop before UID admission even when armed");
    } else {
        assert_eq!(armed_pending, 1, "enforcing policy must admit a fresh UID before Lua can consume a packet");
        assert!(armed_released, "armed raw consumption must release an unowned registration as before");
    }
}
