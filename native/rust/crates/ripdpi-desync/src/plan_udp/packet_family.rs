use ripdpi_config::{DesyncGroup, QuicFakeProfile, UdpChainStep, UdpChainStepKind};
use ripdpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat, packetize_quic_initial, tamper_quic_version,
    udp_fake_profile_bytes, QuicInitialBrowserProfile, QuicInitialPacketLayout,
};

use crate::types::ActivationContext;

use super::adjusted_udp_burst_count;
use super::quic::{
    browser_like_quic_seed, effective_quic_realistic_host, packetize_browser_like_quic_initial,
    packetize_input_quic_initial, quic_browser_profile_for_index, NormalizedQuicPlannerInput, QUIC_INITIAL_MIN_PREFIX,
};

#[derive(Default)]
pub(super) struct UdpPreludeState {
    fake_burst_payload: Option<Vec<u8>>,
}

pub(super) fn build_udp_prelude_packets(
    group: &DesyncGroup,
    payload: &[u8],
    context: ActivationContext,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
    step: UdpChainStep,
    state: &mut UdpPreludeState,
) -> Vec<Vec<u8>> {
    match step.kind {
        UdpChainStepKind::FakeBurst => {
            let fake = state
                .fake_burst_payload
                .get_or_insert_with(|| udp_fake_payload(group, payload, context, normalized_quic))
                .clone();
            let burst_count = adjusted_udp_burst_count(step.count, context) as usize;
            vec![fake; burst_count]
        }
        UdpChainStepKind::DummyPrepend => build_dummy_prepend_packets(group, normalized_quic, step.count),
        UdpChainStepKind::QuicSniSplit => build_quic_sni_split_packets(normalized_quic, step.count),
        UdpChainStepKind::QuicFakeVersion => build_quic_fake_version_packets(group, normalized_quic, step.count),
        UdpChainStepKind::QuicCryptoSplit => build_quic_crypto_split_packets(normalized_quic, step.count),
        UdpChainStepKind::QuicPaddingLadder => build_quic_padding_ladder_packets(group, normalized_quic, step.count),
        UdpChainStepKind::QuicCidChurn => build_quic_cid_churn_packets(group, normalized_quic, step.count),
        UdpChainStepKind::QuicPacketNumberGap => {
            build_quic_packet_number_gap_packets(group, normalized_quic, step.count)
        }
        UdpChainStepKind::QuicVersionNegotiationDecoy => {
            build_quic_version_negotiation_decoy_packets(group, normalized_quic, step.count)
        }
        UdpChainStepKind::QuicMultiInitialRealistic => {
            build_quic_multi_initial_realistic_packets(group, normalized_quic, step.count)
        }
        _ => Vec::new(),
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
                _ => {}
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
