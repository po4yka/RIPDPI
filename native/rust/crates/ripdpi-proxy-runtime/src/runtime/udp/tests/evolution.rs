use super::*;
use crate::runtime::udp::feedback::note_udp_first_response_success;
use crate::runtime::udp::upstream_pump::send_udp_flow_payload;
use ripdpi_proxy_runtime_adapter::desync_platform::EvolutionHintContext;
use ripdpi_proxy_runtime_adapter::model::config::{
    ActivationFilter, NumericRange, QuicFakeProfile, UdpChainStep, UdpChainStepKind,
};
use ripdpi_proxy_runtime_adapter::model::ports::AdaptiveHintPort;

const REQUEST: &[u8] = include_bytes!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin"
));
const RESPONSE: &[u8] = include_bytes!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin"
));

fn fixture(active_round: i64) -> (RuntimeState, RuntimeConfig, UdpFlowActivationState, UdpSocket) {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::FakeBurst, 1));
    group.actions.quic_fake_profile = QuicFakeProfile::Disabled;
    group.matches.activation_filter =
        Some(ActivationFilter { round: Some(NumericRange::new(active_round, active_round)), ..Default::default() });
    let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
    config.adaptive.strategy_evolution = true;
    config.adaptive.evolution_epsilon_permil = 0;
    let state = test_runtime_state(config.clone());
    let peer = UdpSocket::bind("127.0.0.1:0").unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let sender = UdpSocket::bind("127.0.0.1:0").unwrap();
    sender.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let target = peer.local_addr().unwrap();
    sender.connect(target).unwrap();
    (state, config, flow_entry_for(sender, target), peer)
}

fn held_hint(
    state: &RuntimeState,
    config: &RuntimeConfig,
    entry: &UdpFlowActivationState,
    payload: &[u8],
) -> Option<QuicFakeProfile> {
    state
        .test_services()
        .resolve_udp_hints_with_evolver(
            config,
            EvolutionHintContext { runtime_context: None, existing_attempt: entry.evolution_attempt },
            0,
            entry.current_target,
            entry.host.as_deref(),
            &config.groups[0],
            payload,
        )
        .unwrap()
        .hints
        .quic_fake_profile
}

fn accept_response(state: &RuntimeState, entry: &mut UdpFlowActivationState, peer: &UdpSocket) {
    peer.send_to(RESPONSE, entry.upstream.local_addr().unwrap()).unwrap();
    let mut bytes = [0; 4096];
    let n = entry.upstream.recv(&mut bytes).unwrap();
    assert_eq!(&bytes[..n], RESPONSE);
    assert!(entry.session.response_matches_outbound(&bytes[..n]));
    entry.answered = true;
    note_udp_first_response_success(state, entry).unwrap();
    entry.session.observe_upstream_response(&bytes[..n]);
}

#[test]
fn applied_udp_attempt_survives_plain_continuation() {
    let (state, config, mut entry, peer) = fixture(1);
    send_udp_flow_payload(&state, &mut entry, REQUEST, Instant::now(), None).unwrap();
    let mut bytes = [0; 4096];
    let fake = peer.recv(&mut bytes).unwrap();
    assert_ne!(&bytes[..fake], REQUEST);
    let real = peer.recv(&mut bytes).unwrap();
    assert_eq!(&bytes[..real], REQUEST);
    let held = entry.evolution_attempt.expect("applied QUIC attempt");
    assert_eq!(held_hint(&state, &config, &entry, REQUEST), Some(QuicFakeProfile::CompatDefault));
    send_udp_flow_payload(&state, &mut entry, b"continuation", Instant::now(), None).unwrap();
    let n = peer.recv(&mut bytes).unwrap();
    assert_eq!(&bytes[..n], b"continuation");
    assert_eq!(entry.evolution_attempt, Some(held));
    assert_eq!(held_hint(&state, &config, &entry, b"continuation"), Some(QuicFakeProfile::CompatDefault));
    accept_response(&state, &mut entry, &peer);
    assert_eq!(held_hint(&state, &config, &entry, b"continuation"), None);
}

#[test]
fn fresh_udp_skip_cannot_credit_a_response() {
    let (state, config, mut entry, peer) = fixture(2);
    send_udp_flow_payload(&state, &mut entry, REQUEST, Instant::now(), None).unwrap();
    let mut bytes = [0; 4096];
    let n = peer.recv(&mut bytes).unwrap();
    assert_eq!(&bytes[..n], REQUEST);
    assert!(entry.evolution_attempt.is_some());
    assert_eq!(held_hint(&state, &config, &entry, REQUEST), None);
    accept_response(&state, &mut entry, &peer);
    assert_eq!(held_hint(&state, &config, &entry, REQUEST), None);
}
