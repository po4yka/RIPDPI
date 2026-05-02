use ripdpi_config::{DesyncGroup, OffsetBase, QuicFakeProfile, TcpChainStepKind, UdpChainStepKind};
use ripdpi_desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_packets::is_quic_initial;

pub(super) fn split_offset_candidates(group: &DesyncGroup, tls_payload: bool) -> Vec<OffsetBase> {
    let mut candidates = Vec::new();
    for step in group.effective_tcp_chain() {
        if step.kind().is_tls_prelude() || !step.offset().base.is_adaptive() {
            continue;
        }
        extend_unique(&mut candidates, adaptive_candidates(step.offset().base, tls_payload));
    }
    candidates
}

pub(super) fn tls_record_offset_candidates(group: &DesyncGroup) -> Vec<OffsetBase> {
    let mut candidates = Vec::new();
    for step in group.effective_tcp_chain() {
        if !matches!(step.kind(), TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec)
            || !step.offset().base.is_adaptive()
        {
            continue;
        }
        extend_unique(&mut candidates, adaptive_candidates(step.offset().base, true));
    }
    candidates
}

pub(super) fn tlsrandrec_profile_candidates(group: &DesyncGroup) -> Vec<AdaptiveTlsRandRecProfile> {
    if group.effective_tcp_chain().into_iter().any(|step| matches!(step.kind(), TcpChainStepKind::TlsRandRec)) {
        vec![AdaptiveTlsRandRecProfile::Balanced, AdaptiveTlsRandRecProfile::Tight, AdaptiveTlsRandRecProfile::Wide]
    } else {
        Vec::new()
    }
}

pub(super) fn udp_burst_profile_candidates(group: &DesyncGroup) -> Vec<AdaptiveUdpBurstProfile> {
    if group
        .effective_udp_chain()
        .into_iter()
        .any(|step| matches!(step.kind, UdpChainStepKind::FakeBurst) && step.count > 0)
    {
        vec![
            AdaptiveUdpBurstProfile::Balanced,
            AdaptiveUdpBurstProfile::Conservative,
            AdaptiveUdpBurstProfile::Aggressive,
        ]
    } else {
        Vec::new()
    }
}

pub(super) fn quic_fake_profile_candidates(group: &DesyncGroup, payload: &[u8]) -> Vec<QuicFakeProfile> {
    if !is_quic_initial(payload) {
        return Vec::new();
    }
    match group.actions.quic_fake_profile {
        QuicFakeProfile::Disabled => Vec::new(),
        QuicFakeProfile::CompatDefault => vec![QuicFakeProfile::CompatDefault, QuicFakeProfile::RealisticInitial],
        QuicFakeProfile::RealisticInitial => {
            vec![QuicFakeProfile::RealisticInitial, QuicFakeProfile::CompatDefault]
        }
        _ => Vec::new(),
    }
}

fn adaptive_candidates(base: OffsetBase, tls_payload: bool) -> &'static [OffsetBase] {
    match base {
        OffsetBase::AutoBalanced if tls_payload => {
            &[OffsetBase::ExtLen, OffsetBase::SniExt, OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost]
        }
        OffsetBase::AutoBalanced => &[OffsetBase::Method, OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost],
        OffsetBase::AutoHost => &[OffsetBase::Host, OffsetBase::MidSld, OffsetBase::EndHost],
        OffsetBase::AutoMidSld => &[OffsetBase::MidSld, OffsetBase::Host, OffsetBase::EndHost],
        OffsetBase::AutoEndHost => &[OffsetBase::EndHost, OffsetBase::MidSld, OffsetBase::Host],
        OffsetBase::AutoMethod => &[OffsetBase::Method, OffsetBase::Host],
        OffsetBase::AutoSniExt => &[OffsetBase::SniExt, OffsetBase::ExtLen, OffsetBase::Host],
        OffsetBase::AutoExtLen => &[OffsetBase::ExtLen, OffsetBase::SniExt, OffsetBase::Host],
        _ => &[],
    }
}

fn extend_unique<T>(out: &mut Vec<T>, candidates: &[T])
where
    T: Copy + Eq,
{
    for &candidate in candidates {
        if !out.contains(&candidate) {
            out.push(candidate);
        }
    }
}
