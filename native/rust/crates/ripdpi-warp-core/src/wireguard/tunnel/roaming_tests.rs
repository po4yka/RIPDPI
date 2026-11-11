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
