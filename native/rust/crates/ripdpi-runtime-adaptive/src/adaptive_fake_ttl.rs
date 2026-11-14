use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};

use ripdpi_config::AutoTtlConfig;

use crate::adaptive_port::FakeTtlAttempt;

/// Bounds per-target fake-TTL state for a long session, like `MAX_ADAPTIVE_STATES`.
const MAX_FAKE_TTL_STATES: usize = 1024;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum AdaptiveFakeTtlTarget {
    Host(String),
    Address(SocketAddr),
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct AdaptiveFakeTtlKey {
    group_index: usize,
    target: AdaptiveFakeTtlTarget,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct AdaptiveFakeTtlState {
    config: AutoTtlConfig,
    fallback_ttl: Option<u8>,
    generation: u64,
    revision: u64,
    seed: u8,
    candidates: Vec<u8>,
    candidate_index: usize,
    pinned_ttl: Option<u8>,
    detected_fallback: Option<u8>,
    last_used_seq: u64,
}

#[derive(Debug, Default)]
pub struct AdaptiveFakeTtlResolver {
    states: HashMap<AdaptiveFakeTtlKey, AdaptiveFakeTtlState>,
    access_sequence: u64,
}

impl AdaptiveFakeTtlResolver {
    pub fn resolve(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        config: AutoTtlConfig,
        fallback_ttl: Option<u8>,
    ) -> FakeTtlAttempt {
        let key = AdaptiveFakeTtlKey {
            group_index,
            target: normalized_host(host).map_or(AdaptiveFakeTtlTarget::Address(dest), AdaptiveFakeTtlTarget::Host),
        };
        if self.states.len() >= MAX_FAKE_TTL_STATES && !self.states.contains_key(&key) {
            // shortcut: scans at most 1024 entries on insertion; index recency only if this path gets hot.
            if let Some(oldest) =
                self.states.iter().min_by_key(|(_, state)| state.last_used_seq).map(|(key, _)| key.clone())
            {
                self.states.remove(&oldest);
            }
        }
        self.access_sequence = self.access_sequence.saturating_add(1);
        let generation = self.access_sequence;
        let state =
            self.states.entry(key).or_insert_with(|| AdaptiveFakeTtlState::new(config, fallback_ttl, None, generation));
        let effective = state.detected_fallback.or(fallback_ttl);
        if state.config != config
            || state.fallback_ttl != fallback_ttl
            || (state.pinned_ttl.is_none() && state.seed != seed_ttl(config, effective))
        {
            let detected = state.detected_fallback;
            *state = AdaptiveFakeTtlState::new(config, fallback_ttl, detected, generation);
        }
        state.last_used_seq = self.access_sequence;
        let ttl = state.current_ttl();
        let group_label = format!("{group_index}");
        metrics::gauge!("ripdpi_fake_ttl_effective", "group" => group_label).set(f64::from(ttl));
        FakeTtlAttempt { ttl, generation: state.generation, revision: state.revision }
    }

    pub fn note_success(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, attempt: FakeTtlAttempt) {
        if let Some(state) = self.state_for_attempt(group_index, dest, host, attempt)
            && let Some(index) = state.candidates.iter().position(|&ttl| ttl == attempt.ttl)
        {
            state.candidate_index = index;
            state.pinned_ttl = Some(attempt.ttl);
            state.revision = state.revision.wrapping_add(1);
        }
    }

    pub fn note_failure(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, attempt: FakeTtlAttempt) {
        if let Some(state) = self.state_for_attempt(group_index, dest, host, attempt)
            && state.revision == attempt.revision
            && state.current_ttl() == attempt.ttl
        {
            state.pinned_ttl = None;
            state.advance();
            state.revision = state.revision.wrapping_add(1);
            let group_label = format!("{group_index}");
            metrics::counter!("ripdpi_fake_ttl_advances_total", "group" => group_label).increment(1);
        }
    }

    pub fn note_server_ttl(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        observed_ttl: u8,
        attempt: FakeTtlAttempt,
    ) {
        if let Some(state) = self.state_for_attempt(group_index, dest, host, attempt)
            && state.revision == attempt.revision
        {
            state.detected_fallback = Some(detected_from_observed_ttl(observed_ttl));
        }
    }

    fn state_for_attempt(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        attempt: FakeTtlAttempt,
    ) -> Option<&mut AdaptiveFakeTtlState> {
        self.states
            .get_mut(&AdaptiveFakeTtlKey {
                group_index,
                target: normalized_host(host).map_or(AdaptiveFakeTtlTarget::Address(dest), AdaptiveFakeTtlTarget::Host),
            })
            .filter(|state| state.generation == attempt.generation)
    }
}

impl AdaptiveFakeTtlState {
    fn new(config: AutoTtlConfig, fallback_ttl: Option<u8>, detected_fallback: Option<u8>, generation: u64) -> Self {
        let effective = detected_fallback.or(fallback_ttl);
        let candidates = candidate_order(config, effective);
        let seed = seed_ttl(config, effective);
        Self {
            config,
            fallback_ttl,
            generation,
            revision: 0,
            seed,
            candidates,
            candidate_index: 0,
            pinned_ttl: None,
            detected_fallback,
            last_used_seq: 0,
        }
    }

    fn current_ttl(&self) -> u8 {
        self.pinned_ttl.or_else(|| self.candidates.get(self.candidate_index).copied()).unwrap_or(self.seed)
    }

    fn advance(&mut self) {
        if self.candidates.is_empty() {
            return;
        }
        if self.candidate_index + 1 < self.candidates.len() {
            self.candidate_index += 1;
        } else {
            self.candidate_index = 0;
        }
    }
}

pub(crate) fn detected_from_observed_ttl(observed: u8) -> u8 {
    let reference: u8 = if observed <= 64 {
        64
    } else if observed <= 128 {
        128
    } else {
        255
    };
    let hops = reference.saturating_sub(observed);
    hops.saturating_sub(1).max(1)
}

fn seed_ttl(config: AutoTtlConfig, fallback_ttl: Option<u8>) -> u8 {
    let fallback = fallback_ttl.unwrap_or(8);
    fallback.clamp(config.min_ttl, config.max_ttl)
}

fn candidate_order(config: AutoTtlConfig, fallback_ttl: Option<u8>) -> Vec<u8> {
    let seed = u16::from(seed_ttl(config, fallback_ttl));
    let min = u16::from(config.min_ttl);
    let max = u16::from(config.max_ttl);
    let mut out = vec![seed as u8];
    match config.delta.cmp(&0) {
        std::cmp::Ordering::Less => {
            out.extend((min..seed).rev().map(|ttl| ttl as u8));
            out.extend((seed + 1..=max).map(|ttl| ttl as u8));
        }
        std::cmp::Ordering::Greater => {
            out.extend((seed + 1..=max).map(|ttl| ttl as u8));
            out.extend((min..seed).rev().map(|ttl| ttl as u8));
        }
        std::cmp::Ordering::Equal => {
            for distance in 1..=(seed - min).max(max - seed) {
                if distance <= seed - min {
                    out.push((seed - distance) as u8);
                }
                if distance <= max - seed {
                    out.push((seed + distance) as u8);
                }
            }
        }
    }
    out
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

#[cfg(test)]
mod boundary_tests;

#[cfg(test)]
mod feedback_tests;

#[cfg(test)]
mod tests;
