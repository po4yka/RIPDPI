use super::tests::tunnel_and_peer_with_config;
use super::*;
use boringtun::noise::rate_limiter::RateLimiter;
use boringtun::x25519::{PublicKey, StaticSecret};
use tokio::net::UdpSocket;

const UDP_IP: [u8; 28] =
    [69, 0, 0, 28, 0, 0, 0, 0, 64, 17, 102, 53, 10, 77, 0, 1, 10, 77, 0, 2, 202, 108, 202, 108, 0, 8, 0, 0];

/// # Cancel safety:
/// NOT cancel-safe: the caller owns and stops the tunnel receive task after the exchange.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn authenticated_transport_roams_between_both_families_and_rejects_redirection() {
    let (tunnel, original, mut peer) = tunnel_and_peer_with_config(WarpAmneziaConfig::default()).await;
    let bus = Bus::new();
    let mut events = bus.new_endpoint();
    let receiving = Arc::clone(&tunnel);
    let consumer = tokio::spawn(async move { receiving.consume_task(bus).await });
    let result = tokio::time::timeout(Duration::from_secs(5), async {
        tunnel.initiate_handshake().await.unwrap();
        let mut input = [0; 2048];
        let mut output = [0; 2048];
        let (size, client) = original.recv_from(&mut input).await.unwrap();
        let TunnResult::WriteToNetwork(response) = peer.decapsulate(None, &input[..size], &mut output) else {
            panic!("handshake response");
        };
        original.send_to(response, client).await.unwrap();
        let size = original.recv(&mut input).await.unwrap();
        assert!(matches!(peer.decapsulate(None, &input[..size], &mut output), TunnResult::Done));

        let mut last_transport = Vec::new();
        let mut current = None;
        for bind in ["[::1]:0", "127.0.0.1:0"] {
            let remote = UdpSocket::bind(bind).await.expect("available loopback family");
            let local_ip = if remote.local_addr().unwrap().is_ipv6() { "::1" } else { "127.0.0.1" };
            let target = SocketAddr::new(local_ip.parse().unwrap(), client.port());
            let TunnResult::WriteToNetwork(packet) = peer.encapsulate(&UDP_IP, &mut output) else {
                panic!("authenticated transport");
            };
            last_transport = packet.to_vec();
            remote.send_to(packet, target).await.unwrap();
            loop {
                if let Event::InboundInternetPacket(_, packet) = events.recv().await {
                    assert_eq!(packet.as_ref(), UDP_IP);
                    break;
                }
            }
            tunnel.send_ip_packet(&UDP_IP, &mut output).await;
            let size = remote.recv(&mut input).await.unwrap();
            let TunnResult::WriteToTunnelV4(packet, _) = peer.decapsulate(None, &input[..size], &mut output) else {
                panic!("traffic follows authenticated endpoint");
            };
            assert_eq!(packet, UDP_IP);
            current = Some(remote);
        }
        let current = current.unwrap();
        original.send_to(&last_transport, client).await.unwrap();
        let mut forged = last_transport;
        *forged.last_mut().unwrap() ^= 1;
        original.send_to(&forged, client).await.unwrap();
        let TunnResult::WriteToNetwork(malformed) = peer.encapsulate(&[0x30; 32], &mut output) else {
            panic!("authenticated malformed transport");
        };
        original.send_to(malformed, client).await.unwrap();

        for _ in 0..5 {
            tokio::time::sleep(Duration::from_millis(20)).await;
            tunnel.send_ip_packet(&UDP_IP, &mut output).await;
            let size = current.recv(&mut input).await.unwrap();
            assert!(matches!(peer.decapsulate(None, &input[..size], &mut output), TunnResult::WriteToTunnelV4(..)));
        }
        assert!(tokio::time::timeout(Duration::from_millis(50), original.recv(&mut input)).await.is_err());
    })
    .await;
    consumer.abort();
    let _ = consumer.await;
    result.expect("cross-family roaming and rejected-packet isolation");
}

/// # Cancel safety:
/// NOT cancel-safe: the caller owns and stops the tunnel receive task after the exchange.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn provisional_cookie_challenge_replies_to_source_without_roaming() {
    let (tunnel, original, mut peer) = tunnel_and_peer_with_config(WarpAmneziaConfig::default()).await;
    let local_secret = StaticSecret::from([21; 32]);
    let local_public = PublicKey::from(&local_secret);
    let remote_public = PublicKey::from(&StaticSecret::from([42; 32]));
    **tunnel.peer.lock().await =
        Tunn::new(local_secret, remote_public, None, None, 0, Some(Arc::new(RateLimiter::new(&local_public, 0))));
    let local = match &tunnel.carrier {
        WgCarrier::Udp { socket, .. } => {
            SocketAddr::new("127.0.0.1".parse().unwrap(), socket.local_addr().unwrap().port())
        }
        _ => panic!("UDP carrier"),
    };
    let consumer = super::tests::spawn_consume(&tunnel);
    let result = tokio::time::timeout(Duration::from_secs(2), async {
        let foreign = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let mut packet = [0; 2048];
        let mut reply = [0; 2048];
        let TunnResult::WriteToNetwork(initiation) = peer.format_handshake_initiation(&mut packet, false) else {
            panic!("initiation");
        };
        foreign.send_to(initiation, local).await.unwrap();
        let size = foreign.recv(&mut reply).await.unwrap();
        assert_eq!(size, 64);
        assert_eq!(&reply[..4], &[3, 0, 0, 0]);
        assert!(!tunnel.is_handshake_established());
        tunnel.initiate_handshake().await.unwrap();
        let size = original.recv(&mut packet).await.unwrap();
        assert_eq!(size, 148);
        assert_eq!(&packet[..4], &[1, 0, 0, 0]);
        let limiter = RateLimiter::new(&remote_public, 0);
        let Err(TunnResult::WriteToNetwork(cookie)) =
            limiter.verify_packet(Some(local.ip()), &packet[..size], &mut reply)
        else {
            panic!("valid provisional cookie reply");
        };
        foreign.send_to(cookie, local).await.unwrap();
        tokio::time::sleep(Duration::from_millis(20)).await;
        tunnel.initiate_handshake().await.unwrap();
        let size = original.recv(&mut packet).await.unwrap();
        assert_eq!(size, 148);
        assert_eq!(&packet[..4], &[1, 0, 0, 0]);
    })
    .await;
    consumer.abort();
    let _ = consumer.await;
    result.expect("source-bound provisional cookie response");
}

/// # Cancel safety:
/// NOT cancel-safe: this test stops both owned tunnel tasks after the bounded handshake.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn multiple_unanswered_ipv4_candidates_reach_ipv6_before_startup_deadline() {
    let (tunnel, original, mut peer) = tunnel_and_peer_with_config(WarpAmneziaConfig::default()).await;
    drop(original);
    let mut silent = Vec::new();
    for _ in 0..4 {
        silent.push(UdpSocket::bind("127.0.0.1:0").await.unwrap());
    }
    let live = UdpSocket::bind("[::1]:0").await.unwrap();
    let mut addresses: Vec<_> = silent.iter().map(|socket| socket.local_addr().unwrap()).collect();
    addresses.push(live.local_addr().unwrap());
    let mut addresses = crate::config::preferred_endpoint_candidates(addresses).unwrap();
    let primary = addresses.remove(0);
    *tunnel.outbound.lock().await = OutboundState::new(primary, addresses);
    let consumer = super::tests::spawn_consume(&tunnel);
    let ticking = Arc::clone(&tunnel);
    let routine = tokio::spawn(async move { ticking.routine_task().await });
    let result = tokio::time::timeout(Duration::from_secs(8), async {
        let mut input = [0; 2048];
        let mut output = [0; 2048];
        tunnel.initiate_handshake().await.unwrap();
        let size = silent[0].recv(&mut input).await.unwrap();
        assert_eq!(size, 148);
        let (size, client) = live.recv_from(&mut input).await.unwrap();
        assert_eq!(size, 148);
        let TunnResult::WriteToNetwork(response) = peer.decapsulate(None, &input[..size], &mut output) else {
            panic!("IPv6 fallback handshake response");
        };
        live.send_to(response, client).await.unwrap();
        let size = live.recv(&mut input).await.unwrap();
        assert!(matches!(peer.decapsulate(None, &input[..size], &mut output), TunnResult::Done));
        assert!(tunnel.is_handshake_established());
        tunnel.initiate_handshake().await.unwrap();
        let size = live.recv(&mut input).await.unwrap();
        assert_eq!(&input[..4], &[1, 0, 0, 0]);
        assert_eq!(size, 148);
        for address in &silent {
            assert!(tokio::time::timeout(Duration::from_millis(50), address.recv(&mut input)).await.is_err());
        }
    })
    .await;
    routine.abort();
    let _ = routine.await;
    consumer.abort();
    let _ = consumer.await;
    result.expect("IPv6 fallback before the eight-second test bound and the 15-second service deadline");
}

/// # Cancel safety:
/// NOT cancel-safe: terminal cancellation relies on test-runtime teardown. Normal completion
/// and timeout abort and join the owned receive task before checking the exchange result.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn initial_udp_send_error_falls_back_before_startup_failure() {
    let cases = [
        (WarpAmneziaConfig::default(), Vec::<usize>::new()),
        (WarpAmneziaConfig { enabled: true, jc: 2, jmin: 64, jmax: 64, ..Default::default() }, vec![64, 64]),
    ];
    for (config, expected_prelude) in cases {
        let (tunnel, primary, mut peer) = tunnel_and_peer_with_config(config).await;
        let live = UdpSocket::bind("[::1]:0").await.expect("owned IPv6 fallback");
        let socket = socket2::Socket::new(socket2::Domain::IPV6, socket2::Type::DGRAM, Some(socket2::Protocol::UDP))
            .expect("IPv6 carrier socket");
        socket.set_only_v6(false).expect("dual-stack socket");
        socket.bind(&"[::1]:0".parse::<SocketAddr>().unwrap().into()).expect("loopback-only source");
        socket.set_nonblocking(true).expect("nonblocking carrier");
        let socket = UdpSocket::from_std(socket.into()).expect("Tokio carrier");
        let SocketAddr::V4(primary_address) = primary.local_addr().unwrap() else {
            panic!("owned IPv4 primary");
        };
        let mapped_primary = SocketAddr::new(primary_address.ip().to_ipv6_mapped().into(), primary_address.port());
        assert!(
            socket.send_to(b"precondition", mapped_primary).await.is_err(),
            "an IPv6 loopback-bound socket must refuse the mapped IPv4 destination"
        );
        let Ok(mut tunnel) = Arc::try_unwrap(tunnel) else {
            panic!("unpublished tunnel must have one owner");
        };
        tunnel.carrier = WgCarrier::Udp { socket, dual_stack: true };
        *tunnel.outbound.get_mut() =
            OutboundState::new(primary.local_addr().unwrap(), vec![live.local_addr().unwrap()]);
        let tunnel = Arc::new(tunnel);
        let consumer = super::tests::spawn_consume(&tunnel);
        let result = tokio::time::timeout(Duration::from_secs(2), async {
            tunnel.initiate_handshake().await?;
            let mut input = [0; 2048];
            let mut output = [0; 2048];
            let mut prelude = Vec::new();
            for _ in &expected_prelude {
                prelude.push(live.recv(&mut input).await?);
            }
            let (size, client) = live.recv_from(&mut input).await?;
            let initiation_size = size;
            let TunnResult::WriteToNetwork(response) = peer.decapsulate(None, &input[..size], &mut output) else {
                return Err(io::Error::other("fallback peer did not authenticate the initiation"));
            };
            live.send_to(response, client).await?;
            let size = live.recv(&mut input).await?;
            if !matches!(peer.decapsulate(None, &input[..size], &mut output), TunnResult::Done) {
                return Err(io::Error::other("fallback peer did not authenticate confirmation"));
            }
            tunnel.send_ip_packet(&UDP_IP, &mut output).await;
            let size = live.recv(&mut input).await?;
            let TunnResult::WriteToTunnelV4(packet, _) = peer.decapsulate(None, &input[..size], &mut output) else {
                return Err(io::Error::other("fallback peer did not decrypt application traffic"));
            };
            Ok((prelude, initiation_size, packet.to_vec(), tunnel.is_handshake_established()))
        })
        .await;
        consumer.abort();
        let _ = consumer.await;
        let (prelude, initiation_size, payload, established) = result
            .expect("fallback handshake and traffic must finish within two seconds")
            .expect("a failed initial UDP send must try the next resolved endpoint");
        assert_eq!(prelude, expected_prelude, "the fallback receives the complete AWG prelude");
        assert_eq!(initiation_size, 148);
        assert!(established);
        assert_eq!(payload, UDP_IP);
    }
}
