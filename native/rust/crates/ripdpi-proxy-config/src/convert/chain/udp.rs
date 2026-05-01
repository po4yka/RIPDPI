use ripdpi_config::{UdpChainStep, UdpChainStepKind};

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
    for step in steps {
        if step.count < 0 {
            return Err(ProxyConfigError::InvalidConfig("udpChainSteps count must be non-negative".to_string()));
        }

        let kind = parse_udp_chain_step_kind(&step.kind)?;
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
