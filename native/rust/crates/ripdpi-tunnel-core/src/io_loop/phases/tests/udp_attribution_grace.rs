use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::task::JoinSet;

use super::*;

#[derive(Clone, Copy)]
enum Case {
    LargeSoft,
    LargeArmed,
    FullSoft,
}

struct Epoch(ripdpi_flow_app_attribution::AttributionGeneration);

impl Drop for Epoch {
    fn drop(&mut self) {
        ripdpi_flow_app_attribution::end_attribution_session_if(self.0);
    }
}

#[test]
fn soft_owner_grace_forwards_reassembled_udp_without_space() {
    isolated_case("soft_owner_grace_forwards_reassembled_udp_without_space", Case::LargeSoft);
}

#[test]
fn soft_owner_grace_preserves_armed_reassembled_udp_block() {
    isolated_case("soft_owner_grace_preserves_armed_reassembled_udp_block", Case::LargeArmed);
}

#[test]
fn soft_owner_grace_full_queue_preserves_older_packets_and_pins() {
    isolated_case("soft_owner_grace_full_queue_preserves_older_packets_and_pins", Case::FullSoft);
}

fn isolated_case(name: &str, case: Case) {
    crate::io_loop::test_isolation::run_isolated_test(module_path!(), name, || {
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(grace_fixture(case));
    });
}

struct Receipt {
    usernames: Vec<Vec<u8>>,
    datagram: Vec<u8>,
}

/// # Cancel safety:
/// cancel-safe: the future owns its sockets; dropping it closes every partial exchange.
async fn observe_peer(listener: TcpListener, relay: UdpSocket) -> Receipt {
    let mut usernames = Vec::new();
    for _ in 0..2 {
        let (mut stream, _) = listener.accept().await.unwrap();
        let mut greeting = [0; 3];
        stream.read_exact(&mut greeting).await.unwrap();
        assert_eq!(greeting, [5, 1, 2]);
        stream.write_all(&[5, 2]).await.unwrap();
        let mut auth = [0; 2];
        stream.read_exact(&mut auth).await.unwrap();
        assert_eq!(auth[0], 1);
        let mut username = vec![0; usize::from(auth[1])];
        stream.read_exact(&mut username).await.unwrap();
        usernames.push(username);
        let length = stream.read_u8().await.unwrap();
        let mut password = vec![0; usize::from(length)];
        stream.read_exact(&mut password).await.unwrap();
        assert_eq!(password, b"grace-fixture-token");
        stream.write_all(&[1, 0]).await.unwrap();
        let mut request = [0; 10];
        stream.read_exact(&mut request).await.unwrap();
        assert_eq!([request[0], request[2], request[3]], [5, 0, 1]);
        if request[1] != 3 {
            stream.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
            continue;
        }
        let port = relay.local_addr().unwrap().port().to_be_bytes();
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        let mut datagram = vec![0; 4096];
        let (length, source) = relay.recv_from(&mut datagram).await.unwrap();
        assert!(source.ip().is_loopback());
        datagram.truncate(length);
        return Receipt { usernames, datagram };
    }
    panic!("standard UDP ASSOCIATE was not reached");
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation requires dropping this isolated test runtime.
/// Normal and receive-timeout paths shut down the loop and abort/join the peers.
async fn grace_fixture(case: Case) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let _epoch = Epoch(ripdpi_flow_app_attribution::begin_attribution_session());
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.proxy_sockaddr = listener.local_addr().unwrap();
    state.runtime.auth = Auth::UserPass { username: "control".into(), password: "grace-fixture-token".into() };
    let armed = matches!(case, Case::LargeArmed);
    if armed {
        state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
    }
    let mut pinned_ip = None;
    if matches!(case, Case::FullSoft) {
        let mut cache = crate::dns_cache::DnsCache::new(0xc612_0000, 0xffff_ff00, 1).unwrap();
        let (ip, _) = cache.find("queued.example", 0x5db8_d822).unwrap();
        pinned_ip = Some(ip);
        state.dns_cache = Some(cache);
        for offset in 0..PENDING_UID_CAPACITY {
            let mut packet = Vec::new();
            etherparse::PacketBuilder::ipv4([10, 0, 0, 2], ip.to_be_bytes(), 64)
                .udp(40_000 + u16::try_from(offset).unwrap(), 443)
                .write(&mut packet, b"queued")
                .unwrap();
            route_tun_packet(&packet, &mut state);
        }
        assert_eq!(state.pending_uid_packets.len(), PENDING_UID_CAPACITY);
        assert_eq!(state.dns_cache.as_ref().unwrap().lease_count(ip), PENDING_UID_CAPACITY);
    }
    let payload: Vec<u8> = (0..if pinned_ip.is_some() { 128 } else { 3000 }).map(|i| (i % 251) as u8).collect();
    let datagram = ipv4_udp_packet(55_871, 443, &payload);
    for fragment in ipv4_fragments(&datagram, 1480) {
        assert!(fragment.len() <= 1500);
        route_tun_packet(&fragment, &mut state);
    }
    let request = ripdpi_flow_app_attribution::FlowResolveRequest {
        protocol: crate::uid_policy::PROTO_UDP,
        local: "10.0.0.2:55871".parse().unwrap(),
        remote: "93.184.216.34:443".parse().unwrap(),
    };
    assert!(ripdpi_flow_app_attribution::take_pending_request(request).is_some());
    assert_eq!(
        ripdpi_flow_app_attribution::lookup_flow_uid(request.protocol, request.local, request.remote),
        ripdpi_flow_app_attribution::FlowUidLookup::Pending,
    );
    if armed {
        assert!(state.pending_uid_packets.is_empty());
        assert!(state.udp_associations.is_empty());
        assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 1);
        assert!(tokio::time::timeout(Duration::from_millis(30), listener.accept()).await.is_err());
        state.shutdown().await;
        return;
    }
    assert_eq!(state.stats.tun_forwarding_evidence_snapshot().tun_queue_drops, 0);
    if let Some(ip) = pinned_ip {
        assert_eq!(state.pending_uid_packets.len(), PENDING_UID_CAPACITY);
        assert_eq!(state.dns_cache.as_ref().unwrap().lease_count(ip), PENDING_UID_CAPACITY);
        let oldest = state.pending_uid_packets.pop_front().unwrap();
        assert_eq!(oldest.registration_id.request().local.port(), 40_000, "the oldest waiting flow was not evicted");
        state.pending_uid_packets.recycle(oldest, &mut state.dns_cache);
    } else {
        assert!(state.pending_uid_packets.is_empty());
    }
    assert_eq!(state.udp_associations.len(), 1);
    let mut peers = JoinSet::new();
    peers.spawn(observe_peer(listener, relay));
    let result = tokio::time::timeout(Duration::from_secs(2), peers.join_next()).await;
    state.shutdown().await;
    peers.shutdown().await;
    if let Some(ip) = pinned_ip {
        assert_eq!(state.dns_cache.as_ref().unwrap().lease_count(ip), 0);
    }
    let receipt = result.expect("fallback datagram reached the relay").unwrap().unwrap();
    assert!(receipt.usernames.iter().all(|username| username == b"control"), "unknown owner stays unscoped");
    assert_eq!(&receipt.datagram[..10], &[0, 0, 0, 1, 93, 184, 216, 34, 1, 187]);
    assert_eq!(&receipt.datagram[10..], payload);
}
