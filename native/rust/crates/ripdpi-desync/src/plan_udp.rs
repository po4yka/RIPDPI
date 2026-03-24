use crate::types::{activation_filter_matches, ActivationContext, AdaptiveUdpBurstProfile, DesyncAction};
use ripdpi_config::{DesyncGroup, QuicFakeProfile, UdpChainStepKind};
use ripdpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat, parse_quic_initial, udp_fake_profile_bytes,
};

pub fn plan_udp(group: &DesyncGroup, payload: &[u8], default_ttl: u8, context: ActivationContext) -> Vec<DesyncAction> {
    if !activation_filter_matches(group.activation_filter(), context) {
        return vec![DesyncAction::Write(payload.to_vec())];
    }
    let mut actions = Vec::new();
    let chain = group.effective_udp_chain();
    if group.actions.drop_sack {
        actions.push(DesyncAction::AttachDropSack);
    }
    if !chain.is_empty() {
        let fake = udp_fake_payload(group, payload, context);
        for step in chain {
            if !activation_filter_matches(step.activation_filter, context) {
                continue;
            }
            if !matches!(step.kind, UdpChainStepKind::FakeBurst) || step.count <= 0 {
                continue;
            }
            let burst_count = adjusted_udp_burst_count(step.count, context);
            actions.push(DesyncAction::SetTtl(group.actions.ttl.unwrap_or(8)));
            for _ in 0..burst_count {
                actions.push(DesyncAction::Write(fake.clone()));
            }
            actions.push(DesyncAction::RestoreDefaultTtl);
            if default_ttl != 0 {
                actions.push(DesyncAction::SetTtl(default_ttl));
            }
        }
    }
    actions.push(DesyncAction::Write(payload.to_vec()));
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

fn udp_fake_payload(group: &DesyncGroup, payload: &[u8], context: ActivationContext) -> Vec<u8> {
    let quic_fake_profile = context.adaptive.quic_fake_profile.unwrap_or(group.actions.quic_fake_profile);
    if quic_fake_profile != QuicFakeProfile::Disabled {
        if let Some(quic) = parse_quic_initial(payload) {
            match quic_fake_profile {
                QuicFakeProfile::Disabled => {}
                QuicFakeProfile::CompatDefault => return default_fake_quic_compat(),
                QuicFakeProfile::RealisticInitial => {
                    if let Some(fake) =
                        build_realistic_quic_initial(quic.version, group.actions.quic_fake_host.as_deref())
                    {
                        return fake;
                    }
                }
            }
        }
    }

    let mut fake = group
        .actions
        .fake_data
        .clone()
        .unwrap_or_else(|| udp_fake_profile_bytes(group.actions.udp_fake_profile).to_vec());
    if let Some(offset) = group.actions.fake_offset {
        if let Some(pos) = offset.absolute_positive().filter(|pos| (*pos as usize) < fake.len()) {
            fake = fake[pos as usize..].to_vec();
        } else {
            fake.clear();
        }
    }
    fake
}
