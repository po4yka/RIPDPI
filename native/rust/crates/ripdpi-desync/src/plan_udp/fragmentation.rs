use ripdpi_config::{UdpChainStep, UdpChainStepKind};
use ripdpi_ipfrag::Ipv6ExtHeaders;

use crate::types::{ActivationContext, DesyncAction};

use super::quic::NormalizedQuicPlannerInput;

pub(super) fn build_ip_fragmented_udp_action(
    step: UdpChainStep,
    payload: &[u8],
    context: ActivationContext,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
) -> Option<DesyncAction> {
    if step.kind != UdpChainStepKind::IpFrag2Udp {
        return None;
    }
    if context.round != 1 || normalized_quic.is_none() || step.split_bytes <= 0 {
        return None;
    }

    Some(DesyncAction::WriteIpFragmentedUdp {
        bytes: payload.to_vec(),
        split_offset: step.split_bytes as usize,
        disorder: step.ip_frag_disorder,
        ipv6_ext: ipv6_ext_from_udp_step(&step),
    })
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
