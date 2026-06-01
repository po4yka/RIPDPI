use std::collections::BTreeMap;
use std::net::SocketAddr;

use ripdpi_config::{DETECT_RECONN, RuntimeConfig};

use crate::runtime_policy::autolearn::normalize_learned_host;
use crate::runtime_policy::matching::group_matches_with_geo;
use crate::runtime_policy::selection::host_preference::select_host_route_after;
use crate::runtime_policy::selection::retry_penalty::select_best_candidate;
use crate::runtime_policy::{ConnectionRoute, GeoMatcher, RetrySelectionPenalty, RuntimePolicy, TransportProtocol};

impl RuntimePolicy {
    #[allow(clippy::too_many_arguments)]
    pub fn select_next(
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
        self.select_next_with_geo(
            config,
            route,
            dest,
            payload,
            host,
            transport,
            trigger,
            can_reconnect,
            retry_penalties,
            None,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn select_next_with_geo(
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
        geo: Option<&dyn GeoMatcher>,
    ) -> Option<ConnectionRoute> {
        if let Some(normalized_host) =
            host.filter(|_| transport == TransportProtocol::Tcp).and_then(normalize_learned_host)
            && let Some(next) = select_host_route_after(
                self,
                config,
                route,
                &normalized_host,
                dest,
                payload,
                transport,
                trigger,
                can_reconnect,
                retry_penalties,
                geo,
            )
        {
            return Some(next);
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
            if group_matches_with_geo(config, group, dest, payload, false, transport, geo) {
                eligible.push(idx);
            } else {
                attempted_mask |= group.bit;
            }
        }
        select_best_candidate(self, &eligible, attempted_mask, retry_penalties)
    }
}
