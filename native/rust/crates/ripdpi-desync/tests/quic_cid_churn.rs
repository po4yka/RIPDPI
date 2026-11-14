use std::collections::HashSet;

use ripdpi_config::{DesyncGroup, UdpChainStep, UdpChainStepKind};
use ripdpi_desync::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, DesyncAction, plan_udp,
};
use ripdpi_packets::{QUIC_V1_VERSION, QUIC_V2_VERSION, build_realistic_quic_initial, parse_quic_initial};

#[test]
fn cid_churn_steps_share_one_distinct_id_series() {
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        let input = build_realistic_quic_initial(version, Some("real.example.test")).expect("input Initial");
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicCidChurn, 32); 2];
        let context = ActivationContext {
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
        let actions = plan_udp(&group, &input, 64, context);
        let packets = actions
            .iter()
            .filter_map(|action| match action {
                DesyncAction::Write(packet) => Some(packet),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(packets.len(), 65);
        assert_eq!(packets[64], &input);
        let decoys = &packets[..64];
        let ids = decoys.iter().map(|packet| packet[6..6 + usize::from(packet[5])].to_vec()).collect::<HashSet<_>>();
        assert_eq!(ids.len(), decoys.len(), "CID churn steps must not restart the ID series");
        for packet in decoys {
            let parsed = parse_quic_initial(packet).expect("Initial protection follows the emitted DCID");
            assert_eq!(parsed.version, version);
            assert_eq!(parsed.host(), b"www.wikipedia.org");
        }
        assert_eq!(actions, plan_udp(&group, &input, 64, context), "a new plan resets the series");
    }
}
