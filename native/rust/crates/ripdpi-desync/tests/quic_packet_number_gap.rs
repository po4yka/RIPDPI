use std::collections::HashSet;

use ripdpi_config::{DesyncGroup, UdpChainStep, UdpChainStepKind};
use ripdpi_desync::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, DesyncAction, plan_udp,
};
use ripdpi_packets::{QuicInitialPacketLayout, QuicInitialSeed, packetize_quic_initial, parse_quic_initial_seed};

const INPUTS: [&[u8]; 2] = [
    include_bytes!("../../ripdpi-packets/tests/fixtures/quic-initial/token-v1.bin"),
    include_bytes!("../../ripdpi-packets/tests/fixtures/quic-initial/token-v2.bin"),
];

fn group(steps: usize) -> DesyncGroup {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep::new(UdpChainStepKind::QuicPacketNumberGap, 3); steps];
    group.actions.quic_fake_host = Some("decoy.example.test".to_owned());
    group
}

fn planned_datagrams(group: &DesyncGroup, input: &[u8], round: i64) -> Vec<Vec<u8>> {
    let context = ActivationContext {
        round,
        payload_size: input.len() as i64,
        stream_start: 0,
        stream_end: input.len().saturating_sub(1) as i64,
        seqovl_supported: false,
        transport: ActivationTransport::Udp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    };
    plan_udp(group, input, 64, context)
        .into_iter()
        .filter_map(|action| match action {
            DesyncAction::Write(bytes) => Some(bytes),
            _ => None,
        })
        .collect()
}

fn seeds(datagrams: &[Vec<u8>]) -> Vec<QuicInitialSeed> {
    datagrams.iter().map(|packet| parse_quic_initial_seed(packet).expect("decryptable Initial")).collect()
}

#[test]
fn gap_series_is_fresh_between_activations() {
    for input in INPUTS {
        let mut group = group(1);
        let first = planned_datagrams(&group, input, 1);
        let repeated = planned_datagrams(&group, input, 2);
        assert_eq!(first.len(), 4);
        assert_eq!(repeated.len(), 4);
        assert_eq!(first[3], input);
        assert_eq!(repeated[3], input);
        let first_seed = seeds(&first[..3]);
        let repeated_seed = seeds(&repeated[..3]);
        assert_eq!(first_seed[0].version, u32::from_be_bytes(input[1..5].try_into().expect("version")));
        assert_ne!(first_seed[0].dcid, repeated_seed[0].dcid, "repeated activation needs a fresh key context");
        for seed in &first_seed {
            assert_eq!(seed.dcid.len(), 16);
            assert_eq!(seed.scid.len(), 4);
            assert_eq!(seed, &first_seed[0], "one gap series keeps one Initial context and ClientHello");
            assert!(seed.token.is_empty(), "a synthetic context must not reuse the client's Retry token");
        }
        for (packet, packet_number) in first[..3].iter().zip([2, 4, 6]) {
            let mut layout = QuicInitialPacketLayout::contiguous(1200);
            layout.packet_number = packet_number;
            let expected = packetize_quic_initial(&first_seed[0], &layout).expect("expected gap packet");
            assert_eq!(packet, &expected, "gap numbering is preserved on the wire");
        }
        group.actions.quic_fake_host = Some("other.example.test".to_owned());
        let mut padded_input = input.to_vec();
        padded_input.resize(1408, 0);
        let changed = planned_datagrams(&group, &padded_input, 3);
        let changed_seed = seeds(&changed[..3]);
        assert_eq!(changed[3], padded_input);
        assert_ne!(first_seed[0].dcid, changed_seed[0].dcid);
        assert_ne!(first_seed[0].client_hello, changed_seed[0].client_hello);
    }
}

#[test]
fn gap_steps_have_independent_contexts() {
    for input in INPUTS {
        let datagrams = planned_datagrams(&group(2), input, 1);
        assert_eq!(datagrams.len(), 7);
        assert_eq!(datagrams[6], input);
        let seeds = seeds(&datagrams[..6]);
        assert_eq!(seeds[0], seeds[1]);
        assert_eq!(seeds[1], seeds[2]);
        assert_eq!(seeds[3], seeds[4]);
        assert_eq!(seeds[4], seeds[5]);
        assert_ne!(seeds[0].dcid, seeds[3].dcid, "two series must not share a packet-number key context");
    }
}

#[test]
fn parallel_gap_series_have_independent_contexts() {
    for input in INPUTS {
        let workers = (0..8)
            .map(|_| std::thread::spawn(move || seeds(&planned_datagrams(&group(1), input, 1)[..3])[0].dcid.clone()))
            .collect::<Vec<_>>();
        let contexts =
            workers.into_iter().map(|worker| worker.join().expect("gap planner worker")).collect::<HashSet<_>>();
        assert_eq!(contexts.len(), 8);
    }
}

fn tls_extension(hello: &[u8], kind: u16) -> &[u8] {
    let mut pos = 43;
    pos += 1 + usize::from(hello[pos]);
    pos += 2 + usize::from(u16::from_be_bytes([hello[pos], hello[pos + 1]]));
    pos += 1 + usize::from(hello[pos]);
    let extension_bytes = usize::from(u16::from_be_bytes([hello[pos], hello[pos + 1]]));
    pos += 2;
    let end = pos + extension_bytes;
    assert_eq!(end, hello.len(), "complete TLS extension list");
    while pos < end {
        let extension_kind = u16::from_be_bytes([hello[pos], hello[pos + 1]]);
        let len = usize::from(u16::from_be_bytes([hello[pos + 2], hello[pos + 3]]));
        pos += 4;
        let data = &hello[pos..pos + len];
        if extension_kind == kind {
            return data;
        }
        pos += len;
    }
    panic!("missing TLS extension {kind:#x}")
}

fn quic_varint(input: &mut &[u8]) -> u64 {
    let width = 1usize << (input[0] >> 6);
    let mut value = u64::from(input[0] & 0x3f);
    for byte in &input[1..width] {
        value = (value << 8) | u64::from(*byte);
    }
    *input = &input[width..];
    value
}

#[test]
fn gap_transport_parameters_match_header_source_connection_id() {
    for input in INPUTS {
        let datagrams = planned_datagrams(&group(2), input, 1);
        for seed in seeds(&datagrams[..6]) {
            let mut parameters = tls_extension(&seed.client_hello, 0x0039);
            let mut found = false;
            while !parameters.is_empty() {
                let id = quic_varint(&mut parameters);
                let len = usize::try_from(quic_varint(&mut parameters)).expect("parameter length");
                let (value, remaining) = parameters.split_at(len);
                parameters = remaining;
                if id == 0x0f {
                    assert!(!found, "initial_source_connection_id must not repeat");
                    assert_eq!(value, seed.scid, "header SCID must match its transport parameter");
                    found = true;
                }
            }
            assert!(found, "initial_source_connection_id is required");
            assert_eq!(tls_extension(&seed.client_hello, 0x0010), b"\x00\x03\x02h3");
        }
    }
}
