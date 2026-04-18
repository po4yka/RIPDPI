use crate::normalize_quic_initial;
use crate::types::{activation_filter_matches, ActivationContext, AdaptiveUdpBurstProfile, DesyncAction};
use ripdpi_config::{DesyncGroup, QuicFakeProfile, UdpChainStep, UdpChainStepKind};
use ripdpi_ipfrag::Ipv6ExtHeaders;
use ripdpi_packets::{
    build_browser_like_quic_initial_seed, build_realistic_quic_initial, default_fake_quic_compat,
    packetize_quic_initial, parse_quic_initial_seed, tamper_quic_version, udp_fake_profile_bytes,
    QuicInitialBrowserProfile, QuicInitialPacketLayout, QuicInitialSeed,
};

#[derive(Debug, Clone, PartialEq, Eq)]
struct NormalizedQuicPlannerInput {
    version: u32,
    authority_split_offset: usize,
    crypto_split_offset: usize,
    client_hello_len: usize,
    authority_host: String,
    datagram_len: usize,
    seed: QuicInitialSeed,
}

fn normalized_quic_plan_input(payload: &[u8]) -> Option<NormalizedQuicPlannerInput> {
    let ir = normalize_quic_initial(payload)?;
    let seed = parse_quic_initial_seed(payload)?;
    let client_hello_len = ir.tls_client_hello.raw.len();
    let crypto_split_offset = ir
        .desired
        .crypto_frame_boundaries
        .first()
        .copied()
        .filter(|offset| *offset > 0 && *offset < client_hello_len)
        .unwrap_or_else(|| (client_hello_len / 2).max(1));

    Some(NormalizedQuicPlannerInput {
        version: ir.version,
        authority_split_offset: ir.tls_client_hello.authority_span.start,
        crypto_split_offset,
        client_hello_len,
        authority_host: String::from_utf8(ir.tls_client_hello.authority).ok()?,
        datagram_len: payload.len(),
        seed,
    })
}

fn effective_quic_realistic_host<'a>(
    group: &'a DesyncGroup,
    normalized_quic: Option<&'a NormalizedQuicPlannerInput>,
) -> Option<&'a str> {
    group.actions.quic_fake_host.as_deref().or(normalized_quic.map(|quic| quic.authority_host.as_str()))
}

fn browser_like_quic_seed(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    profile: QuicInitialBrowserProfile,
) -> Option<QuicInitialSeed> {
    let normalized_quic = normalized_quic?;
    build_browser_like_quic_initial_seed(
        normalized_quic.version,
        effective_quic_realistic_host(group, Some(normalized_quic)),
        profile,
    )
}

fn packetize_input_quic_initial(
    normalized_quic: &NormalizedQuicPlannerInput,
    layout: QuicInitialPacketLayout,
) -> Option<Vec<u8>> {
    packetize_quic_initial(&normalized_quic.seed, &layout)
}

fn packetize_browser_like_quic_initial(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    profile: QuicInitialBrowserProfile,
    layout: QuicInitialPacketLayout,
) -> Option<Vec<u8>> {
    let seed = browser_like_quic_seed(group, normalized_quic, profile)?;
    packetize_quic_initial(&seed, &layout)
}

fn quic_browser_profile_for_index(idx: usize) -> QuicInitialBrowserProfile {
    if idx.is_multiple_of(2) {
        QuicInitialBrowserProfile::ChromeAndroid
    } else {
        QuicInitialBrowserProfile::FirefoxAndroid
    }
}

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
        let normalized_quic = normalized_quic_plan_input(payload);
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
                        .get_or_insert_with(|| udp_fake_payload(group, payload, context, normalized_quic.as_ref()))
                        .clone();
                    let burst_count = adjusted_udp_burst_count(step.count, context) as usize;
                    vec![fake; burst_count]
                }
                UdpChainStepKind::DummyPrepend => {
                    build_dummy_prepend_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicSniSplit => build_quic_sni_split_packets(normalized_quic.as_ref(), step.count),
                UdpChainStepKind::QuicFakeVersion => {
                    build_quic_fake_version_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicCryptoSplit => {
                    build_quic_crypto_split_packets(normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicPaddingLadder => {
                    build_quic_padding_ladder_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicCidChurn => {
                    build_quic_cid_churn_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicPacketNumberGap => {
                    build_quic_packet_number_gap_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicVersionNegotiationDecoy => {
                    build_quic_version_negotiation_decoy_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::QuicMultiInitialRealistic => {
                    build_quic_multi_initial_realistic_packets(group, normalized_quic.as_ref(), step.count)
                }
                UdpChainStepKind::IpFrag2Udp => {
                    if context.round == 1 && normalized_quic.is_some() && step.split_bytes > 0 {
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

fn build_dummy_prepend_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout = QuicInitialPacketLayout::contiguous(QUIC_INITIAL_MIN_PREFIX + (idx * 32));
            layout.extra_tail_padding = idx * 8;
            packetize_browser_like_quic_initial(
                group,
                Some(normalized_quic),
                quic_browser_profile_for_index(idx),
                layout,
            )
        })
        .collect()
}

const QUIC_INITIAL_MIN_PREFIX: usize = 256;

fn build_quic_sni_split_packets(normalized_quic: Option<&NormalizedQuicPlannerInput>, count: i32) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    let layout =
        QuicInitialPacketLayout::split_at(normalized_quic.authority_split_offset, normalized_quic.datagram_len);
    let Some(packet) = packetize_input_quic_initial(normalized_quic, layout) else {
        return Vec::new();
    };
    vec![packet; count as usize]
}

fn build_quic_fake_version_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let layout = QuicInitialPacketLayout::contiguous(normalized_quic.map_or(1200, |quic| quic.datagram_len.max(1200)));
    let Some(seed_packet) =
        packetize_browser_like_quic_initial(group, normalized_quic, QuicInitialBrowserProfile::ChromeAndroid, layout)
    else {
        return Vec::new();
    };
    let Some(packet) = tamper_quic_version(&seed_packet, group.actions.quic_fake_version) else {
        return Vec::new();
    };
    vec![packet; count as usize]
}

fn build_quic_crypto_split_packets(normalized_quic: Option<&NormalizedQuicPlannerInput>, count: i32) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    let split_at = normalized_quic.crypto_split_offset.min(normalized_quic.client_hello_len.saturating_sub(1));
    let layout = QuicInitialPacketLayout::split_at(split_at, normalized_quic.datagram_len);
    let Some(packet) = packetize_input_quic_initial(normalized_quic, layout) else {
        return Vec::new();
    };
    vec![packet; count.max(1) as usize]
}

fn build_quic_padding_ladder_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout =
                QuicInitialPacketLayout::contiguous(normalized_quic.map_or(1200, |quic| quic.datagram_len.max(1200)));
            layout.extra_tail_padding = 8 * (idx + 1);
            packetize_browser_like_quic_initial(
                group,
                normalized_quic,
                QuicInitialBrowserProfile::ChromeAndroid,
                layout,
            )
        })
        .collect()
}

fn build_quic_cid_churn_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut seed =
                browser_like_quic_seed(group, Some(normalized_quic), QuicInitialBrowserProfile::ChromeAndroid)?;
            if let Some(last) = seed.dcid.last_mut() {
                *last ^= (idx as u8).wrapping_add(normalized_quic.version as u8).max(1);
            }
            packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200)))
        })
        .collect()
}

fn build_quic_packet_number_gap_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    if normalized_quic.is_none() {
        return Vec::new();
    }
    (0..count.max(1) as usize)
        .filter_map(|idx| {
            let mut layout =
                QuicInitialPacketLayout::contiguous(normalized_quic.map_or(1200, |quic| quic.datagram_len.max(1200)));
            layout.packet_number = ((idx as u32) + 1) * 2;
            packetize_browser_like_quic_initial(
                group,
                normalized_quic,
                QuicInitialBrowserProfile::ChromeAndroid,
                layout,
            )
        })
        .collect()
}

fn build_quic_version_negotiation_decoy_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    let Some(seed_packet) = packetize_browser_like_quic_initial(
        group,
        Some(normalized_quic),
        QuicInitialBrowserProfile::ChromeAndroid,
        QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200)),
    ) else {
        return Vec::new();
    };
    let version = normalized_quic.version ^ 0x0f0f_0f0f;
    let Some(packet) = tamper_quic_version(&seed_packet, version) else {
        return Vec::new();
    };
    vec![packet; count.max(1) as usize]
}

fn build_quic_multi_initial_realistic_packets(
    group: &DesyncGroup,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    count: i32,
) -> Vec<Vec<u8>> {
    let Some(normalized_quic) = normalized_quic else {
        return Vec::new();
    };
    (0..count.max(2) as usize)
        .filter_map(|idx| {
            let mut layout = QuicInitialPacketLayout::contiguous(normalized_quic.datagram_len.max(1200));
            layout.extra_tail_padding = idx * 8;
            packetize_browser_like_quic_initial(
                group,
                Some(normalized_quic),
                quic_browser_profile_for_index(idx),
                layout,
            )
        })
        .collect()
}

fn udp_fake_payload(
    group: &DesyncGroup,
    _payload: &[u8],
    context: ActivationContext,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
) -> Vec<u8> {
    let quic_fake_profile = context.adaptive.quic_fake_profile.unwrap_or(group.actions.quic_fake_profile);
    if quic_fake_profile != QuicFakeProfile::Disabled {
        if let Some(quic) = normalized_quic {
            match quic_fake_profile {
                QuicFakeProfile::Disabled => {}
                QuicFakeProfile::CompatDefault => return default_fake_quic_compat(),
                QuicFakeProfile::RealisticInitial => {
                    if let Some(fake) = build_realistic_quic_initial(
                        quic.version,
                        effective_quic_realistic_host(group, normalized_quic),
                    ) {
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
