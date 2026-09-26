use std::collections::HashMap;
use std::net::SocketAddr;

use ripdpi_config::DesyncGroup;
use ripdpi_desync::AdaptivePlannerHints;

use super::MAX_ADAPTIVE_STATES;
use super::key::{adaptive_key, adaptive_seed, cacheable_key, now_millis, tcp_flow_kind, udp_flow_kind};
use super::persistence::{load_adaptive_store, write_adaptive_store};
use super::state::AdaptivePlannerState;
use super::types::AdaptivePlannerKey;

const ADAPTIVE_TUNING_PERSIST_DEBOUNCE_MS: u64 = 2_000;
const ADAPTIVE_TUNING_PERSIST_ERROR_COOLDOWN_MS: u64 = 300_000;

#[derive(Debug, Default)]
pub struct AdaptivePlannerResolver {
    pub(super) states: HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
    access_sequence: u64,
    pub(super) last_persist_at_ms: u64,
    pub(super) dirty: bool,
    pub(super) persist_error_logged_at_ms: u64,
}

impl AdaptivePlannerResolver {
    pub fn load(config: &ripdpi_config::RuntimeConfig) -> Self {
        let (states, trimmed) = match load_adaptive_store(config) {
            Ok(loaded) => loaded,
            Err(err) => {
                tracing::warn!("adaptive tuning store load failed; resetting cache: {err}");
                (HashMap::new(), err.kind() == std::io::ErrorKind::InvalidData)
            }
        };
        let access_sequence = states.len() as u64;
        Self { states, access_sequence, last_persist_at_ms: 0, dirty: trimmed, persist_error_logged_at_ms: 0 }
    }

    /// Discard all cached per-flow adaptive state. Used when a network change
    /// invalidates learned parameters.
    pub fn clear_all(&mut self) {
        self.states.clear();
        self.access_sequence = 0;
        self.dirty = true;
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
        let previous = self.states.remove(&key);
        let had_state = previous.is_some();
        let mut state = previous.unwrap_or_else(|| AdaptivePlannerState::new(seed));
        state.sync_tcp_candidates(group, payload);
        self.cache_hints(key, state, had_state)
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
        let previous = self.states.remove(&key);
        let had_state = previous.is_some();
        let mut state = previous.unwrap_or_else(|| AdaptivePlannerState::new(seed));
        state.sync_udp_candidates(group, payload);
        self.cache_hints(key, state, had_state)
    }

    fn cache_hints(
        &mut self,
        key: AdaptivePlannerKey,
        mut state: AdaptivePlannerState,
        had_state: bool,
    ) -> AdaptivePlannerHints {
        let hints = state.current_hints();
        if !state.has_candidates() || !cacheable_key(&key) {
            self.dirty |= had_state;
            return hints;
        }
        self.access_sequence = self.access_sequence.saturating_add(1);
        state.last_used_seq = self.access_sequence;
        if self.states.len() == MAX_ADAPTIVE_STATES {
            // ponytail: scan at most 1024 entries on insertion; index recency only if this path gets hot.
            if let Some(oldest) =
                self.states.iter().min_by_key(|(_, state)| state.last_used_seq).map(|(key, _)| key.clone())
            {
                self.states.remove(&oldest);
                self.dirty = true;
            }
        }
        self.states.insert(key, state);
        hints
    }

    fn note_feedback(&mut self, key: AdaptivePlannerKey, success: bool) {
        if let Some(state) = self.states.get_mut(&key) {
            self.access_sequence = self.access_sequence.saturating_add(1);
            state.last_used_seq = self.access_sequence;
            if success {
                state.note_success();
            } else {
                state.note_failure();
            }
            self.dirty = true;
        }
    }

    pub fn note_tcp_success(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        self.note_feedback(adaptive_key(network_scope_key, group_index, tcp_flow_kind(payload), dest, host), true);
    }

    pub fn note_tcp_failure(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        self.note_feedback(adaptive_key(network_scope_key, group_index, tcp_flow_kind(payload), dest, host), false);
    }

    pub fn note_udp_success(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        self.note_feedback(adaptive_key(network_scope_key, group_index, udp_flow_kind(payload), dest, host), true);
    }

    pub fn note_udp_failure(
        &mut self,
        network_scope_key: Option<&str>,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) {
        self.note_feedback(adaptive_key(network_scope_key, group_index, udp_flow_kind(payload), dest, host), false);
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
                self.persist_error_logged_at_ms = 0;
            }
            Err(err) => {
                if now_ms.saturating_sub(self.persist_error_logged_at_ms) >= ADAPTIVE_TUNING_PERSIST_ERROR_COOLDOWN_MS {
                    tracing::warn!("adaptive tuning store write failed (non-fatal): {err}");
                    self.persist_error_logged_at_ms = now_ms;
                }
            }
        }
    }
}
