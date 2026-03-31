//! Per-flow adaptive tuning of DPI evasion parameters.
//!
//! [`AdaptivePlannerResolver`] tracks a separate [`AdaptivePlannerState`] for
//! each unique (network-scope, group, flow-kind, target) tuple. On failure it
//! cycles through candidates in one adaptive dimension at a time (round-robin
//! across a shuffled dimension order), and on success it pins the current
//! candidate so it persists until the next failure.
//!
//! # 5-dimension cycling
//!
//! The five tunable dimensions are:
//!
//! 0. `split_offset_base` -- TCP split point strategy
//! 1. `tls_record_offset_base` -- TLS record split point
//! 2. `tlsrandrec_profile` -- TLS random record fragmentation profile
//! 3. `udp_burst_profile` -- UDP fake-burst intensity
//! 4. `quic_fake_profile` -- QUIC fake packet style
//!
//! On each failure, only **one** dimension advances its candidate index. The
//! dimension order is deterministically shuffled per flow key so different
//! flows explore different paths.
//!
//! # Interaction with the strategy evolver
//!
//! When the session-level strategy evolver
//! ([`crate::strategy_evolver::StrategyEvolver`]) is enabled, its hints take
//! priority over the per-flow hints produced here. In that mode the evolver
//! provides a single [`AdaptivePlannerHints`] for all flows and the per-flow
//! dimension cycling in this module is effectively bypassed for any dimension
//! the evolver sets. See `strategy_evolver` module docs for the full priority
//! chain.
//!
//! # Persistence
//!
//! [`AdaptivePlannerResolver`] persists its per-flow state to
//! `adaptive-tuning-v1.json`. When the runtime has a host-autolearn store path,
//! the adaptive store is written next to it; otherwise the current working
//! directory is used as a CLI/native fallback. Persisted state is versioned and
//! invalidated whenever the configured group layout changes.

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use ring::digest;
use ripdpi_config::{DesyncGroup, OffsetBase, QuicFakeProfile, TcpChainStepKind, UdpChainStepKind};
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};
use ripdpi_packets::{is_quic_initial, is_tls_client_hello};
use serde::{Deserialize, Serialize};

const ADAPTIVE_RETRY_WINDOW_MS: u64 = 15_000;
const ADAPTIVE_TUNING_STORE_VERSION: u32 = 1;
const ADAPTIVE_TUNING_STORE_FILE_NAME: &str = "adaptive-tuning-v1.json";
const ADAPTIVE_TUNING_PERSIST_DEBOUNCE_MS: u64 = 2_000;
const DEFAULT_NETWORK_SCOPE_KEY: &str = "default";
const FNV_OFFSET: u64 = 0xcbf29ce484222325;
const FNV_PRIME: u64 = 0x100000001b3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum AdaptiveFlowKind {
    TcpTls,
    TcpOther,
    UdpQuic,
    UdpOther,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
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

#[derive(Debug, Clone, PartialEq, Eq)]
struct AdaptivePlannerState {
    split_offset_base: Option<ChoiceState<OffsetBase>>,
    tls_record_offset_base: Option<ChoiceState<OffsetBase>>,
    tlsrandrec_profile: Option<ChoiceState<AdaptiveTlsRandRecProfile>>,
    udp_burst_profile: Option<ChoiceState<AdaptiveUdpBurstProfile>>,
    quic_fake_profile: Option<ChoiceState<QuicFakeProfile>>,
    dimension_order: Vec<usize>,
    dimension_cursor: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum StoredOffsetBase {
    Abs,
    PayloadEnd,
    PayloadMid,
    PayloadRand,
    Host,
    EndHost,
    HostMid,
    HostRand,
    Sld,
    MidSld,
    EndSld,
    Method,
    ExtLen,
    EchExt,
    SniExt,
    AutoBalanced,
    AutoHost,
    AutoMidSld,
    AutoEndHost,
    AutoMethod,
    AutoSniExt,
    AutoExtLen,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum StoredAdaptiveTlsRandRecProfile {
    Balanced,
    Tight,
    Wide,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum StoredAdaptiveUdpBurstProfile {
    Balanced,
    Conservative,
    Aggressive,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum StoredQuicFakeProfile {
    Disabled,
    CompatDefault,
    RealisticInitial,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct StoredChoiceState<T> {
    candidates: Vec<T>,
    candidate_index: usize,
    pinned: Option<T>,
    #[serde(default)]
    cooldown_until_ms: Vec<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct StoredAdaptivePlannerState {
    #[serde(default)]
    split_offset_base: Option<StoredChoiceState<StoredOffsetBase>>,
    #[serde(default)]
    tls_record_offset_base: Option<StoredChoiceState<StoredOffsetBase>>,
    #[serde(default)]
    tlsrandrec_profile: Option<StoredChoiceState<StoredAdaptiveTlsRandRecProfile>>,
    #[serde(default)]
    udp_burst_profile: Option<StoredChoiceState<StoredAdaptiveUdpBurstProfile>>,
    #[serde(default)]
    quic_fake_profile: Option<StoredChoiceState<StoredQuicFakeProfile>>,
    #[serde(default)]
    dimension_order: Vec<usize>,
    #[serde(default)]
    dimension_cursor: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct StoredAdaptivePlannerEntry {
    group_index: usize,
    flow_kind: AdaptiveFlowKind,
    target: AdaptivePlannerTarget,
    state: StoredAdaptivePlannerState,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct StoredAdaptiveNetworkScope {
    #[serde(default)]
    entries: Vec<StoredAdaptivePlannerEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct StoredAdaptivePlannerStore {
    version: u32,
    fingerprint: String,
    #[serde(default)]
    scopes: BTreeMap<String, StoredAdaptiveNetworkScope>,
}

#[derive(Debug, Default)]
pub struct AdaptivePlannerResolver {
    states: HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
    last_persist_at_ms: u64,
    dirty: bool,
    persist_error_logged: bool,
}

impl AdaptivePlannerResolver {
    pub fn load(config: &ripdpi_config::RuntimeConfig) -> Self {
        let states = load_adaptive_store(config).unwrap_or_default();
        Self { states, last_persist_at_ms: 0, dirty: false, persist_error_logged: false }
    }

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
            self.dirty = true;
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
            self.dirty = true;
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
            self.dirty = true;
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
            self.dirty = true;
        }
    }

    pub fn persist_if_due(&mut self, config: &ripdpi_config::RuntimeConfig) {
        self.persist(config, false);
    }

    pub fn flush_store(&mut self, config: &ripdpi_config::RuntimeConfig) {
        self.persist(config, true);
    }

    fn persist(&mut self, config: &ripdpi_config::RuntimeConfig, force: bool) {
        if !self.dirty {
            return;
        }
        let now_ms = now_millis();
        if !force && now_ms.saturating_sub(self.last_persist_at_ms) < ADAPTIVE_TUNING_PERSIST_DEBOUNCE_MS {
            return;
        }
        match write_adaptive_store(config, &self.states) {
            Ok(()) => {
                self.last_persist_at_ms = now_ms;
                self.dirty = false;
                self.persist_error_logged = false;
            }
            Err(err) => {
                if !self.persist_error_logged {
                    tracing::warn!("adaptive tuning store write failed (non-fatal): {err}");
                    self.persist_error_logged = true;
                }
            }
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
            entropy_mode: None,
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

impl AdaptivePlannerState {
    fn to_persisted(&self) -> StoredAdaptivePlannerState {
        StoredAdaptivePlannerState {
            split_offset_base: self
                .split_offset_base
                .as_ref()
                .map(|choice| store_choice(choice, StoredOffsetBase::from)),
            tls_record_offset_base: self
                .tls_record_offset_base
                .as_ref()
                .map(|choice| store_choice(choice, StoredOffsetBase::from)),
            tlsrandrec_profile: self
                .tlsrandrec_profile
                .as_ref()
                .map(|choice| store_choice(choice, StoredAdaptiveTlsRandRecProfile::from)),
            udp_burst_profile: self
                .udp_burst_profile
                .as_ref()
                .map(|choice| store_choice(choice, StoredAdaptiveUdpBurstProfile::from)),
            quic_fake_profile: self
                .quic_fake_profile
                .as_ref()
                .map(|choice| store_choice(choice, StoredQuicFakeProfile::from)),
            dimension_order: self.dimension_order.clone(),
            dimension_cursor: self.dimension_cursor,
        }
    }

    fn from_persisted(state: StoredAdaptivePlannerState, seed: u64) -> Self {
        let dimension_order = if valid_dimension_order(&state.dimension_order) {
            state.dimension_order
        } else {
            shuffled_dimensions(seed)
        };
        let dimension_cursor = if state.dimension_cursor < dimension_order.len() { state.dimension_cursor } else { 0 };
        Self {
            split_offset_base: state.split_offset_base.and_then(|choice| load_choice(choice, restore_offset_base)),
            tls_record_offset_base: state
                .tls_record_offset_base
                .and_then(|choice| load_choice(choice, restore_offset_base)),
            tlsrandrec_profile: state
                .tlsrandrec_profile
                .and_then(|choice| load_choice(choice, restore_tlsrandrec_profile)),
            udp_burst_profile: state
                .udp_burst_profile
                .and_then(|choice| load_choice(choice, restore_udp_burst_profile)),
            quic_fake_profile: state
                .quic_fake_profile
                .and_then(|choice| load_choice(choice, restore_quic_fake_profile)),
            dimension_order,
            dimension_cursor,
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
        network_scope_key: normalize_scope_key(network_scope_key).to_string(),
        group_index,
        flow_kind,
        target: normalized_host(host).map_or(AdaptivePlannerTarget::Address(dest), AdaptivePlannerTarget::Host),
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
    if is_quic_initial(payload) {
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

fn store_choice<T, U>(choice: &ChoiceState<T>, map: impl Fn(T) -> U) -> StoredChoiceState<U>
where
    T: Copy + Eq,
{
    StoredChoiceState {
        candidates: choice.candidates.iter().copied().map(&map).collect(),
        candidate_index: choice.candidate_index,
        pinned: choice.pinned.map(&map),
        cooldown_until_ms: choice.cooldown_until_ms.clone(),
    }
}

fn load_choice<T, U>(choice: StoredChoiceState<U>, map: impl Fn(U) -> Option<T>) -> Option<ChoiceState<T>>
where
    T: Copy + Eq,
{
    let candidates = choice.candidates.into_iter().map(&map).collect::<Option<Vec<_>>>()?;
    if candidates.is_empty() {
        return None;
    }
    let mut cooldown_until_ms = choice.cooldown_until_ms;
    cooldown_until_ms.resize(candidates.len(), 0);
    cooldown_until_ms.truncate(candidates.len());
    Some(ChoiceState {
        candidates,
        candidate_index: if choice.candidate_index < cooldown_until_ms.len() { choice.candidate_index } else { 0 },
        pinned: match choice.pinned {
            Some(value) => Some(map(value)?),
            None => None,
        },
        cooldown_until_ms,
    })
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

fn adaptive_store_path(config: &ripdpi_config::RuntimeConfig) -> Option<PathBuf> {
    let store_path = config.host_autolearn.store_path.as_deref().map(str::trim).filter(|value| !value.is_empty())?;
    Path::new(store_path).parent().map(|parent| parent.join(ADAPTIVE_TUNING_STORE_FILE_NAME))
}

fn adaptive_store_fingerprint(config: &ripdpi_config::RuntimeConfig) -> String {
    let mut input = format!("adaptive-tuning-v1|{}", config.groups.len());
    input.push('|');
    input.push_str(&format!("{:?}", config.groups));
    let digest = digest::digest(&digest::SHA256, input.as_bytes());
    digest.as_ref().iter().fold(String::new(), |mut out, byte| {
        use std::fmt::Write;
        let _ = write!(out, "{byte:02x}");
        out
    })
}

fn valid_dimension_order(order: &[usize]) -> bool {
    if order.len() != 5 {
        return false;
    }
    let mut sorted = order.to_vec();
    sorted.sort_unstable();
    sorted == [0, 1, 2, 3, 4]
}

fn load_adaptive_store(
    config: &ripdpi_config::RuntimeConfig,
) -> Result<HashMap<AdaptivePlannerKey, AdaptivePlannerState>, io::Error> {
    let Some(path) = adaptive_store_path(config) else {
        return Ok(HashMap::new());
    };
    if !path.exists() {
        return Ok(HashMap::new());
    }
    let payload = fs::read(&path)?;
    let store = serde_json::from_slice::<StoredAdaptivePlannerStore>(&payload)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, format!("invalid adaptive tuning store: {err}")))?;
    if store.version != ADAPTIVE_TUNING_STORE_VERSION || store.fingerprint != adaptive_store_fingerprint(config) {
        return Ok(HashMap::new());
    }
    let mut states = HashMap::new();
    for (network_scope_key, scope) in store.scopes {
        let scope_key = normalize_scope_key(Some(&network_scope_key)).to_string();
        for entry in scope.entries {
            if entry.group_index >= config.groups.len() {
                continue;
            }
            let key = AdaptivePlannerKey {
                network_scope_key: scope_key.clone(),
                group_index: entry.group_index,
                flow_kind: entry.flow_kind,
                target: entry.target,
            };
            let seed = adaptive_seed(&key);
            states.insert(key, AdaptivePlannerState::from_persisted(entry.state, seed));
        }
    }
    Ok(states)
}

fn write_adaptive_store(
    config: &ripdpi_config::RuntimeConfig,
    states: &HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
) -> io::Result<()> {
    let Some(path) = adaptive_store_path(config) else {
        return Ok(());
    };
    let mut scopes: BTreeMap<String, StoredAdaptiveNetworkScope> = BTreeMap::new();
    for (key, state) in states {
        scopes.entry(key.network_scope_key.clone()).or_default().entries.push(StoredAdaptivePlannerEntry {
            group_index: key.group_index,
            flow_kind: key.flow_kind,
            target: key.target.clone(),
            state: state.to_persisted(),
        });
    }
    for scope in scopes.values_mut() {
        scope.entries.sort_by_key(|entry| format!("{}|{:?}|{:?}", entry.group_index, entry.flow_kind, entry.target));
    }
    let store = StoredAdaptivePlannerStore {
        version: ADAPTIVE_TUNING_STORE_VERSION,
        fingerprint: adaptive_store_fingerprint(config),
        scopes,
    };
    let payload = serde_json::to_vec_pretty(&store)
        .map_err(|err| io::Error::other(format!("failed to serialize adaptive tuning store: {err}")))?;
    atomic_write(&path, &payload)
}

fn atomic_write(path: &Path, payload: &[u8]) -> io::Result<()> {
    let Some(parent) = path.parent() else {
        return fs::write(path, payload);
    };
    if parent.as_os_str().is_empty() {
        return fs::write(path, payload);
    }
    fs::create_dir_all(parent)?;
    let tmp_name = format!(
        ".{}.tmp-{}-{}",
        path.file_name().and_then(|value| value.to_str()).unwrap_or("adaptive-tuning"),
        std::process::id(),
        next_temp_file_nonce()
    );
    let tmp_path = parent.join(tmp_name);
    fs::write(&tmp_path, payload)?;
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    fs::rename(tmp_path, path)
}

fn next_temp_file_nonce() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEMP_FILE_NONCE: AtomicU64 = AtomicU64::new(0);
    let timestamp = now_millis() << 16;
    let sequence = TEMP_FILE_NONCE.fetch_add(1, Ordering::Relaxed) & 0xFFFF;
    timestamp | sequence
}

fn normalize_scope_key(network_scope_key: Option<&str>) -> &str {
    network_scope_key.map(str::trim).filter(|value| !value.is_empty()).unwrap_or(DEFAULT_NETWORK_SCOPE_KEY)
}

fn restore_offset_base(base: StoredOffsetBase) -> Option<OffsetBase> {
    Some(match base {
        StoredOffsetBase::Abs => OffsetBase::Abs,
        StoredOffsetBase::PayloadEnd => OffsetBase::PayloadEnd,
        StoredOffsetBase::PayloadMid => OffsetBase::PayloadMid,
        StoredOffsetBase::PayloadRand => OffsetBase::PayloadRand,
        StoredOffsetBase::Host => OffsetBase::Host,
        StoredOffsetBase::EndHost => OffsetBase::EndHost,
        StoredOffsetBase::HostMid => OffsetBase::HostMid,
        StoredOffsetBase::HostRand => OffsetBase::HostRand,
        StoredOffsetBase::Sld => OffsetBase::Sld,
        StoredOffsetBase::MidSld => OffsetBase::MidSld,
        StoredOffsetBase::EndSld => OffsetBase::EndSld,
        StoredOffsetBase::Method => OffsetBase::Method,
        StoredOffsetBase::ExtLen => OffsetBase::ExtLen,
        StoredOffsetBase::EchExt => OffsetBase::EchExt,
        StoredOffsetBase::SniExt => OffsetBase::SniExt,
        StoredOffsetBase::AutoBalanced => OffsetBase::AutoBalanced,
        StoredOffsetBase::AutoHost => OffsetBase::AutoHost,
        StoredOffsetBase::AutoMidSld => OffsetBase::AutoMidSld,
        StoredOffsetBase::AutoEndHost => OffsetBase::AutoEndHost,
        StoredOffsetBase::AutoMethod => OffsetBase::AutoMethod,
        StoredOffsetBase::AutoSniExt => OffsetBase::AutoSniExt,
        StoredOffsetBase::AutoExtLen => OffsetBase::AutoExtLen,
    })
}

fn restore_tlsrandrec_profile(profile: StoredAdaptiveTlsRandRecProfile) -> Option<AdaptiveTlsRandRecProfile> {
    Some(match profile {
        StoredAdaptiveTlsRandRecProfile::Balanced => AdaptiveTlsRandRecProfile::Balanced,
        StoredAdaptiveTlsRandRecProfile::Tight => AdaptiveTlsRandRecProfile::Tight,
        StoredAdaptiveTlsRandRecProfile::Wide => AdaptiveTlsRandRecProfile::Wide,
    })
}

fn restore_udp_burst_profile(profile: StoredAdaptiveUdpBurstProfile) -> Option<AdaptiveUdpBurstProfile> {
    Some(match profile {
        StoredAdaptiveUdpBurstProfile::Balanced => AdaptiveUdpBurstProfile::Balanced,
        StoredAdaptiveUdpBurstProfile::Conservative => AdaptiveUdpBurstProfile::Conservative,
        StoredAdaptiveUdpBurstProfile::Aggressive => AdaptiveUdpBurstProfile::Aggressive,
    })
}

fn restore_quic_fake_profile(profile: StoredQuicFakeProfile) -> Option<QuicFakeProfile> {
    Some(match profile {
        StoredQuicFakeProfile::Disabled => QuicFakeProfile::Disabled,
        StoredQuicFakeProfile::CompatDefault => QuicFakeProfile::CompatDefault,
        StoredQuicFakeProfile::RealisticInitial => QuicFakeProfile::RealisticInitial,
    })
}

impl From<OffsetBase> for StoredOffsetBase {
    fn from(base: OffsetBase) -> Self {
        match base {
            OffsetBase::Abs => Self::Abs,
            OffsetBase::PayloadEnd => Self::PayloadEnd,
            OffsetBase::PayloadMid => Self::PayloadMid,
            OffsetBase::PayloadRand => Self::PayloadRand,
            OffsetBase::Host => Self::Host,
            OffsetBase::EndHost => Self::EndHost,
            OffsetBase::HostMid => Self::HostMid,
            OffsetBase::HostRand => Self::HostRand,
            OffsetBase::Sld => Self::Sld,
            OffsetBase::MidSld => Self::MidSld,
            OffsetBase::EndSld => Self::EndSld,
            OffsetBase::Method => Self::Method,
            OffsetBase::ExtLen => Self::ExtLen,
            OffsetBase::EchExt => Self::EchExt,
            OffsetBase::SniExt => Self::SniExt,
            OffsetBase::AutoBalanced => Self::AutoBalanced,
            OffsetBase::AutoHost => Self::AutoHost,
            OffsetBase::AutoMidSld => Self::AutoMidSld,
            OffsetBase::AutoEndHost => Self::AutoEndHost,
            OffsetBase::AutoMethod => Self::AutoMethod,
            OffsetBase::AutoSniExt => Self::AutoSniExt,
            OffsetBase::AutoExtLen => Self::AutoExtLen,
        }
    }
}

impl From<AdaptiveTlsRandRecProfile> for StoredAdaptiveTlsRandRecProfile {
    fn from(profile: AdaptiveTlsRandRecProfile) -> Self {
        match profile {
            AdaptiveTlsRandRecProfile::Balanced => Self::Balanced,
            AdaptiveTlsRandRecProfile::Tight => Self::Tight,
            AdaptiveTlsRandRecProfile::Wide => Self::Wide,
        }
    }
}

impl From<AdaptiveUdpBurstProfile> for StoredAdaptiveUdpBurstProfile {
    fn from(profile: AdaptiveUdpBurstProfile) -> Self {
        match profile {
            AdaptiveUdpBurstProfile::Balanced => Self::Balanced,
            AdaptiveUdpBurstProfile::Conservative => Self::Conservative,
            AdaptiveUdpBurstProfile::Aggressive => Self::Aggressive,
        }
    }
}

impl From<QuicFakeProfile> for StoredQuicFakeProfile {
    fn from(profile: QuicFakeProfile) -> Self {
        match profile {
            QuicFakeProfile::Disabled => Self::Disabled,
            QuicFakeProfile::CompatDefault => Self::CompatDefault,
            QuicFakeProfile::RealisticInitial => Self::RealisticInitial,
        }
    }
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
    if !is_quic_initial(payload) {
        return Vec::new();
    }
    match group.actions.quic_fake_profile {
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
    use std::{env, fs, path::PathBuf};

    use ripdpi_config::{OffsetExpr, TcpChainStep, UdpChainStep};
    use ripdpi_packets::{build_realistic_quic_initial, QUIC_V2_VERSION};

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::from(([127, 0, 0, 1], port))
    }

    fn config_with_adaptive_store(groups: Vec<DesyncGroup>) -> (ripdpi_config::RuntimeConfig, tempfile::TempDir) {
        let tmp_dir = tempfile::tempdir().expect("create temp dir for adaptive store test");
        let mut config = ripdpi_config::RuntimeConfig { groups, ..ripdpi_config::RuntimeConfig::default() };
        config.host_autolearn.store_path =
            Some(tmp_dir.path().join("host-autolearn.json").to_string_lossy().into_owned());
        (config, tmp_dir)
    }

    #[test]
    fn tcp_failure_rotates_one_adaptive_dimension_at_a_time() {
        let payload = b"GET / HTTP/1.1\r\nHost: video.example.test\r\n\r\n";
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
            TcpChainStep {
                kind: TcpChainStepKind::TlsRandRec,
                offset: OffsetExpr::adaptive(OffsetBase::AutoSniExt),
                activation_filter: None,
                midhost_offset: None,
                fake_host_template: None,
                overlap_size: 0,
                seqovl_fake_mode: ripdpi_config::SeqOverlapFakeMode::Profile,
                fragment_count: 3,
                min_fragment_size: 12,
                max_fragment_size: 64,
                inter_segment_delay_ms: 0,
                ip_frag_disorder: false,
                ipv6_hop_by_hop: false,
                ipv6_dest_opt: false,
                ipv6_dest_opt2: false,
                ipv6_routing: false,
                ipv6_frag_next_override: None,
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
        group.actions.tcp_chain =
            vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

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
        group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
        group.actions.udp_chain = vec![UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 2,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        }];

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
        group.actions.tcp_chain =
            vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

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
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
            TcpChainStep {
                kind: TcpChainStepKind::TlsRandRec,
                offset: OffsetExpr::adaptive(OffsetBase::AutoSniExt),
                activation_filter: None,
                midhost_offset: None,
                fake_host_template: None,
                overlap_size: 0,
                seqovl_fake_mode: ripdpi_config::SeqOverlapFakeMode::Profile,
                fragment_count: 3,
                min_fragment_size: 12,
                max_fragment_size: 64,
                inter_segment_delay_ms: 0,
                ip_frag_disorder: false,
                ipv6_hop_by_hop: false,
                ipv6_dest_opt: false,
                ipv6_dest_opt2: false,
                ipv6_routing: false,
                ipv6_frag_next_override: None,
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

    // --- ChoiceState unit tests ---

    #[test]
    fn choice_state_pins_on_success() {
        let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
        assert_eq!(cs.current(), Some(10));

        cs.note_success();
        assert_eq!(cs.current(), Some(10));
        assert_eq!(cs.pinned, Some(10));

        // Even after advancing the index, pinned value still wins.
        cs.candidate_index = 2;
        assert_eq!(cs.current(), Some(10));
    }

    #[test]
    fn choice_state_advances_on_failure() {
        let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
        assert_eq!(cs.current(), Some(10));

        cs.note_failure(1000);
        assert_eq!(cs.current(), Some(20));
        assert_eq!(cs.candidate_index, 1);
    }

    #[test]
    fn choice_state_cooldown_skips_recent_failure() {
        let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
        let t = 100_000u64;

        // Fail index 0 at time T -- puts it on cooldown, advances to index 1.
        cs.note_failure(t);
        assert_eq!(cs.current(), Some(20));

        // Fail index 1 at time T+1 (within 15s window of index 0).
        // Index 0 is still on cooldown, so should skip to index 2.
        cs.note_failure(t + 1);
        assert_eq!(cs.current(), Some(30));
        assert_eq!(cs.candidate_index, 2);
    }

    #[test]
    fn choice_state_cooldown_expires() {
        let mut cs = ChoiceState::new(vec![10u32, 20, 30]);
        let t = 100_000u64;

        // Fail index 0 at time T -- cooldown until T+15000, advances to 1.
        cs.note_failure(t);
        assert_eq!(cs.current(), Some(20));

        // Fail index 1 at T+1 -- cooldown until T+15001, advances to 2
        // (index 0 still on cooldown).
        cs.note_failure(t + 1);
        assert_eq!(cs.current(), Some(30));

        // Fail index 2 after index 0's cooldown has expired (T+16000 > T+15000).
        // Index 0 is now eligible again.
        cs.note_failure(t + 16_000);
        assert_eq!(cs.current(), Some(10));
        assert_eq!(cs.candidate_index, 0);
    }

    #[test]
    fn single_candidate_failure_is_noop() {
        let mut cs = ChoiceState::new(vec![42u32]);
        assert_eq!(cs.current(), Some(42));

        cs.note_failure(1000);
        assert_eq!(cs.current(), Some(42));
        assert_eq!(cs.candidate_index, 0);
    }

    // --- AdaptivePlannerState unit tests ---

    /// Helper: build a DesyncGroup with a single adaptive TCP split step.
    fn tcp_group_with_adaptive_split() -> DesyncGroup {
        let mut g = DesyncGroup::new(0);
        g.actions.tcp_chain =
            vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];
        g
    }

    #[test]
    fn planner_state_cycles_dimensions_on_failure() {
        let seed = 12345u64;
        let mut state = AdaptivePlannerState::new(seed);

        // Sync TCP candidates with a non-TLS payload so split_offset_base is populated.
        let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        state.sync_tcp_candidates(&group, payload);

        // The only adaptive dimension for this config is split_offset_base (dimension 0).
        // Record initial value.
        let initial = state.current_hints().split_offset_base;
        assert!(initial.is_some());

        // After note_failure, the value for split_offset_base should change
        // (the dimension cursor advances to the next dimension, but only
        // dimension 0 is active, so it will eventually circle back to it).
        let now = 100_000u64;
        // We call advance_dimension directly to verify dimension cycling with
        // a known order.
        let order = state.dimension_order.clone();
        let first_active = order.iter().position(|&d| d == 0).expect("dimension 0 in order");

        // Manually advance so we can control timing (note_failure uses now_millis()).
        state.dimension_cursor = first_active;
        let advanced = state.advance_dimension(0, now);
        assert!(advanced);

        let after = state.current_hints().split_offset_base;
        assert_ne!(initial, after, "split_offset_base should change after advancing dimension 0");
    }

    #[test]
    fn planner_state_success_pins_all_dimensions() {
        let mut state = AdaptivePlannerState::new(99);
        let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        state.sync_tcp_candidates(&group, payload);

        let before = state.current_hints();
        state.note_success();

        // All dimensions should be pinned -- verify via the underlying state.
        assert!(state.split_offset_base.as_ref().unwrap().pinned.is_some());

        // Re-resolve hints: should match what was pinned.
        let after = state.current_hints();
        assert_eq!(before.split_offset_base, after.split_offset_base);
    }

    // --- AdaptivePlannerResolver tests ---

    #[test]
    fn resolver_tracks_per_host_state() {
        let payload = b"GET / HTTP/1.1\r\nHost: alpha.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let target = addr(80);

        let mut resolver = AdaptivePlannerResolver::default();

        let alpha = resolver.resolve_tcp_hints(None, 0, target, Some("alpha.test"), &group, payload);
        let beta = resolver.resolve_tcp_hints(None, 0, target, Some("beta.test"), &group, payload);

        // Both should start with the same initial candidate (Host).
        assert_eq!(alpha.split_offset_base, Some(OffsetBase::Host));
        assert_eq!(beta.split_offset_base, Some(OffsetBase::Host));

        // Fail alpha, advance it.
        resolver.note_tcp_failure(None, 0, target, Some("alpha.test"), payload);
        let alpha_after = resolver.resolve_tcp_hints(None, 0, target, Some("alpha.test"), &group, payload);
        let beta_after = resolver.resolve_tcp_hints(None, 0, target, Some("beta.test"), &group, payload);

        // Alpha should have advanced, beta should remain unchanged.
        assert_ne!(alpha_after.split_offset_base, Some(OffsetBase::Host));
        assert_eq!(beta_after.split_offset_base, Some(OffsetBase::Host));
    }

    #[test]
    fn resolver_tcp_success_pins_state() {
        let payload = b"GET / HTTP/1.1\r\nHost: pin.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let target = addr(80);

        let mut resolver = AdaptivePlannerResolver::default();

        let first = resolver.resolve_tcp_hints(None, 0, target, Some("pin.test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

        // Pin via success.
        resolver.note_tcp_success(None, 0, target, Some("pin.test"), payload);

        // Resolve again -- should still be Host (pinned).
        let after_pin = resolver.resolve_tcp_hints(None, 0, target, Some("pin.test"), &group, payload);
        assert_eq!(after_pin.split_offset_base, Some(OffsetBase::Host));

        // Even after a failure, the pin is cleared and we advance, but the key
        // point is the pin held across the second resolve.
        resolver.note_tcp_failure(None, 0, target, Some("pin.test"), payload);
        let after_fail = resolver.resolve_tcp_hints(None, 0, target, Some("pin.test"), &group, payload);
        assert_ne!(after_fail.split_offset_base, first.split_offset_base);
    }

    // --- Tests requested for full coverage ---

    #[test]
    fn resolver_returns_default_hints_for_fresh_key() {
        let payload = &[0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x01, 0x00, 0x00];
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain =
            vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoBalanced))];

        let mut resolver = AdaptivePlannerResolver::default();
        let hints = resolver.resolve_tcp_hints(None, 0, addr(443), Some("fresh.test"), &group, payload);

        // A fresh key should return index-0 candidates. For AutoBalanced on a
        // TLS payload, index 0 is ExtLen.
        assert_eq!(hints.split_offset_base, Some(OffsetBase::ExtLen));
        // No UDP-related hints on a TCP resolve.
        assert_eq!(hints.udp_burst_profile, None);
        assert_eq!(hints.quic_fake_profile, None);
    }

    #[test]
    fn note_success_pins_current_candidate() {
        let payload = b"GET / HTTP/1.1\r\nHost: pin-check.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let target = addr(80);

        let mut resolver = AdaptivePlannerResolver::default();
        let first = resolver.resolve_tcp_hints(None, 0, target, Some("pin-check.test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

        resolver.note_tcp_success(None, 0, target, Some("pin-check.test"), payload);

        // Subsequent resolves must return the same pinned value.
        let second = resolver.resolve_tcp_hints(None, 0, target, Some("pin-check.test"), &group, payload);
        assert_eq!(second.split_offset_base, first.split_offset_base);

        let third = resolver.resolve_tcp_hints(None, 0, target, Some("pin-check.test"), &group, payload);
        assert_eq!(third.split_offset_base, first.split_offset_base);
    }

    #[test]
    fn note_failure_advances_to_next_candidate() {
        let payload = b"GET / HTTP/1.1\r\nHost: advance.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let target = addr(80);

        let mut resolver = AdaptivePlannerResolver::default();
        let first = resolver.resolve_tcp_hints(None, 0, target, Some("advance.test"), &group, payload);
        assert_eq!(first.split_offset_base, Some(OffsetBase::Host));

        resolver.note_tcp_failure(None, 0, target, Some("advance.test"), payload);
        let second = resolver.resolve_tcp_hints(None, 0, target, Some("advance.test"), &group, payload);

        // At least one dimension must have changed after failure.
        assert_ne!(first.split_offset_base, second.split_offset_base, "split_offset_base should differ after failure");
    }

    #[test]
    fn choice_state_new_starts_at_index_zero() {
        let cs = ChoiceState::new(vec![100u32, 200, 300]);
        assert_eq!(cs.candidate_index, 0);
        assert_eq!(cs.pinned, None);
        assert_eq!(cs.current(), Some(100));
    }

    #[test]
    fn choice_state_pin_preserves_current_value() {
        let mut cs = ChoiceState::new(vec![5u32, 10, 15]);
        // Advance to index 1 via failure.
        cs.note_failure(1000);
        assert_eq!(cs.current(), Some(10));

        cs.note_success();
        assert_eq!(cs.pinned, Some(10));
        assert_eq!(cs.current(), Some(10));

        // Manually move candidate_index -- pinned value should still win.
        cs.candidate_index = 2;
        assert_eq!(cs.current(), Some(10));
    }

    #[test]
    fn choice_state_advance_wraps_around() {
        let mut cs = ChoiceState::new(vec![1u32, 2, 3]);
        assert_eq!(cs.candidate_index, 0);

        // Advance through all candidates using well-spaced timestamps to avoid
        // cooldown interference.
        cs.note_failure(0);
        assert_eq!(cs.candidate_index, 1);

        cs.note_failure(ADAPTIVE_RETRY_WINDOW_MS + 1);
        assert_eq!(cs.candidate_index, 2);

        cs.note_failure(2 * ADAPTIVE_RETRY_WINDOW_MS + 2);
        assert_eq!(cs.candidate_index, 0, "should wrap around to index 0");
        assert_eq!(cs.current(), Some(1));
    }

    #[test]
    fn adaptive_store_round_trips_full_state() {
        let payload = b"GET / HTTP/1.1\r\nHost: persist.example.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let (config, _tmp) = config_with_adaptive_store(vec![group.clone()]);
        let target = addr(443);

        let mut resolver = AdaptivePlannerResolver::default();
        resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("persist.example.test"), &group, payload);
        resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("persist.example.test"), payload);
        resolver.note_tcp_success(Some("scope-a"), 0, target, Some("persist.example.test"), payload);
        resolver.flush_store(&config);

        let reloaded = AdaptivePlannerResolver::load(&config);
        assert_eq!(reloaded.states, resolver.states);
    }

    #[test]
    fn adaptive_store_fingerprint_invalidates_stale_entries() {
        let payload = b"GET / HTTP/1.1\r\nHost: fingerprint.example.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let (config, _tmp) = config_with_adaptive_store(vec![group.clone()]);
        let store_path = adaptive_store_path(&config).expect("test config has store_path");
        let target = addr(443);

        let mut resolver = AdaptivePlannerResolver::default();
        resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("fingerprint.example.test"), &group, payload);
        resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("fingerprint.example.test"), payload);
        resolver.flush_store(&config);

        let mut changed_group = group.clone();
        changed_group
            .actions
            .tcp_chain
            .push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoEndHost)));
        let (changed_config, _changed_tmp) = config_with_adaptive_store(vec![changed_group]);
        let changed_store_path = adaptive_store_path(&changed_config).expect("test config has store_path");
        assert!(store_path.exists(), "flush should write adaptive store before reload test");
        fs::copy(&store_path, &changed_store_path).expect("copy persisted store");

        let reloaded = AdaptivePlannerResolver::load(&changed_config);
        assert!(reloaded.states.is_empty(), "changed group layout should invalidate persisted adaptive state");
    }

    #[test]
    fn adaptive_store_debounce_defers_write_until_flush() {
        let payload = b"GET / HTTP/1.1\r\nHost: debounce.example.test\r\n\r\n";
        let group = tcp_group_with_adaptive_split();
        let (config, _tmp) = config_with_adaptive_store(vec![group.clone()]);
        let store_path = adaptive_store_path(&config).expect("test config has store_path");
        let target = addr(443);

        let mut resolver = AdaptivePlannerResolver::default();
        resolver.resolve_tcp_hints(Some("scope-a"), 0, target, Some("debounce.example.test"), &group, payload);
        resolver.note_tcp_failure(Some("scope-a"), 0, target, Some("debounce.example.test"), payload);
        resolver.last_persist_at_ms = now_millis();
        resolver.persist_if_due(&config);
        assert!(!store_path.exists(), "debounced persist should not write immediately");

        resolver.flush_store(&config);
        assert!(store_path.exists(), "flush should force adaptive store write");
    }

    #[test]
    fn adaptive_store_returns_none_when_host_store_path_is_missing() {
        let config = ripdpi_config::RuntimeConfig::default();
        assert_eq!(adaptive_store_path(&config), None);
    }

    #[test]
    fn stored_offset_base_round_trips_ech_ext() {
        let stored = StoredOffsetBase::from(OffsetBase::EchExt);

        assert_eq!(stored, StoredOffsetBase::EchExt);
        assert_eq!(restore_offset_base(stored), Some(OffsetBase::EchExt));
    }
}
