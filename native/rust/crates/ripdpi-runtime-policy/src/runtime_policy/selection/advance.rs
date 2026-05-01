use std::io;
use std::net::SocketAddr;

use ripdpi_config::{RuntimeConfig, AUTO_NOPOST, AUTO_SORT};

use crate::runtime_policy::autolearn::normalize_learned_host;
use crate::runtime_policy::{ConnectionRoute, RouteAdvance, RuntimePolicy, TransportProtocol};

impl RuntimePolicy {
    pub fn advance_route(
        &mut self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        request: RouteAdvance<'_>,
    ) -> io::Result<Option<ConnectionRoute>> {
        if !request.can_reconnect && (config.adaptive.auto_level & AUTO_NOPOST) != 0 {
            return Ok(None);
        }

        apply_strategy_failure_penalty(self, config, route, &request);

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

        apply_auto_sort(self, config, route, next.as_ref());
        persist_advance_result(self, config, request.dest, request.host, next)
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
            self.store(config, dest, route.group_index, route.attempted_mask, normalized_host.clone());
            if let Some(host) = normalized_host {
                self.note_host_success(config, &host, route.group_index);
            }
        } else {
            self.store(config, dest, route.group_index, route.attempted_mask, host.map(str::to_owned));
        }
        Ok(())
    }
}

fn apply_strategy_failure_penalty(
    policy: &mut RuntimePolicy,
    config: &RuntimeConfig,
    route: &ConnectionRoute,
    request: &RouteAdvance<'_>,
) {
    if request.penalize_strategy_failure {
        if let Some(group) = policy.groups.get_mut(route.group_index) {
            group.fail_count += 1;
        }
    }

    if request.transport == TransportProtocol::Tcp && request.penalize_strategy_failure {
        if let Some(host) = request.host.as_deref() {
            policy.note_host_failure(config, host, route.group_index);
        }
    }
}

fn apply_auto_sort(
    policy: &mut RuntimePolicy,
    config: &RuntimeConfig,
    route: &ConnectionRoute,
    next: Option<&ConnectionRoute>,
) {
    if (config.adaptive.auto_level & AUTO_SORT) == 0 {
        return;
    }

    if let Some(next_route) = next {
        let current_pri = policy.groups.get(route.group_index).map(|group| group.pri).unwrap_or_default();
        let next_pri = policy.groups.get(next_route.group_index).map(|group| group.pri).unwrap_or_default();
        if current_pri > next_pri {
            policy.swap_groups(route.group_index, next_route.group_index);
        }
    }

    if let Some(group) = policy.groups.get_mut(route.group_index) {
        group.pri += 1;
    }
}

fn persist_advance_result(
    policy: &mut RuntimePolicy,
    config: &RuntimeConfig,
    dest: SocketAddr,
    host: Option<String>,
    next: Option<ConnectionRoute>,
) -> io::Result<Option<ConnectionRoute>> {
    match next {
        Some(next_route) => {
            policy.store(config, dest, next_route.group_index, next_route.attempted_mask, host);
            Ok(Some(next_route))
        }
        None => {
            policy.clear(config, dest);
            Ok(None)
        }
    }
}
