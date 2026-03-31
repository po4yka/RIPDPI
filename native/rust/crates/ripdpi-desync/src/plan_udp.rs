use crate::types::{activation_filter_matches, ActivationContext, AdaptiveUdpBurstProfile, DesyncAction};
use ring::rand::{SecureRandom, SystemRandom};
use ripdpi_config::{DesyncGroup, QuicFakeProfile, UdpChainStep, UdpChainStepKind};
use ripdpi_ipfrag::Ipv6ExtHeaders;
use ripdpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat, parse_quic_initial, tamper_quic_initial_split_sni,
    tamper_quic_version, udp_fake_profile_bytes, QuicInitialInfo,
};

fn ipv6_ext_from_udp_step(step: &UdpChainStep) -> Ipv6ExtHeaders {
    Ipv6ExtHeaders {
        hop_by_hop: step.ipv6_hop_by_hop,
        dest_opt: step.ipv6_dest_opt,
        dest_opt_fragmentable: step.ipv6_dest_opt2,
        routing: false,
        second_frag_next_override: step.ipv6_frag_next_override,
    }
}

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
        let parsed_quic = parse_quic_initial(payload);
        let mut fake_burst_payload = None;
        for step in chain {
            if !activation_filter_matches(step.activation_filter, context) {
                continue;
            }
            if step.kind != UdpChainStepKind::IpFrag2Udp && step.count <= 0 {
                continue;
            }
            let prelude_packets = match step.kind {
                UdpChainStepKind::FakeBurst => {
                    let fake = fake_burst_payload
                        .get_or_insert_with(|| udp_fake_payload(group, payload, context, parsed_quic.as_ref()))
                        .clone();
                    let burst_count = adjusted_udp_burst_count(step.count, context) as usize;
                    vec![fake; burst_count]
                }
                UdpChainStepKind::DummyPrepend => build_dummy_prepend_packets(step.count as usize),
                UdpChainStepKind::QuicSniSplit => {
                    build_quic_sni_split_packets(payload, parsed_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicFakeVersion => {
                    build_quic_fake_version_packets(group, payload, parsed_quic.as_ref(), step.count)
                }
                UdpChainStepKind::IpFrag2Udp => {
                    if context.round == 1 && parsed_quic.is_some() && step.split_bytes > 0 {
                        actions.push(DesyncAction::WriteIpFragmentedUdp {
                            bytes: payload.to_vec(),
                            split_offset: step.split_bytes as usize,
                            disorder: step.ip_frag_disorder,
                            ipv6_ext: ipv6_ext_from_udp_step(&step),
                        });
                        wrote_original = true;
                    }
                    Vec::new()
                }
            };
            if prelude_packets.is_empty() {
                continue;
            }
            actions.push(DesyncAction::SetTtl(group.actions.ttl.unwrap_or(8)));
            for packet in prelude_packets {
                actions.push(DesyncAction::Write(packet));
            }
            actions.push(DesyncAction::RestoreDefaultTtl);
            if default_ttl != 0 {
                actions.push(DesyncAction::SetTtl(default_ttl));
            }
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

fn build_dummy_prepend_packets(count: usize) -> Vec<Vec<u8>> {
    let rng = SystemRandom::new();
    (0..count)
        .map(|seed| {
            let mut packet = vec![0u8; 64];
            if rng.fill(&mut packet).is_err() {
                for (idx, byte) in packet.iter_mut().enumerate() {
                    *byte = (seed as u8).wrapping_add((idx as u8).wrapping_mul(31));
                }
            }
            packet[0] &= 0x7f;
            packet
        })
        .collect()
}

fn build_quic_sni_split_packets(payload: &[u8], parsed_quic: Option<&QuicInitialInfo>, count: i32) -> Vec<Vec<u8>> {
    let Some(parsed_quic) = parsed_quic else {
        return Vec::new();
    };
    let Some(packet) = tamper_quic_initial_split_sni(payload, parsed_quic.tls_info.host_start) else {
        return Vec::new();
    };
    vec![packet; count as usize]
}

fn build_quic_fake_version_packets(
    group: &DesyncGroup,
    payload: &[u8],
    parsed_quic: Option<&QuicInitialInfo>,
    count: i32,
) -> Vec<Vec<u8>> {
    if parsed_quic.is_none() {
        return Vec::new();
    }
    let Some(packet) = tamper_quic_version(payload, group.actions.quic_fake_version) else {
        return Vec::new();
    };
    vec![packet; count as usize]
}

fn udp_fake_payload(
    group: &DesyncGroup,
    _payload: &[u8],
    context: ActivationContext,
    parsed_quic: Option<&QuicInitialInfo>,
) -> Vec<u8> {
    let quic_fake_profile = context.adaptive.quic_fake_profile.unwrap_or(group.actions.quic_fake_profile);
    if quic_fake_profile != QuicFakeProfile::Disabled {
        if let Some(quic) = parsed_quic {
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
        }
    }
    fake
}
