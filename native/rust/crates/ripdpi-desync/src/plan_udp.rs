mod fragmentation;
mod packet_family;
mod quic;
mod sequencing;

use crate::types::{activation_filter_matches, ActivationContext, AdaptiveUdpBurstProfile, DesyncAction};
use ripdpi_config::{DesyncGroup, UdpChainStepKind};

use self::fragmentation::build_ip_fragmented_udp_action;
use self::packet_family::{build_udp_prelude_packets, UdpPreludeState};
use self::quic::normalized_quic_plan_input;
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

        for step in chain {
            if !activation_filter_matches(step.activation_filter, context) {
                continue;
            }
            if step.kind != UdpChainStepKind::IpFrag2Udp && step.count <= 0 {
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

fn adjusted_udp_burst_count(base_count: i32, context: ActivationContext) -> i32 {
    let base_count = base_count.max(1);
    match context.adaptive.udp_burst_profile.unwrap_or(AdaptiveUdpBurstProfile::Balanced) {
        AdaptiveUdpBurstProfile::Balanced => base_count,
        AdaptiveUdpBurstProfile::Conservative => base_count.saturating_sub(1).max(1),
        AdaptiveUdpBurstProfile::Aggressive => base_count.saturating_add(1).min(16),
    }
}
