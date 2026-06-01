pub(super) use ripdpi_proxy_runtime_adapter::model::config::{
    DETECT_CONNECT, DelayedConnectSettings, FirstResponseSettings, ListenerSettings, NetworkReprobeSettings,
    ProxyHandshakeSettings, ProxyProtocolMode, RelayGroupSettingsTable, ResponseFailureEvidenceSettings,
    RoutePayloadMatcher, RuntimeConfig, TcpRouteConnectSettingsTable, TcpRouteRetrySettings, TcpRouteSynDataSettings,
    UdpGroupSettingsTable, WarmupProbeSettings, WsTunnelSettings, connection_route_requests_direct_syn_data_tfo_with,
    delayed_route_matches_payload_with, ensure_default_ttl, first_response_timeout, first_response_timeout_count_limit,
    listener_settings, primary_tcp_strategy_family_with, relay_group_settings_with,
    route_matches_transport_payload_with, route_requires_delay_payload_with, should_rebind_udp_source_port_with,
    tcp_rotation_seed_with, tcp_route_connect_settings_table, tcp_route_connect_settings_with, udp_flow_at_capacity,
    udp_group_settings_with,
};
#[cfg(all(test, not(feature = "loom")))]
pub(super) use ripdpi_proxy_runtime_adapter::model::config::{
    DETECT_HTTP_LOCAT, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST, WsTunnelMode, first_response_settings,
};
use ripdpi_proxy_runtime_adapter::model::services::GeoMatcher;
use std::sync::Arc;

use ripdpi_proxy_runtime_adapter::model::config::{
    delayed_connect_settings, first_response_settings as projected_first_response_settings, network_reprobe_settings,
    proxy_handshake_settings, relay_group_settings_table, response_failure_evidence_settings,
    route_payload_matcher_with_geo, tcp_route_retry_settings, tcp_route_syn_data_settings, udp_flow_limit,
    udp_group_settings_table, warmup_probe_settings, ws_tunnel_settings,
};

pub(super) struct RuntimeConfigProjection {
    pub(super) listener_settings: ListenerSettings,
    pub(super) handshake_settings: ProxyHandshakeSettings,
    pub(super) delayed_connect_settings: DelayedConnectSettings,
    pub(super) network_reprobe_settings: NetworkReprobeSettings,
    pub(super) ws_tunnel_settings: WsTunnelSettings,
    pub(super) warmup_probe_settings: WarmupProbeSettings,
    pub(super) route_retry_settings: TcpRouteRetrySettings,
    pub(super) route_syn_data_settings: TcpRouteSynDataSettings,
    pub(super) route_connect_settings: TcpRouteConnectSettingsTable,
    pub(super) udp_group_settings: UdpGroupSettingsTable,
    pub(super) route_payload_matcher: RoutePayloadMatcher,
    pub(super) udp_flow_limit: usize,
    pub(super) relay_group_settings: RelayGroupSettingsTable,
    pub(super) relay_first_response: FirstResponseSettings,
    pub(super) response_failure_evidence_settings: ResponseFailureEvidenceSettings,
}

pub(super) fn runtime_config_projection_with_geo(
    config: &RuntimeConfig,
    geo: Option<Arc<dyn GeoMatcher + Send + Sync>>,
) -> RuntimeConfigProjection {
    RuntimeConfigProjection {
        listener_settings: listener_settings(config),
        handshake_settings: proxy_handshake_settings(config),
        delayed_connect_settings: delayed_connect_settings(config),
        network_reprobe_settings: network_reprobe_settings(config),
        ws_tunnel_settings: ws_tunnel_settings(config),
        warmup_probe_settings: warmup_probe_settings(config),
        route_retry_settings: tcp_route_retry_settings(config),
        route_syn_data_settings: tcp_route_syn_data_settings(config),
        route_connect_settings: tcp_route_connect_settings_table(config),
        udp_group_settings: udp_group_settings_table(config),
        route_payload_matcher: route_payload_matcher_with_geo(config, geo),
        udp_flow_limit: udp_flow_limit(config),
        relay_group_settings: relay_group_settings_table(config),
        relay_first_response: projected_first_response_settings(config),
        response_failure_evidence_settings: response_failure_evidence_settings(config),
    }
}
