use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_runtime_decision_ports::TransportProtocol;
use ripdpi_runtime_services::GeoMatcher;

pub fn selected_desync_group(config: &RuntimeConfig, group_index: usize) -> Option<&DesyncGroup> {
    config.groups.get(group_index)
}

pub fn route_requires_delay_payload(config: &RuntimeConfig, group_index: usize) -> Option<bool> {
    selected_desync_group(config, group_index).map(ripdpi_runtime_services::decision_helpers::group_requires_payload)
}

pub fn route_matches_transport_payload(
    config: &RuntimeConfig,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    ripdpi_runtime_services::decision_helpers::route_matches_payload(config, group_index, target, payload, transport)
}

#[derive(Clone)]
pub struct RoutePayloadMatcher {
    config: RuntimeConfig,
    geo: Option<Arc<dyn GeoMatcher + Send + Sync>>,
}

pub fn route_payload_matcher(config: &RuntimeConfig) -> RoutePayloadMatcher {
    route_payload_matcher_with_geo(config, None)
}

pub fn route_payload_matcher_with_geo(
    config: &RuntimeConfig,
    geo: Option<Arc<dyn GeoMatcher + Send + Sync>>,
) -> RoutePayloadMatcher {
    RoutePayloadMatcher { config: config.clone(), geo }
}

pub fn route_matches_transport_payload_with(
    matcher: &RoutePayloadMatcher,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    ripdpi_runtime_services::decision_helpers::route_matches_payload_with_geo(
        &matcher.config,
        group_index,
        target,
        payload,
        transport,
        matcher.geo.as_deref().map(|geo| geo as &dyn GeoMatcher),
    )
}

pub fn route_requires_delay_payload_with(matcher: &RoutePayloadMatcher, group_index: usize) -> Option<bool> {
    route_requires_delay_payload(&matcher.config, group_index)
}

pub fn delayed_route_matches_payload(
    config: &RuntimeConfig,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    host_hint: Option<&str>,
) -> bool {
    if route_matches_transport_payload(config, group_index, target, payload, TransportProtocol::Tcp) {
        return true;
    }

    let Some(host) = host_hint else {
        return false;
    };
    let Some(group) = selected_desync_group(config, group_index) else {
        return false;
    };
    group.matches.filters.hosts_match(host) && crate::protocol_payload::group_accepts_any_or_non_http_tls(group)
}

pub fn delayed_route_matches_payload_with(
    matcher: &RoutePayloadMatcher,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    host_hint: Option<&str>,
) -> bool {
    delayed_route_matches_payload(&matcher.config, group_index, target, payload, host_hint)
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;

    use ripdpi_config::RuntimeConfig;
    use ripdpi_runtime_decision_ports::TransportProtocol;

    use super::*;

    #[test]
    fn route_payload_matcher_preserves_payload_matching() {
        let config = RuntimeConfig::default();
        let matcher = route_payload_matcher(&config);
        let target = SocketAddr::from(([203, 0, 113, 7], 443));

        assert_eq!(
            route_matches_transport_payload_with(
                &matcher,
                0,
                target,
                b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                TransportProtocol::Tcp,
            ),
            route_matches_transport_payload(
                &config,
                0,
                target,
                b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                TransportProtocol::Tcp,
            ),
        );
    }
}
