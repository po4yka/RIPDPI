use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};

use ciadpi_config::AutoTtlConfig;

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
    seed: u8,
    candidates: Vec<u8>,
    candidate_index: usize,
    pinned_ttl: Option<u8>,
}

#[derive(Debug, Default)]
pub struct AdaptiveFakeTtlResolver {
    states: HashMap<AdaptiveFakeTtlKey, AdaptiveFakeTtlState>,
}

impl AdaptiveFakeTtlResolver {
    pub fn resolve(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
        config: AutoTtlConfig,
        fallback_ttl: Option<u8>,
    ) -> u8 {
        let key = AdaptiveFakeTtlKey {
            group_index,
            target: normalized_host(host)
                .map(AdaptiveFakeTtlTarget::Host)
                .unwrap_or(AdaptiveFakeTtlTarget::Address(dest)),
        };
        let state = self
            .states
            .entry(key)
            .or_insert_with(|| AdaptiveFakeTtlState::new(config, fallback_ttl));
        if state.seed != seed_ttl(config, fallback_ttl) || state.candidates != candidate_order(config, fallback_ttl) {
            *state = AdaptiveFakeTtlState::new(config, fallback_ttl);
        }
        state.current_ttl()
    }

    pub fn note_success(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
    ) {
        if let Some(state) = self.states.get_mut(&AdaptiveFakeTtlKey {
            group_index,
            target: normalized_host(host)
                .map(AdaptiveFakeTtlTarget::Host)
                .unwrap_or(AdaptiveFakeTtlTarget::Address(dest)),
        }) {
            state.pinned_ttl = Some(state.current_ttl());
        }
    }

    pub fn note_failure(
        &mut self,
        group_index: usize,
        dest: SocketAddr,
        host: Option<&str>,
    ) {
        if let Some(state) = self.states.get_mut(&AdaptiveFakeTtlKey {
            group_index,
            target: normalized_host(host)
                .map(AdaptiveFakeTtlTarget::Host)
                .unwrap_or(AdaptiveFakeTtlTarget::Address(dest)),
        }) {
            state.pinned_ttl = None;
            state.advance();
        }
    }
}

impl AdaptiveFakeTtlState {
    fn new(config: AutoTtlConfig, fallback_ttl: Option<u8>) -> Self {
        let candidates = candidate_order(config, fallback_ttl);
        let seed = *candidates.first().unwrap_or(&8);
        Self {
            seed,
            candidates,
            candidate_index: 0,
            pinned_ttl: None,
        }
    }

    fn current_ttl(&self) -> u8 {
        self.pinned_ttl
            .or_else(|| self.candidates.get(self.candidate_index).copied())
            .unwrap_or(self.seed)
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

fn seed_ttl(config: AutoTtlConfig, fallback_ttl: Option<u8>) -> u8 {
    let fallback = fallback_ttl.unwrap_or(8);
    fallback.clamp(config.min_ttl, config.max_ttl)
}

fn candidate_order(config: AutoTtlConfig, fallback_ttl: Option<u8>) -> Vec<u8> {
    let seed = seed_ttl(config, fallback_ttl);
    let min = config.min_ttl;
    let max = config.max_ttl;
    let mut out = vec![seed];
    match config.delta.cmp(&0) {
        std::cmp::Ordering::Less => {
            for ttl in (min..seed).rev() {
                out.push(ttl);
            }
            for ttl in seed.saturating_add(1)..=max {
                out.push(ttl);
            }
        }
        std::cmp::Ordering::Greater => {
            for ttl in seed.saturating_add(1)..=max {
                out.push(ttl);
            }
            for ttl in (min..seed).rev() {
                out.push(ttl);
            }
        }
        std::cmp::Ordering::Equal => {
            let mut distance = 1u8;
            while seed.saturating_sub(distance) >= min || seed.saturating_add(distance) <= max {
                let lower = seed.saturating_sub(distance);
                if lower >= min && lower < seed {
                    out.push(lower);
                }
                let upper = seed.saturating_add(distance);
                if upper <= max && upper > seed {
                    out.push(upper);
                }
                if distance == u8::MAX {
                    break;
                }
                distance = distance.saturating_add(1);
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
mod tests {
    use super::*;

    fn config(delta: i8, min_ttl: u8, max_ttl: u8) -> AutoTtlConfig {
        AutoTtlConfig { delta, min_ttl, max_ttl }
    }

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::from(([127, 0, 0, 1], port))
    }

    #[test]
    fn negative_delta_prefers_lower_candidates_first() {
        assert_eq!(candidate_order(config(-1, 3, 12), Some(8)), vec![8, 7, 6, 5, 4, 3, 9, 10, 11, 12]);
    }

    #[test]
    fn positive_delta_prefers_higher_candidates_first() {
        assert_eq!(candidate_order(config(1, 3, 12), Some(8)), vec![8, 9, 10, 11, 12, 7, 6, 5, 4, 3]);
    }

    #[test]
    fn zero_delta_alternates_outward_from_seed() {
        assert_eq!(candidate_order(config(0, 3, 12), Some(8)), vec![8, 7, 9, 6, 10, 5, 11, 4, 12, 3]);
    }

    #[test]
    fn success_pins_and_failure_advances_or_wraps() {
        let mut resolver = AdaptiveFakeTtlResolver::default();
        let host = Some("Example.com");
        let target = addr(443);
        assert_eq!(resolver.resolve(0, target, host, config(-1, 3, 5), Some(5)), 5);
        resolver.note_failure(0, target, host);
        assert_eq!(resolver.resolve(0, target, host, config(-1, 3, 5), Some(5)), 4);
        resolver.note_success(0, target, host);
        resolver.note_failure(0, target, host);
        assert_eq!(resolver.resolve(0, target, host, config(-1, 3, 5), Some(5)), 3);
        resolver.note_failure(0, target, host);
        assert_eq!(resolver.resolve(0, target, host, config(-1, 3, 5), Some(5)), 5);
    }

    #[test]
    fn host_and_address_keys_remain_isolated() {
        let mut resolver = AdaptiveFakeTtlResolver::default();
        let config = config(-1, 3, 6);
        let target = addr(443);
        assert_eq!(resolver.resolve(0, target, Some("video.example"), config, Some(6)), 6);
        resolver.note_failure(0, target, Some("video.example"));
        assert_eq!(resolver.resolve(0, target, Some("video.example"), config, Some(6)), 5);
        assert_eq!(resolver.resolve(0, target, None, config, Some(6)), 6);
        assert_eq!(resolver.resolve(1, target, Some("video.example"), config, Some(6)), 6);
    }
}
