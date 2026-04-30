use std::collections::HashMap;
use std::net::SocketAddr;

use ripdpi_config::DesyncGroup;
use ripdpi_desync::AdaptivePlannerHints;

use super::key::{adaptive_key, adaptive_seed, now_millis, tcp_flow_kind, udp_flow_kind};
use super::persistence::{load_adaptive_store, write_adaptive_store};
use super::state::AdaptivePlannerState;
use super::types::AdaptivePlannerKey;

const ADAPTIVE_TUNING_PERSIST_DEBOUNCE_MS: u64 = 2_000;
const ADAPTIVE_TUNING_PERSIST_ERROR_COOLDOWN_MS: u64 = 300_000;

#[derive(Debug, Default)]
pub struct AdaptivePlannerResolver {
    pub(super) states: HashMap<AdaptivePlannerKey, AdaptivePlannerState>,
    pub(super) last_persist_at_ms: u64,
    pub(super) dirty: bool,
    pub(super) persist_error_logged_at_ms: u64,
}

impl AdaptivePlannerResolver {
    pub fn load(config: &ripdpi_config::RuntimeConfig) -> Self {
        let states = load_adaptive_store(config).unwrap_or_default();
        Self { states, last_persist_at_ms: 0, dirty: false, persist_error_logged_at_ms: 0 }
    }

    /// Discard all cached per-flow adaptive state. Used when a network change
    /// invalidates learned parameters.
    pub fn clear_all(&mut self) {
        self.states.clear();
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
