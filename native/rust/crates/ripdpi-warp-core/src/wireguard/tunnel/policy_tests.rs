use super::tests::{answer_initiation, tunnel_and_peer_with_config};
use super::*;
use smoltcp::wire::Ipv4Packet;

fn udp_packet(source: [u8; 4], destination: [u8; 4]) -> Vec<u8> {
    let mut packet = vec![0x45, 0, 0, 29, 0, 0, 0, 0, 64, 17, 0, 0];
    packet.extend_from_slice(&source);
    packet.extend_from_slice(&destination);
    packet.extend_from_slice(&[0xa0, 0x29, 0x03, 0xe8, 0, 9, 0, 0, b'x']);
    Ipv4Packet::new_unchecked(&mut packet[..]).fill_checksum();
    packet
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns encrypted peer state and joins the receiver after the exchange.
#[tokio::test]
async fn peer_policy_filters_authenticated_sources_and_outgoing_destinations() {
    let (mut tunnel, socket, mut peer) = tunnel_and_peer_with_config(WarpAmneziaConfig::default()).await;
    Arc::get_mut(&mut tunnel).unwrap().allowed_ips =
        Arc::new(AllowedIps::parse(Some(&["10.77.0.1/32".into()])).unwrap());
    let bus = Bus::new();
    let mut observer = bus.new_endpoint();
    let receiver = {
        let tunnel = Arc::clone(&tunnel);
        tokio::spawn(async move { tunnel.consume_task(bus).await })
    };
    let allowed_in = udp_packet([10, 77, 0, 1], [10, 77, 0, 2]);
    let allowed_out = udp_packet([10, 77, 0, 2], [10, 77, 0, 1]);
    let result = tokio::time::timeout(Duration::from_secs(2), async {
        tunnel.initiate_handshake().await.unwrap();
        answer_initiation(&socket, &mut peer, |_| {}).await;
        let mut wire = vec![0; MAX_PACKET];
        let mut plain = vec![0; MAX_PACKET];
        let (size, client) = socket.recv_from(&mut wire).await.unwrap();
        assert!(matches!(peer.decapsulate(None, &wire[..size], &mut plain), TunnResult::Done));
        for packet in [udp_packet([198, 51, 100, 1], [10, 77, 0, 2]), allowed_in.clone()] {
            let TunnResult::WriteToNetwork(encrypted) = peer.encapsulate(&packet, &mut wire) else {
                panic!("established peer must encrypt the IP packet");
            };
            socket.send_to(encrypted, client).await.unwrap();
        }
        let delivered = loop {
            if let Event::InboundInternetPacket(_, packet) = observer.recv().await {
                break packet;
            }
        };
        tunnel.send_ip_packet(&udp_packet([10, 77, 0, 2], [198, 51, 100, 1]), &mut wire).await;
        tunnel.send_ip_packet(&allowed_out, &mut wire).await;
        let (size, _) = socket.recv_from(&mut wire).await.unwrap();
        let TunnResult::WriteToTunnelV4(sent, _) = peer.decapsulate(None, &wire[..size], &mut plain) else {
            panic!("peer must receive the permitted IP packet");
        };
        (delivered, sent.to_vec())
    })
    .await;
    receiver.abort();
    let _ = receiver.await;
    let (delivered, sent) = result.expect("only permitted packets reach the bus and peer");
    assert_eq!(delivered.as_ref(), allowed_in.as_slice());
    assert_eq!(sent, allowed_out);
}
