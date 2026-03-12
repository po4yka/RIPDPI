use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};

use ciadpi_config::{DesyncGroup, OffsetBase, QuicFakeProfile, TcpChainStepKind, UdpChainStepKind};
use ciadpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ciadpi_packets::{is_tls_client_hello, parse_quic_initial};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum AdaptiveFlowKind {
    TcpTls,
    TcpOther,
    UdpQuic,
    UdpOther,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum AdaptivePlannerTarget {
    Host(String),
    Address(SocketAddr),
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct AdaptivePlannerKey {
    group_index: usize,
    flow_kind: AdaptiveFlowKind,
    target: AdaptivePlannerTarget,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ChoiceState<T> {
    candidates: Vec<T>,
    candidate_index: usize,
    pinned: Option<T>,
}

#[derive(Debug, Clone, Default)]
struct AdaptivePlannerState {
    split_offset_base: Option<ChoiceState<OffsetBase>>,
    tls_record_offset_base: Option<ChoiceState<OffsetBase>>,
    tlsrandrec_profile: Option<ChoiceState<AdaptiveTlsRandRecProfile>>,
    udp_burst_profile: Option<ChoiceState<AdaptiveUdpBurstProfile>>,
    quic_fake_profile: Option<ChoiceState<QuicFakeProfile>>,
    next_dimension: usize,
}

#[derive(Debug, Default)]
pub struct AdaptivePlannerResolver {
    states: HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
}

impl AdaptivePlannerResolver {
    pub fn resolve_tcp_hints(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> AdaptivePlannerHints {
        let flow_kind = tcp_flow_kind(payload);
        let state = self.states.entry(adaptive_key(group_index, flow_kind, dest, host)).or_default();
        state.sync_tcp_candidates(group, payload);
        state.current_hints()
    }

    pub fn resolve_udp_hints(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> AdaptivePlannerHints {
        let flow_kind = udp_flow_kind(payload);
        let state = self.states.entry(adaptive_key(group_index, flow_kind, dest, host)).or_default();
        state.sync_udp_candidates(group, payload);
        state.current_hints()
    }

    pub fn note_tcp_success(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, payload: &[u8]) {
        if let Some(state) = self.states.get_mut(&adaptive_key(group_index, tcp_flow_kind(payload), dest, host)) {
            state.note_success();
        }
    }

    pub fn note_tcp_failure(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, payload: &[u8]) {
        if let Some(state) = self.states.get_mut(&adaptive_key(group_index, tcp_flow_kind(payload), dest, host)) {
            state.note_failure();
        }
    }

    pub fn note_udp_success(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, payload: &[u8]) {
        if let Some(state) = self.states.get_mut(&adaptive_key(group_index, udp_flow_kind(payload), dest, host)) {
            state.note_success();
        }
    }

    pub fn note_udp_failure(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, payload: &[u8]) {
        if let Some(state) = self.states.get_mut(&adaptive_key(group_index, udp_flow_kind(payload), dest, host)) {
            state.note_failure();
        }
    }
}

impl AdaptivePlannerState {
    fn sync_tcp_candidates(&mut self, group: &DesyncGroup, payload: &[u8]) {
        let tls_payload = is_tls_client_hello(payload);
        sync_choice(&mut self.split_offset_base, split_offset_candidates(group, tls_payload));
        sync_choice(&mut self.tls_record_offset_base, tls_record_offset_candidates(group));
        sync_choice(&mut self.tlsrandrec_profile, tlsrandrec_profile_candidates(group));
        self.udp_burst_profile = None;
        self.quic_fake_profile = None;
    }

    fn sync_udp_candidates(&mut self, group: &DesyncGroup, payload: &[u8]) {
        sync_choice(&mut self.udp_burst_profile, udp_burst_profile_candidates(group));
        sync_choice(&mut self.quic_fake_profile, quic_fake_profile_candidates(group, payload));
        self.split_offset_base = None;
        self.tls_record_offset_base = None;
        self.tlsrandrec_profile = None;
    }

    fn current_hints(&self) -> AdaptivePlannerHints {
        AdaptivePlannerHints {
            split_offset_base: self.split_offset_base.as_ref().and_then(ChoiceState::current),
            tls_record_offset_base: self.tls_record_offset_base.as_ref().and_then(ChoiceState::current),
            tlsrandrec_profile: self.tlsrandrec_profile.as_ref().and_then(ChoiceState::current),
            udp_burst_profile: self.udp_burst_profile.as_ref().and_then(ChoiceState::current),
            quic_fake_profile: self.quic_fake_profile.as_ref().and_then(ChoiceState::current),
        }
    }

    fn note_success(&mut self) {
        pin_choice(&mut self.split_offset_base);
        pin_choice(&mut self.tls_record_offset_base);
        pin_choice(&mut self.tlsrandrec_profile);
        pin_choice(&mut self.udp_burst_profile);
        pin_choice(&mut self.quic_fake_profile);
    }

    fn note_failure(&mut self) {
        for offset in 0..5usize {
            let dimension = (self.next_dimension + offset) % 5;
            if self.advance_dimension(dimension) {
                self.next_dimension = (dimension + 1) % 5;
                break;
            }
        }
    }

    fn advance_dimension(&mut self, dimension: usize) -> bool {
        match dimension {
            0 => advance_choice(&mut self.split_offset_base),
            1 => advance_choice(&mut self.tls_record_offset_base),
            2 => advance_choice(&mut self.tlsrandrec_profile),
            3 => advance_choice(&mut self.udp_burst_profile),
            4 => advance_choice(&mut self.quic_fake_profile),
            _ => false,
        }
    }
}

impl<T> ChoiceState<T>
where
    T: Copy + Eq,
{
    fn new(candidates: Vec<T>) -> Self {
        Self { candidates, candidate_index: 0, pinned: None }
    }

    fn current(&self) -> Option<T> {
        self.pinned.or_else(|| self.candidates.get(self.candidate_index).copied())
    }

    fn is_adaptive(&self) -> bool {
        self.candidates.len() > 1
    }

    fn note_success(&mut self) {
        self.pinned = self.current();
    }

    fn note_failure(&mut self) {
        self.pinned = None;
        if self.candidates.is_empty() {
            return;
        }
        self.candidate_index = (self.candidate_index + 1) % self.candidates.len();
    }
}

fn adaptive_key(
    group_index: usize,
    flow_kind: AdaptiveFlowKind,
    dest: SocketAddr,
    host: Option<&str>,
) -> AdaptivePlannerKey {
    AdaptivePlannerKey {
        group_index,
        flow_kind,
        target: normalized_host(host).map(AdaptivePlannerTarget::Host).unwrap_or(AdaptivePlannerTarget::Address(dest)),
    }
}

fn tcp_flow_kind(payload: &[u8]) -> AdaptiveFlowKind {
    if is_tls_client_hello(payload) {
        AdaptiveFlowKind::TcpTls
    } else {
        AdaptiveFlowKind::TcpOther
    }
}

fn udp_flow_kind(payload: &[u8]) -> AdaptiveFlowKind {
    if parse_quic_initial(payload).is_some() {
        AdaptiveFlowKind::UdpQuic
    } else {
        AdaptiveFlowKind::UdpOther
    }
}

fn normalized_host(host: Option<&str>) -> Option<String> {
    let trimmed = host?.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    let normalized = trimmed.to_ascii_lowercase();
    if normalized.parse::<IpAddr>().is_ok() {
        return None;
    }
    Some(normalized)
}

fn sync_choice<T>(slot: &mut Option<ChoiceState<T>>, candidates: Vec<T>)
where
    T: Copy + Eq,
{
    if candidates.is_empty() {
        *slot = None;
        return;
    }
    match slot {
        Some(state) if state.candidates == candidates => {}
        Some(state) => *state = ChoiceState::new(candidates),
        None => *slot = Some(ChoiceState::new(candidates)),
    }
}

fn pin_choice<T>(slot: &mut Option<ChoiceState<T>>)
where
    T: Copy + Eq,
{
    if let Some(state) = slot.as_mut() {
        state.note_success();
    }
}

fn advance_choice<T>(slot: &mut Option<ChoiceState<T>>) -> bool
where
    T: Copy + Eq,
{
    let Some(state) = slot.as_mut() else {
        return false;
    };
    if !state.is_adaptive() {
        return false;
    }
    state.note_failure();
    true
}

fn split_offset_candidates(group: &DesyncGroup, tls_payload: bool) -> Vec<OffsetBase> {
    let mut candidates = Vec::new();
    for step in group.effective_tcp_chain() {
        if step.kind.is_tls_prelude() || !step.offset.base.is_adaptive() {
            continue;
        }
        extend_unique(&mut candidates, adaptive_candidates(step.offset.base, tls_payload));
    }
    candidates
}

fn tls_record_offset_candidates(group: &DesyncGroup) -> Vec<OffsetBase> {
    let mut candidates = Vec::new();
    for step in group.effective_tcp_chain() {
        if !matches!(step.kind, TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec)
            || !step.offset.base.is_adaptive()
        {
            continue;
        }
        extend_unique(&mut candidates, adaptive_candidates(step.offset.base, true));
    }
    candidates
}

fn tlsrandrec_profile_candidates(group: &DesyncGroup) -> Vec<AdaptiveTlsRandRecProfile> {
    if group.effective_tcp_chain().into_iter().any(|step| matches!(step.kind, TcpChainStepKind::TlsRandRec)) {
        vec![AdaptiveTlsRandRecProfile::Balanced, AdaptiveTlsRandRecProfile::Tight, AdaptiveTlsRandRecProfile::Wide]
    } else {
        Vec::new()
    }
}

fn udp_burst_profile_candidates(group: &DesyncGroup) -> Vec<AdaptiveUdpBurstProfile> {
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

fn quic_fake_profile_candidates(group: &DesyncGroup, payload: &[u8]) -> Vec<QuicFakeProfile> {
    if parse_quic_initial(payload).is_none() {
        return Vec::new();
    }
    match group.quic_fake_profile {
        QuicFakeProfile::Disabled => Vec::new(),
        QuicFakeProfile::CompatDefault => vec![QuicFakeProfile::CompatDefault, QuicFakeProfile::RealisticInitial],
        QuicFakeProfile::RealisticInitial => {
            vec![QuicFakeProfile::RealisticInitial, QuicFakeProfile::CompatDefault]
        }
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

#[cfg(test)]
mod tests {
    use super::*;
    use ciadpi_config::{OffsetExpr, TcpChainStep, UdpChainStep};
    use ciadpi_packets::{build_realistic_quic_initial, QUIC_V2_VERSION};

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::from(([127, 0, 0, 1], port))
    }

    #[test]
    fn tcp_failure_rotates_one_adaptive_dimension_at_a_time() {
        let payload = b"GET / HTTP/1.1\r\nHost: video.example.test\r\n\r\n";
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
            TcpChainStep {
                kind: TcpChainStepKind::TlsRandRec,
                offset: OffsetExpr::adaptive(OffsetBase::AutoSniExt),
                activation_filter: None,
                midhost_offset: None,
                fake_host_template: None,
                fragment_count: 3,
                min_fragment_size: 12,
                max_fragment_size: 64,
            },
        ];

        let mut resolver = AdaptivePlannerResolver::default();
        let target = addr(443);
        let first = resolver.resolve_tcp_hints(0, target, Some("Video.Example.Test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));
        assert_eq!(first.tls_record_offset_base, Some(OffsetBase::SniExt));
        assert_eq!(first.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Balanced));

        resolver.note_tcp_failure(0, target, Some("video.example.test"), payload);
        let second = resolver.resolve_tcp_hints(0, target, Some("video.example.test"), &group, payload);
        assert_eq!(second.split_offset_base, Some(OffsetBase::MidSld));
        assert_eq!(second.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Balanced));

        resolver.note_tcp_failure(0, target, Some("video.example.test"), payload);
        let third = resolver.resolve_tcp_hints(0, target, Some("video.example.test"), &group, payload);
        assert_eq!(third.split_offset_base, Some(OffsetBase::MidSld));
        assert_eq!(third.tls_record_offset_base, Some(OffsetBase::ExtLen));
        assert_eq!(third.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Balanced));
    }

    #[test]
    fn tcp_success_pins_current_candidate_until_next_failure() {
        let payload = b"GET / HTTP/1.1\r\nHost: docs.example.test\r\n\r\n";
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

        let mut resolver = AdaptivePlannerResolver::default();
        let target = addr(80);
        let first = resolver.resolve_tcp_hints(0, target, Some("docs.example.test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

        resolver.note_tcp_failure(0, target, Some("docs.example.test"), payload);
        let advanced = resolver.resolve_tcp_hints(0, target, Some("docs.example.test"), &group, payload);
        assert_eq!(advanced.split_offset_base, Some(OffsetBase::MidSld));

        resolver.note_tcp_success(0, target, Some("docs.example.test"), payload);
        resolver.note_tcp_failure(0, target, Some("docs.example.test"), payload);
        let next = resolver.resolve_tcp_hints(0, target, Some("docs.example.test"), &group, payload);
        assert_eq!(next.split_offset_base, Some(OffsetBase::EndHost));
    }

    #[test]
    fn udp_feedback_is_scoped_by_host_and_quic_profile() {
        let payload =
            build_realistic_quic_initial(QUIC_V2_VERSION, Some("media.example.test")).expect("quic initial payload");
        let mut group = DesyncGroup::new(0);
        group.quic_fake_profile = QuicFakeProfile::RealisticInitial;
        group.udp_chain = vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None }];

        let mut resolver = AdaptivePlannerResolver::default();
        let target = addr(443);
        let first = resolver.resolve_udp_hints(0, target, Some("media.example.test"), &group, &payload);
        assert_eq!(first.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Balanced));
        assert_eq!(first.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));

        resolver.note_udp_failure(0, target, Some("media.example.test"), &payload);
        let second = resolver.resolve_udp_hints(0, target, Some("media.example.test"), &group, &payload);
        assert_eq!(second.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(second.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));

        resolver.note_udp_failure(0, target, Some("media.example.test"), &payload);
        let third = resolver.resolve_udp_hints(0, target, Some("media.example.test"), &group, &payload);
        assert_eq!(third.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(third.quic_fake_profile, Some(QuicFakeProfile::CompatDefault));

        let isolated = resolver.resolve_udp_hints(0, target, Some("other.example.test"), &group, &payload);
        assert_eq!(isolated.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Balanced));
        assert_eq!(isolated.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
    }
}
