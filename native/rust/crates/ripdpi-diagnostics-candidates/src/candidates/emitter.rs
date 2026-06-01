use super::prelude::*;

pub(super) fn strategy_emitter_tier(tier: EmitterTier) -> StrategyEmitterTier {
    match tier {
        EmitterTier::NonRootProduction => StrategyEmitterTier::NonRootProduction,
        EmitterTier::RootedProduction => StrategyEmitterTier::RootedProduction,
        EmitterTier::LabDiagnosticsOnly => StrategyEmitterTier::LabDiagnosticsOnly,
        _ => StrategyEmitterTier::NonRootProduction,
    }
}

pub(super) fn max_emitter_tier(current: EmitterTier, next: EmitterTier) -> EmitterTier {
    if next > current { next } else { current }
}

pub(super) fn config_emitter_tier(config: &ProxyUiConfig) -> EmitterTier {
    let mut tier = EmitterTier::NonRootProduction;
    for step in &config.chains.tcp_steps {
        if let Some(kind) = tcp_step_kind(step) {
            tier = max_emitter_tier(tier, kind.emitter_tier());
        }
    }
    for rotation in config.chains.tcp_rotation.as_ref().into_iter().flat_map(|value| value.candidates.iter()) {
        for step in &rotation.tcp_steps {
            if let Some(kind) = tcp_step_kind(step) {
                tier = max_emitter_tier(tier, kind.emitter_tier());
            }
        }
    }
    for step in &config.chains.udp_steps {
        if let Some(kind) = udp_step_kind(step) {
            tier = max_emitter_tier(tier, kind.emitter_tier());
        }
    }
    tier
}

pub(super) fn tier_override(candidate_id: &str) -> Option<StrategyEmitterTier> {
    match candidate_id {
        "fake_synfin"
        | "fake_pshurg"
        | "fake_rst"
        | "ipfrag2_hopbyhop"
        | "ipfrag2_hopbyhop2"
        | "ipfrag2_destopt"
        | "ipfrag2_hopbyhop_destopt"
        | "quic_ipfrag2_hopbyhop"
        | "quic_ipfrag2_hopbyhop2"
        | "quic_ipfrag2_destopt"
        | "quic_ipfrag2_hopbyhop_destopt"
        | "tlsrec_fakedsplit_altorder1"
        | "tlsrec_fakedsplit_altorder2"
        | "circular_tlsrec_split"
        | "activation_window_split"
        | "activation_window_hostfake"
        | "adaptive_fake_ttl"
        | "library_fake_payloads" => Some(StrategyEmitterTier::LabDiagnosticsOnly),
        _ => None,
    }
}

pub(super) fn candidate_emitter_tier(candidate_id: &str, config: &ProxyUiConfig) -> StrategyEmitterTier {
    tier_override(candidate_id).unwrap_or_else(|| strategy_emitter_tier(config_emitter_tier(config)))
}

pub(super) fn candidate_exact_emitter_requires_root(candidate_id: &str) -> bool {
    matches!(candidate_id, "tlsrec_seqovl_midsld" | "tlsrec_seqovl_sniext" | "multi_disorder" | "fake_rst")
}

pub(super) fn candidate_approximate_fallback_family(candidate_id: &str, family: &str) -> Option<&'static str> {
    match candidate_id {
        "tlsrec_seqovl_midsld" | "tlsrec_seqovl_sniext" => Some("tlsrec_split"),
        _ if family == "hostfake" => None,
        _ => None,
    }
}

pub(super) fn tcp_step_kind(step: &ProxyUiTcpChainStep) -> Option<TcpChainStepKind> {
    match step.kind.as_str() {
        "split" => Some(TcpChainStepKind::Split),
        "syndata" => Some(TcpChainStepKind::SynData),
        "seqovl" => Some(TcpChainStepKind::SeqOverlap),
        "disorder" => Some(TcpChainStepKind::Disorder),
        "multidisorder" => Some(TcpChainStepKind::MultiDisorder),
        "fake" => Some(TcpChainStepKind::Fake),
        "fakedsplit" => Some(TcpChainStepKind::FakeSplit),
        "fakeddisorder" => Some(TcpChainStepKind::FakeDisorder),
        "hostfake" => Some(TcpChainStepKind::HostFake),
        "oob" => Some(TcpChainStepKind::Oob),
        "disoob" => Some(TcpChainStepKind::Disoob),
        "tlsrec" => Some(TcpChainStepKind::TlsRec),
        "tlsrandrec" => Some(TcpChainStepKind::TlsRandRec),
        "ipfrag2" => Some(TcpChainStepKind::IpFrag2),
        "fakerst" => Some(TcpChainStepKind::FakeRst),
        _ => None,
    }
}

pub(super) fn udp_step_kind(step: &ProxyUiUdpChainStep) -> Option<UdpChainStepKind> {
    parse_udp_chain_step_kind(&step.kind).ok().or(match step.kind.as_str() {
        "fake" => Some(UdpChainStepKind::FakeBurst),
        "dummy" => Some(UdpChainStepKind::DummyPrepend),
        "quicsnisplit" => Some(UdpChainStepKind::QuicSniSplit),
        "quicfake" => Some(UdpChainStepKind::QuicFakeVersion),
        "quiccryptosplit" => Some(UdpChainStepKind::QuicCryptoSplit),
        "quicpaddingladder" => Some(UdpChainStepKind::QuicPaddingLadder),
        "quiccidchurn" => Some(UdpChainStepKind::QuicCidChurn),
        "quicpacketnumbergap" => Some(UdpChainStepKind::QuicPacketNumberGap),
        "quicvn" => Some(UdpChainStepKind::QuicVersionNegotiationDecoy),
        "quicmultiinitial" => Some(UdpChainStepKind::QuicMultiInitialRealistic),
        "ipfrag2" => Some(UdpChainStepKind::IpFrag2Udp),
        _ => None,
    })
}
