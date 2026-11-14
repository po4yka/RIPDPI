use super::*;
use ripdpi_packets::{QUIC_V1_VERSION, parse_quic_initial_seed};
use std::collections::HashSet;

#[test]
fn multi_initial_connections_are_fresh_within_and_between_bursts() {
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        let payload = build_realistic_quic_initial(version, Some("private.example.test")).expect("input");
        let original = parse_quic_initial_seed(&payload).expect("input seed");
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicMultiInitialRealistic, 3)];
        let context = ActivationContext { transport: ActivationTransport::Udp, ..tcp_context(&payload) };
        let mut dcids = HashSet::new();
        let mut scids = HashSet::new();
        let mut tls_randoms = HashSet::new();
        for _ in 0..2 {
            let actions = plan_udp(&group, &payload, 64, context);
            let writes = actions
                .iter()
                .filter_map(|action| match action {
                    DesyncAction::Write(bytes) => Some(bytes),
                    _ => None,
                })
                .collect::<Vec<_>>();
            assert_eq!(writes.len(), 4);
            assert_eq!(*writes[3], payload);
            for packet in &writes[..3] {
                let seed = parse_quic_initial_seed(packet).expect("decoy seed");
                assert_eq!(seed.version, version);
                assert_ne!(seed.dcid, original.dcid);
                assert_ne!(seed.scid, original.scid);
                assert!(dcids.insert(seed.dcid));
                assert!(scids.insert(seed.scid));
                // TLS record header + handshake header + legacy_version.
                assert!(tls_randoms.insert(seed.client_hello[11..43].to_vec()));
                assert_eq!(seed.client_hello[43], 0);
                assert_eq!(parse_quic_initial(packet).expect("decoy authority").host(), b"www.wikipedia.org");
            }
        }
        assert_eq!(dcids.len(), 6);
        assert_eq!(scids.len(), 6);
        assert_eq!(tls_randoms.len(), 6);
    }
}
