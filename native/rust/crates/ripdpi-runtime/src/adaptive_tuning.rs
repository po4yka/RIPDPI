use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use ciadpi_config::{DesyncGroup, OffsetBase, QuicFakeProfile, TcpChainStepKind, UdpChainStepKind};
use ciadpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ciadpi_packets::{is_tls_client_hello, parse_quic_initial};

const ADAPTIVE_RETRY_WINDOW_MS: u64 = 15_000;
const FNV_OFFSET: u64 = 0xcbf29ce484222325;
const FNV_PRIME: u64 = 0x100000001b3;

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
    network_scope_key: String,
    group_index: usize,
    flow_kind: AdaptiveFlowKind,
    target: AdaptivePlannerTarget,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ChoiceState<T> {
    candidates: Vec<T>,
    candidate_index: usize,
    pinned: Option<T>,
    cooldown_until_ms: Vec<u64>,
}

#[derive(Debug, Clone)]
struct AdaptivePlannerState {
    split_offset_base: Option<ChoiceState<OffsetBase>>,
    tls_record_offset_base: Option<ChoiceState<OffsetBase>>,
    tlsrandrec_profile: Option<ChoiceState<AdaptiveTlsRandRecProfile>>,
    udp_burst_profile: Option<ChoiceState<AdaptiveUdpBurstProfile>>,
    quic_fake_profile: Option<ChoiceState<QuicFakeProfile>>,
    dimension_order: Vec<usize>,
    dimension_cursor: usize,
}

#[derive(Debug, Default)]
pub struct AdaptivePlannerResolver {
    states: HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
}

impl AdaptivePlannerResolver {
    pub fn resolve_tcp_hints(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> AdaptivePlannerHints {
        let flow_kind = tcp_flow_kind(payload);
        let key = adaptive_key(network_scope_key, group_index, flow_kind, dest, host);
        let seed = adaptive_seed(&key);
        let state = self.states.entry(key).or_insert_with(|| AdaptivePlannerState::new(seed));
        state.sync_tcp_candidates(group, payload);
        state.current_hints()
    }

    pub fn resolve_udp_hints(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> AdaptivePlannerHints {
        let flow_kind = udp_flow_kind(payload);
        let key = adaptive_key(network_scope_key, group_index, flow_kind, dest, host);
        let seed = adaptive_seed(&key);
        let state = self.states.entry(key).or_insert_with(|| AdaptivePlannerState::new(seed));
        state.sync_udp_candidates(group, payload);
        state.current_hints()
    }

    pub fn note_tcp_success(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        if let Some(state) =
            self.states.get_mut(&adaptive_key(network_scope_key, group_index, tcp_flow_kind(payload), dest, host))
        {
            state.note_success();
        }
    }

    pub fn note_tcp_failure(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        if let Some(state) =
            self.states.get_mut(&adaptive_key(network_scope_key, group_index, tcp_flow_kind(payload), dest, host))
        {
            state.note_failure();
        }
    }

    pub fn note_udp_success(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        if let Some(state) =
            self.states.get_mut(&adaptive_key(network_scope_key, group_index, udp_flow_kind(payload), dest, host))
        {
            state.note_success();
        }
    }

    pub fn note_udp_failure(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        if let Some(state) =
            self.states.get_mut(&adaptive_key(network_scope_key, group_index, udp_flow_kind(payload), dest, host))
        {
            state.note_failure();
        }
    }
}

impl AdaptivePlannerState {
    fn new(seed: u64) -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            dimension_order: shuffled_dimensions(seed),
            dimension_cursor: 0,
        }
    }

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
        clear_cooldowns(&mut self.split_offset_base);
        clear_cooldowns(&mut self.tls_record_offset_base);
        clear_cooldowns(&mut self.tlsrandrec_profile);
        clear_cooldowns(&mut self.udp_burst_profile);
        clear_cooldowns(&mut self.quic_fake_profile);
    }

    fn note_failure(&mut self) {
        let now_ms = now_millis();
        for offset in 0..self.dimension_order.len() {
            let position = (self.dimension_cursor + offset) % self.dimension_order.len();
            let dimension = self.dimension_order[position];
            if self.advance_dimension(dimension, now_ms) {
                self.dimension_cursor = (position + 1) % self.dimension_order.len();
                break;
            }
        }
    }

    fn advance_dimension(&mut self, dimension: usize, now_ms: u64) -> bool {
        match dimension {
            0 => advance_choice(&mut self.split_offset_base, now_ms),
            1 => advance_choice(&mut self.tls_record_offset_base, now_ms),
            2 => advance_choice(&mut self.tlsrandrec_profile, now_ms),
            3 => advance_choice(&mut self.udp_burst_profile, now_ms),
            4 => advance_choice(&mut self.quic_fake_profile, now_ms),
            _ => false,
        }
    }
}

impl<T> ChoiceState<T>
where
    T: Copy + Eq,
{
    fn new(candidates: Vec<T>) -> Self {
        let cooldown_until_ms = vec![0; candidates.len()];
        Self { candidates, candidate_index: 0, pinned: None, cooldown_until_ms }
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

    fn clear_cooldowns(&mut self) {
        self.cooldown_until_ms.fill(0);
    }

    fn note_failure(&mut self, now_ms: u64) {
        self.pinned = None;
        if self.candidates.len() <= 1 {
            return;
        }
        if self.candidate_index < self.cooldown_until_ms.len() {
            self.cooldown_until_ms[self.candidate_index] = now_ms.saturating_add(ADAPTIVE_RETRY_WINDOW_MS);
        }
        for offset in 1..=self.candidates.len() {
            let candidate = (self.candidate_index + offset) % self.candidates.len();
            if self.cooldown_until_ms.get(candidate).copied().unwrap_or_default() <= now_ms {
                self.candidate_index = candidate;
                return;
            }
        }
        self.candidate_index = (self.candidate_index + 1) % self.candidates.len();
    }
}

fn adaptive_key(
    network_scope_key: Option<&str>,
    group_index: usize,
    flow_kind: AdaptiveFlowKind,
    dest: SocketAddr,
    host: Option<&str>,
) -> AdaptivePlannerKey {
    AdaptivePlannerKey {
        network_scope_key: network_scope_key
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .unwrap_or("default")
            .to_string(),
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
        Some(state) if state.candidates == candidates => {
            state.cooldown_until_ms.resize(state.candidates.len(), 0);
        }
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

fn clear_cooldowns<T>(slot: &mut Option<ChoiceState<T>>)
where
    T: Copy + Eq,
{
    if let Some(state) = slot.as_mut() {
        state.clear_cooldowns();
    }
}

fn advance_choice<T>(slot: &mut Option<ChoiceState<T>>, now_ms: u64) -> bool
where
    T: Copy + Eq,
{
    let Some(state) = slot.as_mut() else {
        return false;
    };
    if !state.is_adaptive() {
        return false;
    }
    state.note_failure(now_ms);
    true
}

fn adaptive_seed(key: &AdaptivePlannerKey) -> u64 {
    let mut hash = FNV_OFFSET;
    stable_hash_update(&mut hash, key.network_scope_key.as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, key.group_index.to_string().as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, format!("{:?}", key.flow_kind).as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, format!("{:?}", key.target).as_bytes());
    hash
}

fn shuffled_dimensions(seed: u64) -> Vec<usize> {
    let mut dimensions = vec![0usize, 1, 2, 3, 4];
    dimensions.sort_by_key(|dimension| stable_hash(seed, *dimension as u64));
    dimensions
}

fn stable_hash(seed: u64, value: u64) -> u64 {
    let mut hash = FNV_OFFSET;
    stable_hash_update(&mut hash, seed.to_string().as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, value.to_string().as_bytes());
    hash
}

fn stable_hash_update(hash: &mut u64, bytes: &[u8]) {
    for byte in bytes {
        *hash ^= u64::from(*byte);
        *hash = hash.wrapping_mul(FNV_PRIME);
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
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
        let first = resolver.resolve_tcp_hints(None, 0, target, Some("Video.Example.Test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));
        assert_eq!(first.tls_record_offset_base, Some(OffsetBase::SniExt));
        assert_eq!(first.tlsrandrec_profile, Some(AdaptiveTlsRandRecProfile::Balanced));

        resolver.note_tcp_failure(None, 0, target, Some("video.example.test"), payload);
        let second = resolver.resolve_tcp_hints(None, 0, target, Some("video.example.test"), &group, payload);
        let second_changes = [
            first.split_offset_base != second.split_offset_base,
            first.tls_record_offset_base != second.tls_record_offset_base,
            first.tlsrandrec_profile != second.tlsrandrec_profile,
        ];
        assert_eq!(second_changes.into_iter().filter(|changed| *changed).count(), 1);

        resolver.note_tcp_failure(None, 0, target, Some("video.example.test"), payload);
        let third = resolver.resolve_tcp_hints(None, 0, target, Some("video.example.test"), &group, payload);
        let third_changes = [
            second.split_offset_base != third.split_offset_base,
            second.tls_record_offset_base != third.tls_record_offset_base,
            second.tlsrandrec_profile != third.tlsrandrec_profile,
        ];
        assert_eq!(third_changes.into_iter().filter(|changed| *changed).count(), 1);
    }

    #[test]
    fn tcp_success_pins_current_candidate_until_next_failure() {
        let payload = b"GET / HTTP/1.1\r\nHost: docs.example.test\r\n\r\n";
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

        let mut resolver = AdaptivePlannerResolver::default();
        let target = addr(80);
        let first = resolver.resolve_tcp_hints(None, 0, target, Some("docs.example.test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

        resolver.note_tcp_failure(None, 0, target, Some("docs.example.test"), payload);
        let advanced = resolver.resolve_tcp_hints(None, 0, target, Some("docs.example.test"), &group, payload);
        assert_eq!(advanced.split_offset_base, Some(OffsetBase::MidSld));

        resolver.note_tcp_success(None, 0, target, Some("docs.example.test"), payload);
        resolver.note_tcp_failure(None, 0, target, Some("docs.example.test"), payload);
        let next = resolver.resolve_tcp_hints(None, 0, target, Some("docs.example.test"), &group, payload);
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
        let first = resolver.resolve_udp_hints(None, 0, target, Some("media.example.test"), &group, &payload);
        assert_eq!(first.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Balanced));
        assert_eq!(first.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));

        resolver.note_udp_failure(None, 0, target, Some("media.example.test"), &payload);
        let second = resolver.resolve_udp_hints(None, 0, target, Some("media.example.test"), &group, &payload);
        assert_eq!(second.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(second.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));

        resolver.note_udp_failure(None, 0, target, Some("media.example.test"), &payload);
        let third = resolver.resolve_udp_hints(None, 0, target, Some("media.example.test"), &group, &payload);
        assert_eq!(third.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Conservative));
        assert_eq!(third.quic_fake_profile, Some(QuicFakeProfile::CompatDefault));

        let isolated = resolver.resolve_udp_hints(None, 0, target, Some("other.example.test"), &group, &payload);
        assert_eq!(isolated.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Balanced));
        assert_eq!(isolated.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
    }

    #[test]
    fn tcp_feedback_is_scoped_by_network_scope_key() {
        let payload = b"GET / HTTP/1.1\r\nHost: video.example.test\r\n\r\n";
        let mut group = DesyncGroup::new(0);
        group.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

        let mut resolver = AdaptivePlannerResolver::default();
        let target = addr(443);

        let baseline =
            resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("video.example.test"), &group, payload);
        resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("video.example.test"), payload);
        let advanced =
            resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("video.example.test"), &group, payload);
        let isolated =
            resolver.resolve_tcp_hints(Some("scope-b"), 0, target, Some("video.example.test"), &group, payload);

        assert_ne!(baseline.split_offset_base, advanced.split_offset_base);
        assert_eq!(isolated.split_offset_base, baseline.split_offset_base);
    }

    #[test]
    fn adaptive_dimension_order_is_stable_within_same_scope() {
        let payload = b"GET / HTTP/1.1\r\nHost: docs.example.test\r\n\r\n";
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
        let target = addr(443);

        let mut first = AdaptivePlannerResolver::default();
        let base_first =
            first.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);
        first.note_tcp_failure(Some("scope-a"), 0, target, Some("docs.example.test"), payload);
        let next_first =
            first.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);

        let mut second = AdaptivePlannerResolver::default();
        let base_second =
            second.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);
        second.note_tcp_failure(Some("scope-a"), 0, target, Some("docs.example.test"), payload);
        let next_second =
            second.resolve_tcp_hints(Some("scope-a"), 0, target, Some("docs.example.test"), &group, payload);

        assert_eq!(base_first, base_second);
        assert_eq!(next_first, next_second);
    }
}
