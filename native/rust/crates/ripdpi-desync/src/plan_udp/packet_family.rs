mod churn_gap;
mod fake_burst;
mod realistic_initial;
mod split;
mod tamper_version;

use ripdpi_config::{DesyncGroup, UdpChainStep, UdpChainStepKind};

use crate::types::ActivationContext;

use self::churn_gap::{build_quic_cid_churn_packets, build_quic_packet_number_gap_packets};
use self::fake_burst::udp_fake_payload;
use self::realistic_initial::{build_quic_multi_initial_realistic_packets, build_quic_padding_ladder_packets};
use self::split::{build_dummy_prepend_packets, build_quic_crypto_split_packets, build_quic_sni_split_packets};
use self::tamper_version::{build_quic_fake_version_packets, build_quic_version_negotiation_decoy_packets};
use super::adjusted_udp_burst_count;
use super::quic::NormalizedQuicPlannerInput;

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
