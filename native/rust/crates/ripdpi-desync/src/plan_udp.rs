mod fragmentation;
mod packet_family;
mod quic;
mod sequencing;

use crate::types::{ActivationContext, AdaptiveUdpBurstProfile, DesyncAction, activation_filter_matches};
use ripdpi_config::{ActivationFilter, DesyncGroup, MAX_DUMMY_PREPEND_COUNT, MAX_FAKE_BURST_COUNT, UdpChainStepKind};

use self::fragmentation::build_ip_fragmented_udp_action;
use self::packet_family::{UdpPreludeState, build_udp_prelude_packets};
use self::quic::{normalized_quic_plan_input, quic_initial_header_version};
use self::sequencing::append_ttl_wrapped_packets;

pub fn plan_udp(group: &DesyncGroup, payload: &[u8], default_ttl: u8, context: ActivationContext) -> Vec<DesyncAction> {
    if !activation_filter_matches(group.activation_filter(), context) {
        return vec![DesyncAction::Write(payload.to_vec())];
    }

    let mut actions = Vec::new();
    let mut wrote_original = false;
    let chain = group.effective_udp_chain();

    if group.actions.drop_sack {
        actions.push(DesyncAction::AttachDropSack);
    }

    if !chain.is_empty() {
        let normalized_quic = normalized_quic_plan_input(payload);
        let mut prelude_state = UdpPreludeState::default();
        let mut dummy_prepend_remaining = MAX_DUMMY_PREPEND_COUNT;

        for mut step in chain {
            if !activation_filter_matches(step.activation_filter, context) {
                continue;
            }
            if step.kind != UdpChainStepKind::IpFrag2Udp && step.count <= 0 {
                continue;
            }
            if step.kind == UdpChainStepKind::DummyPrepend {
                step.count = step.count.min(dummy_prepend_remaining);
                dummy_prepend_remaining -= step.count;
                if step.count == 0 {
                    continue;
                }
            }
            if step.kind == UdpChainStepKind::FakeBurst
                && !fake_burst_round_matches(group, step.activation_filter, payload, context)
            {
                continue;
            }

            if let Some(action) = build_ip_fragmented_udp_action(step, payload, context, normalized_quic.as_ref()) {
                actions.push(action);
                wrote_original = true;
                continue;
            }

            let prelude_packets =
                build_udp_prelude_packets(group, payload, context, normalized_quic.as_ref(), step, &mut prelude_state);
            append_ttl_wrapped_packets(&mut actions, group, default_ttl, prelude_packets);
        }
    }

    if !wrote_original {
        actions.push(DesyncAction::Write(payload.to_vec()));
    }
    if group.actions.drop_sack {
        actions.push(DesyncAction::DetachDropSack);
    }

    actions
}

/// Without an explicit `round` range, a fake burst covers the first datagram
/// and any QUIC Initial, so a long flow's later datagrams stay untouched.
fn fake_burst_round_matches(
    group: &DesyncGroup,
    step_filter: Option<ActivationFilter>,
    payload: &[u8],
    context: ActivationContext,
) -> bool {
    let has_round = |filter: Option<ActivationFilter>| filter.is_some_and(|filter| filter.round.is_some());
    context.round <= 1
        || has_round(group.activation_filter())
        || has_round(step_filter)
        || quic_initial_header_version(payload).is_some()
}

fn adjusted_udp_burst_count(base_count: i32, context: ActivationContext) -> i32 {
    let base_count = base_count.clamp(1, MAX_FAKE_BURST_COUNT);
    match context.adaptive.udp_burst_profile.unwrap_or(AdaptiveUdpBurstProfile::Balanced) {
        AdaptiveUdpBurstProfile::Balanced => base_count,
        AdaptiveUdpBurstProfile::Conservative => base_count.saturating_sub(1).max(1),
        AdaptiveUdpBurstProfile::Aggressive => base_count.saturating_add(1).min(16).max(base_count),
    }
}
