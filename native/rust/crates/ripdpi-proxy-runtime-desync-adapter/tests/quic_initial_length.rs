#![cfg(not(feature = "loom"))]

use std::net::UdpSocket;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, UdpChainStep, UdpChainStepKind};
use ripdpi_desync::{ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, plan_udp};
use ripdpi_packets::{QuicInitialPacketLayout, packetize_quic_initial, parse_quic_initial_seed};
use ripdpi_proxy_runtime_desync_adapter::udp_desync::{UdpActionExecContext, execute_udp_actions};

#[test]
fn maximum_ipv4_initial_sends_its_decoy_and_unchanged_original() {
    for source in [
        include_bytes!("../../ripdpi-packets/tests/fixtures/quic-initial/token-v1.bin").as_slice(),
        include_bytes!("../../ripdpi-packets/tests/fixtures/quic-initial/token-v2.bin").as_slice(),
    ] {
        let seed = parse_quic_initial_seed(source).expect("independent Initial seed");
        let input = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(65_507)).expect("large Initial");
        assert_eq!(input.len(), 65_507);
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicCidChurn, 1)];
        let activation = ActivationContext {
            round: 1,
            payload_size: input.len() as i64,
            stream_start: 0,
            stream_end: input.len() as i64 - 1,
            seqovl_supported: false,
            transport: ActivationTransport::Udp,
            tcp_segment_hint: None,
            tcp_state: ActivationTcpState::default(),
            resolved_fake_ttl: None,
            adaptive: AdaptivePlannerHints::default(),
        };
        let actions = plan_udp(&group, &input, 64, activation);
        let peer = UdpSocket::bind("127.0.0.1:0").expect("loopback receiver");
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        socket2::SockRef::from(&peer).set_recv_buffer_size(262_144).unwrap();
        let sender = UdpSocket::bind("127.0.0.1:0").expect("loopback sender");
        sender.connect(peer.local_addr().unwrap()).unwrap();
        socket2::SockRef::from(&sender).set_send_buffer_size(131_072).unwrap();
        let outcome = execute_udp_actions(
            UdpActionExecContext {
                upstream: &sender,
                target: peer.local_addr().unwrap(),
                default_ttl: 64,
                protect_path: None,
                ip_id_mode: None,
                socks_udp_frame: false,
            },
            &actions,
            &input,
        )
        .expect("send complete Initial plan");
        assert_eq!(outcome.technique_actions_completed, 1);
        assert_eq!(outcome.real_writes_committed, 1);
        assert_eq!(outcome.payload_bytes_committed, input.len());
        let mut received = vec![0; 65_535];
        let decoy_len = peer.recv(&mut received).expect("receive decoy");
        assert_eq!(decoy_len, 65_507);
        let original_len = peer.recv(&mut received).expect("receive original");
        assert_eq!(&received[..original_len], input);
    }
}
