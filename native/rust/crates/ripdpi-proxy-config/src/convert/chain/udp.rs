use ripdpi_config::{
    MAX_DUMMY_PREPEND_COUNT, MAX_FAKE_BURST_COUNT, MAX_QUIC_MULTI_INITIAL_COUNT, MAX_QUIC_PADDING_LADDER_COUNT,
    UdpChainStep, UdpChainStepKind,
};

use crate::types::{ProxyConfigError, ProxyUiUdpChainStep};

use super::activation::parse_proxy_activation_filter;
use super::ipv6::parse_ipv6_extension_profile;

pub fn parse_udp_chain_step_kind(value: &str) -> Result<UdpChainStepKind, ProxyConfigError> {
    match value {
        "fake_burst" => Ok(UdpChainStepKind::FakeBurst),
        "dummyprepend" | "dummy_prepend" => Ok(UdpChainStepKind::DummyPrepend),
        "quicsnisplit" | "quic_sni_split" => Ok(UdpChainStepKind::QuicSniSplit),
        "quicfakeversion" | "quic_fake_version" => Ok(UdpChainStepKind::QuicFakeVersion),
        "quiccryptosplit" | "quic_crypto_split" => Ok(UdpChainStepKind::QuicCryptoSplit),
        "quicpaddingladder" | "quic_padding_ladder" => Ok(UdpChainStepKind::QuicPaddingLadder),
        "quiccidchurn" | "quic_cid_churn" => Ok(UdpChainStepKind::QuicCidChurn),
        "quicpacketnumbergap" | "quic_packet_number_gap" => Ok(UdpChainStepKind::QuicPacketNumberGap),
        "quicversionnegotiationdecoy" | "quic_version_negotiation_decoy" => {
            Ok(UdpChainStepKind::QuicVersionNegotiationDecoy)
        }
        "quicmultiinitialrealistic" | "quic_multi_initial_realistic" => Ok(UdpChainStepKind::QuicMultiInitialRealistic),
        "ipfrag2_udp" => Ok(UdpChainStepKind::IpFrag2Udp),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown udpChainSteps kind: {value}"))),
    }
}

pub(crate) fn parse_proxy_udp_chain(steps: &[ProxyUiUdpChainStep]) -> Result<Vec<UdpChainStep>, ProxyConfigError> {
    let mut parsed = Vec::with_capacity(steps.len());
    let mut remaining_crypto_splits = MAX_FAKE_BURST_COUNT;
    let mut cid_churn_budget = MAX_FAKE_BURST_COUNT;
    for step in steps {
        if step.count < 0 {
            return Err(ProxyConfigError::InvalidConfig("udpChainSteps count must be non-negative".to_string()));
        }

        let kind = parse_udp_chain_step_kind(&step.kind)?;
        if kind == UdpChainStepKind::QuicCidChurn {
            if step.count > cid_churn_budget {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "udpChainSteps quic_cid_churn total count must be at most {MAX_FAKE_BURST_COUNT}"
                )));
            }
            cid_churn_budget -= step.count;
        }
        if matches!(kind, UdpChainStepKind::FakeBurst | UdpChainStepKind::QuicPacketNumberGap)
            && step.count > MAX_FAKE_BURST_COUNT
        {
            let name = if kind == UdpChainStepKind::FakeBurst { "fake_burst" } else { "quic_packet_number_gap" };
            return Err(ProxyConfigError::InvalidConfig(format!(
                "udpChainSteps kind={name} count must be at most {MAX_FAKE_BURST_COUNT}"
            )));
        }
        if kind == UdpChainStepKind::QuicCryptoSplit {
            if step.count > remaining_crypto_splits {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "udpChainSteps kind=quic_crypto_split combined count must be at most {MAX_FAKE_BURST_COUNT}"
                )));
            }
            remaining_crypto_splits -= step.count;
        }
        if kind == UdpChainStepKind::DummyPrepend && step.count > MAX_DUMMY_PREPEND_COUNT {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "udpChainSteps kind=dummy_prepend count must be at most {MAX_DUMMY_PREPEND_COUNT}"
            )));
        }
        if kind == UdpChainStepKind::QuicPaddingLadder && step.count > MAX_QUIC_PADDING_LADDER_COUNT {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "udpChainSteps kind=quic_padding_ladder count must be at most {MAX_QUIC_PADDING_LADDER_COUNT}"
            )));
        }
        if kind == UdpChainStepKind::QuicVersionNegotiationDecoy && step.count > MAX_FAKE_BURST_COUNT {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "udpChainSteps kind=quic_version_negotiation_decoy count must be at most {MAX_FAKE_BURST_COUNT}"
            )));
        }
        if kind == UdpChainStepKind::QuicMultiInitialRealistic && step.count > MAX_QUIC_MULTI_INITIAL_COUNT {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "udpChainSteps kind=quic_multi_initial_realistic count must be at most {MAX_QUIC_MULTI_INITIAL_COUNT}"
            )));
        }
        if kind == UdpChainStepKind::IpFrag2Udp {
            if step.count != 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "udpChainSteps kind=ipfrag2_udp must not declare count".to_string(),
                ));
            }
            if step.split_bytes <= 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "udpChainSteps kind=ipfrag2_udp must declare positive splitBytes".to_string(),
                ));
            }
        } else if step.split_bytes != 0 {
            return Err(ProxyConfigError::InvalidConfig(
                "udpChainSteps splitBytes is only supported for kind=ipfrag2_udp".to_string(),
            ));
        }

        let ipv6_ext = parse_ipv6_extension_profile(&step.ipv6_extension_profile)?;
        parsed.push(UdpChainStep {
            kind,
            count: step.count,
            split_bytes: step.split_bytes,
            activation_filter: parse_proxy_activation_filter(
                step.activation_filter.as_ref(),
                "chains.udpSteps.activationFilter",
                false,
            )?,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: ipv6_ext.hop_by_hop,
            ipv6_dest_opt: ipv6_ext.dest_opt,
            ipv6_dest_opt2: ipv6_ext.dest_opt2,
            ipv6_frag_next_override: None,
        });
    }
    Ok(parsed)
}

#[cfg(test)]
mod budget_tests;

#[cfg(test)]
mod multi_initial_tests {
    use super::*;

    #[test]
    fn multi_initial_count_is_bounded() {
        for (count, accepted) in [(0, true), (1, true), (2, true), (32, true), (33, false), (i32::MAX, false)] {
            let step = ProxyUiUdpChainStep {
                kind: "quic_multi_initial_realistic".to_string(),
                count,
                split_bytes: 0,
                activation_filter: None,
                ipv6_extension_profile: "none".to_string(),
            };
            assert_eq!(parse_proxy_udp_chain(&[step]).is_ok(), accepted, "count={count}");
        }
    }
}
