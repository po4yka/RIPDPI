use super::*;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

const APP_SCOPE: &str = "e1b35e2a-d892-456f-b073-0422d4c8a141";

#[test]
fn synthetic_tcp_keeps_original_owner_and_scoped_real_target_on_wire() {
    crate::io_loop::test_isolation::run_isolated_test(
        module_path!(),
        "synthetic_tcp_keeps_original_owner_and_scoped_real_target_on_wire",
        || {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("isolated runtime")
                .block_on(synthetic_tcp_keeps_original_owner_and_scoped_real_target_on_wire_fixture());
        },
    );
}

/// # Cancel safety:
/// NOT cancel-safe: this test runs to completion. Dropping it during the app write or peer wait
/// can detach its tasks. Its bounded peer-wait path cancels and joins both tasks before assertions.
async fn synthetic_tcp_keeps_original_owner_and_scoped_real_target_on_wire_fixture() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("local SOCKS peer");
    listener.set_nonblocking(true).expect("nonblocking peer");
    let proxy = listener.local_addr().expect("peer address");
    let listener = tokio::net::TcpListener::from_std(listener).expect("async peer");
    let mut peer = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.expect("TUN SOCKS session");
        let mut greeting = [0; 3];
        stream.read_exact(&mut greeting).await.expect("RFC1928 greeting");
        assert_eq!(greeting, [5, 1, 2]);
        stream.write_all(&[5, 2]).await.expect("select RFC1929");
        let mut header = [0; 2];
        stream.read_exact(&mut header).await.expect("RFC1929 header");
        assert_eq!(header[0], 1);
        let mut username = vec![0; usize::from(header[1])];
        stream.read_exact(&mut username).await.expect("username");
        assert_eq!(username, format!("vpn08:{APP_SCOPE}").as_bytes());
        let length = stream.read_u8().await.expect("password length");
        let mut password = vec![0; usize::from(length)];
        stream.read_exact(&mut password).await.expect("password");
        assert_eq!(password, b"fixture-token");
        stream.write_all(&[1, 0]).await.expect("accept auth");
        // The private resolved-domain contract retains the hostname and carries numeric real IP.
        let expected = b"\x05\x01\x00\x05\x0bmapped.test\x01\x7f\x00\x00\x01\x01\xbb";
        let mut request = vec![0; expected.len()];
        stream.read_exact(&mut request).await.expect("resolved-domain CONNECT");
        assert_eq!(request, expected);
        stream.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).await.expect("CONNECT reply");
        let mut body = [0; 5];
        stream.read_exact(&mut body).await.expect("forwarded application bytes");
        assert_eq!(&body, b"owned");
    });

    let (mut sessions, mut socket_set, cancel, registration) = {
        let _guard = UID_ADMISSION_TEST_GUARD.lock().expect("UID test guard");
        let mut device = TunDevice::new(1500);
        let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
        let mut iface = Interface::new(config, &mut device, Instant::now());
        iface.update_ip_addrs(|ips| {
            ips.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).expect("TUN address");
        });
        iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("route");
        iface.set_any_ip(true);
        let mut socket_set = SocketSet::new(vec![]);
        let mut listens = HashMap::new();
        let mut cursor = 0;
        let mut sessions = ActiveSessions::new(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut cache = DnsCache::new(0xC612_0000, 0xFFFE_0000, 8).expect("MapDNS cache");
        let synthetic = Ipv4Addr::from(cache.find("mapped.test", u32::from(Ipv4Addr::LOCALHOST)).expect("mapping").0);
        assert_ne!(synthetic, Ipv4Addr::LOCALHOST);
        let mut cache = Some(cache);
        let client = Ipv4Addr::new(10, 0, 0, 211);
        let syn = build_ipv4_tcp_syn_packet(client, synthetic, 56_173, 443);
        ensure_pending_listen_for_syn(&syn, &mut listens, &mut socket_set, crate::io_loop::TCP_SOCKET_BUF);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        let syn_ack = device.tx_queue.pop_front().expect("TUN SYN-ACK");
        let (sequence, _) = tcp_seq_ack(&syn_ack);
        device.rx_queue.push_back(build_ipv4_tcp_ack_packet(client, synthetic, 56_173, 443, 1, sequence + 1));
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        let request = ripdpi_flow_app_attribution::FlowResolveRequest {
            protocol: crate::uid_policy::PROTO_TCP,
            local: SocketAddr::new(client.into(), 56_173),
            remote: SocketAddr::new(synthetic.into(), 443),
        };
        let registration = *listens.values().next().expect("listener").attribution_id();
        let job = ripdpi_flow_app_attribution::take_pending_request(request).expect("original UID job");
        assert_eq!(job.request().remote, SocketAddr::new(synthetic.into(), 443));
        assert_ne!(job.request().remote.ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
        ripdpi_flow_app_attribution::store_uid_and_scope_resolution(&job, Some(10_123), Some(APP_SCOPE));
        let auth = super::super::Auth::UserPass { username: "ripdpi".to_owned(), password: "fixture-token".to_owned() };
        let policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::from([10_123]));
        let resetting = spawn_new_tcp_sessions(
            &mut socket_set,
            &mut sessions,
            &mut listens,
            &mut cursor,
            proxy,
            &auth,
            None,
            Duration::from_secs(1),
            Duration::from_secs(1),
            &cancel,
            &stats,
            &mut cache,
            None,
            &policy,
            None,
            None,
            None,
        );
        assert!(resetting.is_empty());
        assert_eq!(sessions.len(), 1);
        (sessions, socket_set, cancel, registration)
    };

    let (_, entry) = sessions.iter_mut().next().expect("admitted session");
    entry.smoltcp_side.write_all(b"owned").await.expect("app write");
    let observed = tokio::time::timeout(Duration::from_secs(2), &mut peer).await;
    cancel.cancel();
    let handles: Vec<_> = sessions.iter_mut().map(|(handle, _)| handle).collect();
    for handle in handles {
        if let Some(entry) = sessions.remove(handle) {
            entry.cancel.cancel();
            entry.handle.abort();
            let _ = entry.handle.await;
        }
        socket_set.remove(handle);
    }
    ripdpi_flow_app_attribution::evict_flow_if_current(registration);
    if observed.is_err() {
        peer.abort();
        let _ = peer.await;
    }
    observed.expect("bounded peer exchange").expect("independent peer assertions");
}
