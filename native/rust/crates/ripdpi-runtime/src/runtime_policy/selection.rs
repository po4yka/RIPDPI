use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use ciadpi_config::{RuntimeConfig, AUTO_NOPOST, AUTO_SORT, DETECT_RECONN};

use super::autolearn::normalize_learned_host;
use super::matching::group_matches;
use super::{now_millis, ConnectionRoute, RetrySelectionPenalty, RouteAdvance, RuntimePolicy, TransportProtocol};

impl RuntimePolicy {
    pub fn select_initial(
        &mut self,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        config: &RuntimeConfig,
    ) -> Option<ConnectionRoute> {
        if let Some(normalized_host) =
            host.filter(|_| transport == TransportProtocol::Tcp).and_then(normalize_learned_host)
        {
            if let Some(route) =
                self.select_host_route(config, &normalized_host, dest, payload, allow_unknown_payload, transport)
            {
                return Some(route);
            }
        } else if let Some(route) = self.lookup_and_prune(config, dest) {
            let group = config.groups.get(route.group_index)?;
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                return Some(route);
            }
        }

        let mut attempted_mask = 0u64;
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            if self.detect_for(config, idx) != 0 {
                continue;
            }
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                return Some(ConnectionRoute { group_index: idx, attempted_mask });
            }
            attempted_mask |= group.bit;
        }
        None
    }

    pub(crate) fn select_next(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
        if let Some(normalized_host) =
            host.filter(|_| transport == TransportProtocol::Tcp).and_then(normalize_learned_host)
        {
            if let Some(next) = self.select_host_route_after(
                config,
                route,
                &normalized_host,
                dest,
                payload,
                transport,
                trigger,
                can_reconnect,
                retry_penalties,
            ) {
                return Some(next);
            }
        }
        let mut attempted_mask = route.attempted_mask | config.groups[route.group_index].bit;
        let mut eligible = Vec::new();
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            let detect = self.detect_for(config, idx);
            if attempted_mask & group.bit != 0 {
                continue;
            }
            if detect != 0 && (detect & trigger) == 0 {
                attempted_mask |= group.bit;
                continue;
            }
            if (detect & DETECT_RECONN) != 0 && !can_reconnect {
                attempted_mask |= group.bit;
                continue;
            }
            if group_matches(config, group, dest, payload, false, transport) {
                eligible.push(idx);
            } else {
                attempted_mask |= group.bit;
            }
        }
        select_best_candidate(self, &eligible, attempted_mask, retry_penalties)
    }

    pub fn advance_route(
        &mut self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        request: RouteAdvance<'_>,
    ) -> io::Result<Option<ConnectionRoute>> {
        if !request.can_reconnect && (config.auto_level & AUTO_NOPOST) != 0 {
            return Ok(None);
        }

        if request.penalize_strategy_failure {
            if let Some(group) = self.groups.get_mut(route.group_index) {
                group.fail_count += 1;
            }
        }

        if request.transport == TransportProtocol::Tcp && request.penalize_strategy_failure {
            if let Some(host) = request.host.as_deref() {
                self.note_host_failure(config, host, route.group_index)?;
            }
        }

        let next = self.select_next(
            config,
            route,
            request.dest,
            request.payload,
            request.host.as_deref(),
            request.transport,
            request.trigger,
            request.can_reconnect,
            request.retry_penalties,
        );

        if (config.auto_level & AUTO_SORT) != 0 {
            if let Some(ref next_route) = next {
                let current_pri = self.groups.get(route.group_index).map(|group| group.pri).unwrap_or_default();
                let next_pri = self.groups.get(next_route.group_index).map(|group| group.pri).unwrap_or_default();
                if current_pri > next_pri {
                    self.swap_groups(route.group_index, next_route.group_index);
                }
            }
            if let Some(group) = self.groups.get_mut(route.group_index) {
                group.pri += 1;
            }
        }

        match next {
            Some(next_route) => {
                self.store(config, request.dest, next_route.group_index, next_route.attempted_mask, request.host)?;
                Ok(Some(next_route))
            }
            None => {
                self.clear(config, request.dest)?;
                Ok(None)
            }
        }
    }

    pub fn note_route_success(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
    ) -> io::Result<()> {
        self.note_route_success_for_transport(config, dest, route, host, TransportProtocol::Tcp)
    }

    pub fn note_route_success_for_transport(
        &mut self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
        transport: TransportProtocol,
    ) -> io::Result<()> {
        if transport == TransportProtocol::Tcp {
            let normalized_host = host.and_then(normalize_learned_host);
            self.store(config, dest, route.group_index, route.attempted_mask, normalized_host.clone())?;
            if let Some(host) = normalized_host {
                self.note_host_success(config, &host, route.group_index)?;
            }
        } else {
            self.store(config, dest, route.group_index, route.attempted_mask, host.map(str::to_owned))?;
        }
        Ok(())
    }

    fn select_host_route(
        &self,
        config: &RuntimeConfig,
        host: &str,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
    ) -> Option<ConnectionRoute> {
        let record = self.learned_hosts(config).get(host)?;
        if let Some(route) = self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            allow_unknown_payload,
            transport,
            0,
            true,
            false,
            None,
        ) {
            return Some(route);
        }
        let attempted_mask = record
            .preferred_groups
            .iter()
            .fold(0u64, |mask, &index| mask | config.groups.get(index).map_or(0, |group| group.bit));
        let mut rejected_mask = attempted_mask;
        let mut eligible = Vec::new();
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            if rejected_mask & group.bit != 0 || self.detect_for(config, idx) != 0 {
                continue;
            }
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                eligible.push(idx);
            } else {
                rejected_mask |= group.bit;
            }
        }
        if let Some(route) = select_best_candidate(self, &eligible, rejected_mask, None) {
            return Some(route);
        }

        self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            allow_unknown_payload,
            transport,
            rejected_mask,
            true,
            true,
            None,
        )
    }

    fn select_host_route_after(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        host: &str,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
        let record = self.learned_hosts(config).get(host)?;
        let mut attempted_mask = route.attempted_mask | config.groups[route.group_index].bit;
        if let Some(route) = self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            false,
            transport,
            attempted_mask,
            can_reconnect,
            false,
            retry_penalties,
        ) {
            return Some(route);
        }
        attempted_mask |= record
            .preferred_groups
            .iter()
            .fold(0u64, |mask, &index| mask | config.groups.get(index).map_or(0, |group| group.bit));
        let mut rejected_mask = attempted_mask;
        let mut eligible = Vec::new();
        for &idx in self.ordered_indices() {
            let group = config.groups.get(idx)?;
            let detect = self.detect_for(config, idx);
            if rejected_mask & group.bit != 0 {
                continue;
            }
            if detect != 0 && (detect & trigger) == 0 {
                rejected_mask |= group.bit;
                continue;
            }
            if (detect & DETECT_RECONN) != 0 && !can_reconnect {
                rejected_mask |= group.bit;
                continue;
            }
            if group_matches(config, group, dest, payload, false, transport) {
                eligible.push(idx);
            } else {
                rejected_mask |= group.bit;
            }
        }
        if let Some(route) = select_best_candidate(self, &eligible, rejected_mask, retry_penalties) {
            return Some(route);
        }

        self.preferred_host_candidate(
            config,
            record,
            dest,
            payload,
            false,
            transport,
            rejected_mask,
            can_reconnect,
            true,
            retry_penalties,
        )
    }

    fn preferred_host_candidate(
        &self,
        config: &RuntimeConfig,
        record: &super::types::LearnedHostRecord,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        attempted_mask: u64,
        can_reconnect: bool,
        penalized: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
        let now_ms = now_millis();
        let mut next_mask = attempted_mask;
        let mut eligible = Vec::new();
        for &idx in &record.preferred_groups {
            let group = config.groups.get(idx)?;
            if next_mask & group.bit != 0 {
                continue;
            }
            if (self.detect_for(config, idx) & DETECT_RECONN) != 0 && !can_reconnect {
                next_mask |= group.bit;
                continue;
            }
            let is_penalized = record.group_stats.get(&idx).is_some_and(|stats| stats.penalty_until_ms > now_ms);
            if is_penalized != penalized {
                next_mask |= group.bit;
                continue;
            }
            if group_matches(config, group, dest, payload, allow_unknown_payload, transport) {
                eligible.push(idx);
            } else {
                next_mask |= group.bit;
            }
        }
        select_best_candidate(self, &eligible, next_mask, retry_penalties)
    }
}

fn select_best_candidate(
    cache: &RuntimePolicy,
    eligible: &[usize],
    attempted_mask: u64,
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
) -> Option<ConnectionRoute> {
    let mut ranked = eligible.to_vec();
    ranked.sort_by_key(|index| {
        let penalty = retry_penalty(retry_penalties, *index);
        let group = cache.groups.get(*index);
        (
            penalty.same_signature_cooldown_ms > 0,
            penalty.family_cooldown_ms > 0,
            group.map_or(0, |value| value.fail_count),
            group.map_or(0, |value| value.pri),
            penalty.diversification_rank,
            *index,
        )
    });
    ranked.into_iter().next().map(|group_index| ConnectionRoute { group_index, attempted_mask })
}

fn retry_penalty(
    retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    group_index: usize,
) -> RetrySelectionPenalty {
    retry_penalties.and_then(|value| value.get(&group_index).copied()).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ciadpi_config::{DesyncGroup, DETECT_RECONN};

    use super::*;
    use crate::runtime_policy::test_support::{autolearn_config, config_with_groups, sample_dest};

    #[test]
    fn select_initial_group_skips_detect_only_groups() {
        let mut first = DesyncGroup::new(0);
        first.detect = DETECT_RECONN;
        let second = DesyncGroup::new(1);
        let config = config_with_groups(vec![first, second]);
        let mut policy = RuntimePolicy::load(&config);

        let route = policy
            .select_initial(sample_dest(80), None, None, true, TransportProtocol::Tcp, &config)
            .expect("fallback route");

        assert_eq!(route.group_index, 1);
        assert_eq!(route.attempted_mask, 0);
    }

    #[test]
    fn select_next_group_advances_to_matching_trigger_group() {
        let first = DesyncGroup::new(0);
        let mut second = DesyncGroup::new(1);
        second.detect = DETECT_RECONN;
        let config = config_with_groups(vec![first, second]);
        let policy = RuntimePolicy::load(&config);
        let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };

        let next = policy
            .select_next(
                &config,
                &route,
                sample_dest(443),
                None,
                None,
                TransportProtocol::Tcp,
                DETECT_RECONN,
                true,
                None,
            )
            .expect("next route");

        assert_eq!(next.group_index, 1);
        assert_eq!(next.attempted_mask, config.groups[0].bit);
    }

    #[test]
    fn select_next_group_prefers_non_cooled_candidate_when_retry_penalties_exist() {
        let first = DesyncGroup::new(0);
        let mut second = DesyncGroup::new(1);
        second.detect = DETECT_RECONN;
        let mut third = DesyncGroup::new(2);
        third.detect = DETECT_RECONN;
        let config = config_with_groups(vec![first, second, third]);
        let policy = RuntimePolicy::load(&config);
        let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let penalties = BTreeMap::from([
            (
                1usize,
                RetrySelectionPenalty {
                    same_signature_cooldown_ms: 1_000,
                    family_cooldown_ms: 0,
                    diversification_rank: 10,
                },
            ),
            (
                2usize,
                RetrySelectionPenalty {
                    same_signature_cooldown_ms: 0,
                    family_cooldown_ms: 0,
                    diversification_rank: 20,
                },
            ),
        ]);

        let next = policy
            .select_next(
                &config,
                &route,
                sample_dest(443),
                None,
                None,
                TransportProtocol::Tcp,
                DETECT_RECONN,
                true,
                Some(&penalties),
            )
            .expect("next route");

        assert_eq!(next.group_index, 2);
    }

    #[test]
    fn select_next_group_uses_diversification_rank_as_tiebreaker() {
        let first = DesyncGroup::new(0);
        let mut second = DesyncGroup::new(1);
        second.detect = DETECT_RECONN;
        let mut third = DesyncGroup::new(2);
        third.detect = DETECT_RECONN;
        let config = config_with_groups(vec![first, second, third]);
        let policy = RuntimePolicy::load(&config);
        let route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let penalties = BTreeMap::from([
            (
                1usize,
                RetrySelectionPenalty {
                    same_signature_cooldown_ms: 0,
                    family_cooldown_ms: 0,
                    diversification_rank: 25,
                },
            ),
            (
                2usize,
                RetrySelectionPenalty { same_signature_cooldown_ms: 0, family_cooldown_ms: 0, diversification_rank: 5 },
            ),
        ]);

        let next = policy
            .select_next(
                &config,
                &route,
                sample_dest(443),
                None,
                None,
                TransportProtocol::Tcp,
                DETECT_RECONN,
                true,
                Some(&penalties),
            )
            .expect("next route");

        assert_eq!(next.group_index, 2);
    }

    #[test]
    fn host_preference_outranks_destination_cache() {
        let config = autolearn_config(2, 32);
        let dest = sample_dest(443);
        let mut policy = RuntimePolicy::load(&config);

        policy.store(&config, dest, 0, 0, None).expect("store destination cache");
        policy
            .note_route_success(
                &config,
                dest,
                &ConnectionRoute { group_index: 1, attempted_mask: 0 },
                Some("Example.org"),
            )
            .expect("learn host route");

        let route = policy
            .select_initial(dest, None, Some("example.org"), true, TransportProtocol::Tcp, &config)
            .expect("host-aware route");

        assert_eq!(route.group_index, 1);
    }
}
