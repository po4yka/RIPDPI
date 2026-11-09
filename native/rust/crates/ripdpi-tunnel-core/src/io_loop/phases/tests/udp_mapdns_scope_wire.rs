use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::task::JoinSet;

use super::*;

const SCOPE: &str = "81c5afbf-9a33-4680-880a-06c47840e52b";
const USERNAME: &[u8] = b"vpn08:81c5afbf-9a33-4680-880a-06c47840e52b";
const PASSWORD: &[u8] = b"owner-test-token";

struct WireReceipt {
    usernames: Vec<Vec<u8>>,
    datagram: Vec<u8>,
}

/// # Cancel safety:
/// NOT cancel-safe: the owning peer must close the stream after any partial exchange.
async fn authenticate(stream: &mut TcpStream) -> Vec<u8> {
    let mut greeting = [0; 2];
    stream.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting[0], 5);
    let mut methods = vec![0; usize::from(greeting[1])];
    stream.read_exact(&mut methods).await.unwrap();
    assert!(methods.contains(&2), "the client must offer RFC 1929 authentication");
    stream.write_all(&[5, 2]).await.unwrap();
    let mut header = [0; 2];
    stream.read_exact(&mut header).await.unwrap();
    assert_eq!(header[0], 1);
    let mut username = vec![0; usize::from(header[1])];
    stream.read_exact(&mut username).await.unwrap();
    let mut length = [0; 1];
    stream.read_exact(&mut length).await.unwrap();
    let mut password = vec![0; usize::from(length[0])];
    stream.read_exact(&mut password).await.unwrap();
    assert_eq!(password, PASSWORD);
    stream.write_all(&[1, 0]).await.unwrap();
    username
}

/// # Cancel safety:
/// NOT cancel-safe: the owning peer must close the stream after a partial request.
async fn request_command(stream: &mut TcpStream) -> u8 {
    let mut header = [0; 4];
    stream.read_exact(&mut header).await.unwrap();
    assert_eq!((header[0], header[2]), (5, 0));
    let address_len = match header[3] {
        1 => 6,
        4 => 18,
        other => panic!("unexpected UDP associate address type {other}"),
    };
    let mut endpoint = vec![0; address_len];
    stream.read_exact(&mut endpoint).await.unwrap();
    header[1]
}

/// # Cancel safety:
/// cancel-safe: this future owns every socket and aborting its JoinSet task drops partial streams.
async fn observe_peer(listener: TcpListener, relay: UdpSocket) -> WireReceipt {
    let mut usernames = Vec::new();
    for _ in 0..2 {
        let (mut stream, _) = listener.accept().await.unwrap();
        usernames.push(authenticate(&mut stream).await);
        if request_command(&mut stream).await != 3 {
            // A standard peer explicitly declines the optional owned extension.
            stream.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
            continue;
        }
        let port = relay.local_addr().unwrap().port().to_be_bytes();
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
        let mut datagram = vec![0; 2048];
        let (length, source) = relay.recv_from(&mut datagram).await.unwrap();
        assert!(source.ip().is_loopback());
        datagram.truncate(length);
        return WireReceipt { usernames, datagram };
    }
    panic!("the client did not retry a standard UDP ASSOCIATE");
}

#[test]
fn mapdns_udp_keeps_original_owner_tuple_and_sends_scope_with_real_target() {
    crate::io_loop::test_isolation::run_isolated_test(
        module_path!(),
        "mapdns_udp_keeps_original_owner_tuple_and_sends_scope_with_real_target",
        || {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("isolated runtime")
                .block_on(mapdns_udp_keeps_original_owner_tuple_and_sends_scope_with_real_target_fixture());
        },
    );
}

/// # Cancel safety:
/// NOT cancel-safe: the test must reach loop shutdown; the peer timeout is saved before cleanup.
async fn mapdns_udp_keeps_original_owner_tuple_and_sends_scope_with_real_target_fixture() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.proxy_sockaddr = proxy;
    state.runtime.auth =
        Auth::UserPass { username: "ripdpi".into(), password: String::from_utf8(PASSWORD.to_vec()).unwrap() };
    state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10133]));
    let mut peers = JoinSet::new();
    peers.spawn(observe_peer(listener, relay));
    let src = SocketAddr::from(([10, 0, 0, 2], 55671));
    let (synthetic_ip, synthetic, owner_request, admitted, pinned) = {
        let _binder_guard =
            crate::tunnel_api::direct_dns_binding::TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        state.active_direct_dns_generation = crate::tunnel_api::direct_dns_binding::current_direct_dns_generation();
        state.dns_cache = Some(crate::dns_cache::DnsCache::new(0xc612_0000, 0xfffe_0000, 2).unwrap());
        let synthetic_ip = state.dns_cache.as_mut().unwrap().find("owner.test", 0xcb00_7151).unwrap().0;
        let synthetic = SocketAddr::new(Ipv4Addr::from(synthetic_ip).into(), 443);
        let mut packet = Vec::new();
        etherparse::PacketBuilder::ipv4([10, 0, 0, 2], Ipv4Addr::from(synthetic_ip).octets(), 64)
            .udp(src.port(), 443)
            .write(&mut packet, b"test")
            .unwrap();
        route_tun_packet(&packet, &mut state);
        assert!(state.udp_associations.is_empty(), "unresolved owner must not allocate a transport");
        let job = ripdpi_flow_app_attribution::take_pending_request(ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: 17,
            local: src,
            remote: synthetic,
        })
        .expect("routing must request the original synthetic owner tuple");
        let owner_request = job.request();
        ripdpi_flow_app_attribution::store_uid_and_scope_resolution(&job, Some(10133), Some(SCOPE));
        retry_pending_uid_packets(&mut state);
        let admitted = !state.udp_associations.is_empty() && state.pending_uid_packets.is_empty();
        let pinned = state.dns_cache.as_ref().unwrap().lease_count(synthetic_ip);
        (synthetic_ip, synthetic, owner_request, admitted, pinned)
    };
    let receipt = tokio::time::timeout(Duration::from_secs(3), peers.join_next()).await;
    state.shutdown().await;
    let receipt = receipt.expect("loopback peer deadline").expect("peer task").expect("valid peer exchange");

    assert!(admitted, "resolved owner must reach the normal UDP transport once");
    assert_eq!(pinned, 1);
    assert_eq!(owner_request.local, src);
    assert_eq!(owner_request.remote, synthetic, "owner lookup must use the kernel-visible synthetic destination");
    assert_ne!(owner_request.remote, SocketAddr::from(([203, 0, 113, 81], 443)));
    assert!(!receipt.usernames.is_empty());
    for username in receipt.usernames {
        assert_eq!(username, USERNAME, "literal RFC 1929 app scope");
    }
    // Literal oracle for the owned resolved-domain UDP envelope: real IPv4/443, not synthetic IPv4.
    assert_eq!(receipt.datagram, b"\0\0\0\x05\x0aowner.test\x01\xcb\0\x71\x51\x01\xbbtest");
    assert_eq!(state.dns_cache.as_ref().unwrap().lease_count(synthetic_ip), 0);
}
