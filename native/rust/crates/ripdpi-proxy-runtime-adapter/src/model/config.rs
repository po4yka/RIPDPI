use std::io;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

use ripdpi_runtime_decision_ports::{ConnectionRoute, ExtractedHost, HostSource, TransportProtocol};

pub use ripdpi_config::*;

#[derive(Clone)]
pub struct NetworkReprobeSettings {
    pub enabled: bool,
    pub protect_path: Option<String>,
}

pub fn network_reprobe_settings(config: &RuntimeConfig) -> NetworkReprobeSettings {
    NetworkReprobeSettings {
        enabled: config.host_autolearn.network_reprobe_enabled,
        protect_path: config.process.protect_path.clone(),
    }
}

pub fn udp_flow_limit(config: &RuntimeConfig) -> usize {
    config.network.max_open.max(1) as usize
}

pub fn udp_flow_at_capacity(flow_exists: bool, active_flows: usize, flow_limit: usize) -> bool {
    !flow_exists && active_flows >= flow_limit
}

pub fn listener_bind_addr(config: &RuntimeConfig) -> SocketAddr {
    SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port)
}

pub fn client_capacity(config: &RuntimeConfig) -> usize {
    config.network.max_open.max(1) as usize
}

pub fn max_route_retries(config: &RuntimeConfig) -> usize {
    config.max_route_retries
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TcpRouteRetrySettings {
    pub max_route_retries: usize,
}

pub fn tcp_route_retry_settings(config: &RuntimeConfig) -> TcpRouteRetrySettings {
    TcpRouteRetrySettings { max_route_retries: max_route_retries(config) }
}

pub fn route_group_count(config: &RuntimeConfig) -> usize {
    config.groups.len()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ListenerSettings {
    pub bind_addr: SocketAddr,
    pub client_capacity: usize,
    pub route_group_count: usize,
}

pub fn listener_settings(config: &RuntimeConfig) -> ListenerSettings {
    ListenerSettings {
        bind_addr: listener_bind_addr(config),
        client_capacity: client_capacity(config),
        route_group_count: route_group_count(config),
    }
}

pub fn selected_desync_group(config: &RuntimeConfig, group_index: usize) -> Option<&DesyncGroup> {
    config.groups.get(group_index)
}

pub fn route_requires_delay_payload(config: &RuntimeConfig, group_index: usize) -> Option<bool> {
    selected_desync_group(config, group_index).map(ripdpi_runtime_decision_ports::group_requires_payload)
}

pub fn route_matches_transport_payload(
    config: &RuntimeConfig,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    ripdpi_runtime_decision_ports::route_matches_payload(config, group_index, target, payload, transport)
}

#[derive(Clone)]
pub struct RoutePayloadMatcher {
    config: RuntimeConfig,
}

pub fn route_payload_matcher(config: &RuntimeConfig) -> RoutePayloadMatcher {
    RoutePayloadMatcher { config: config.clone() }
}

pub fn route_matches_transport_payload_with(
    matcher: &RoutePayloadMatcher,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    transport: TransportProtocol,
) -> bool {
    route_matches_transport_payload(&matcher.config, group_index, target, payload, transport)
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

pub fn transparent_proxy_enabled(config: &RuntimeConfig) -> bool {
    config.network.transparent
}

pub fn http_connect_enabled(config: &RuntimeConfig) -> bool {
    config.network.http_connect
}

pub fn shadowsocks_enabled(config: &RuntimeConfig) -> bool {
    config.network.shadowsocks
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProxyProtocolMode {
    Transparent,
    HttpConnect,
    BytePrefixed { shadowsocks_enabled: bool },
}

pub fn proxy_protocol_mode(config: &RuntimeConfig) -> ProxyProtocolMode {
    if transparent_proxy_enabled(config) {
        ProxyProtocolMode::Transparent
    } else if http_connect_enabled(config) {
        ProxyProtocolMode::HttpConnect
    } else {
        ProxyProtocolMode::BytePrefixed { shadowsocks_enabled: shadowsocks_enabled(config) }
    }
}

pub fn udp_associate_enabled(config: &RuntimeConfig) -> bool {
    config.network.udp
}

pub fn delayed_connect_enabled(config: &RuntimeConfig) -> bool {
    config.network.delay_conn
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DelayedConnectSettings {
    pub enabled: bool,
    pub buffer_size: usize,
}

pub fn delayed_connect_settings(config: &RuntimeConfig) -> DelayedConnectSettings {
    DelayedConnectSettings { enabled: delayed_connect_enabled(config), buffer_size: runtime_buffer_size(config) }
}

pub fn ipv6_enabled(config: &RuntimeConfig) -> bool {
    config.network.ipv6
}

pub fn name_resolution_enabled(config: &RuntimeConfig) -> bool {
    config.network.resolve
}

pub fn warmup_probe_enabled(config: &RuntimeConfig) -> bool {
    config.host_autolearn.enabled && config.host_autolearn.warmup_probe_enabled
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WarmupProbeSettings {
    pub scheduler_enabled: bool,
    pub response_buffer_size: usize,
    pub ipv6_enabled: bool,
    pub protect_path: Option<String>,
}

pub fn warmup_probe_settings(config: &RuntimeConfig) -> WarmupProbeSettings {
    WarmupProbeSettings {
        scheduler_enabled: warmup_probe_enabled(config) && route_group_count(config) >= 2,
        response_buffer_size: runtime_buffer_size(config),
        ipv6_enabled: ipv6_enabled(config),
        protect_path: protect_path_owned(config),
    }
}

pub fn host_autolearn_enabled(config: &RuntimeConfig) -> bool {
    config.host_autolearn.enabled
}

pub fn strategy_evolution_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.strategy_evolution
}

#[derive(Clone)]
pub struct ProcessSettings {
    pub daemonize: bool,
    pub pid_file_path: Option<PathBuf>,
}

pub fn process_settings(config: &RuntimeConfig) -> ProcessSettings {
    ProcessSettings {
        daemonize: config.process.daemonize,
        pid_file_path: config.process.pid_file.as_deref().map(PathBuf::from),
    }
}

pub fn proxy_auth_token(config: &RuntimeConfig) -> Option<&str> {
    config.network.listen.auth_token.as_deref()
}

pub fn proxy_session_config(config: &RuntimeConfig) -> ripdpi_session::SessionConfig {
    ripdpi_session::SessionConfig { resolve: config.network.resolve, ipv6: config.network.ipv6 }
}

#[derive(Clone)]
pub struct ProxyHandshakeSettings {
    pub protocol_mode: ProxyProtocolMode,
    pub auth_token: Option<String>,
    pub session_config: ripdpi_session::SessionConfig,
    pub shadowsocks_target_policy: ShadowsocksTargetPolicy,
    pub udp_associate_enabled: bool,
    pub protect_path: Option<String>,
}

pub fn proxy_handshake_settings(config: &RuntimeConfig) -> ProxyHandshakeSettings {
    ProxyHandshakeSettings {
        protocol_mode: proxy_protocol_mode(config),
        auth_token: proxy_auth_token(config).map(ToOwned::to_owned),
        session_config: proxy_session_config(config),
        shadowsocks_target_policy: shadowsocks_target_policy(config),
        udp_associate_enabled: udp_associate_enabled(config),
        protect_path: protect_path_owned(config),
    }
}

pub fn protect_path(config: &RuntimeConfig) -> Option<&str> {
    config.process.protect_path.as_deref()
}

pub fn protect_path_owned(config: &RuntimeConfig) -> Option<String> {
    config.process.protect_path.clone()
}

pub fn connect_timeout(config: &RuntimeConfig) -> Option<Duration> {
    (config.timeouts.connect_timeout_ms > 0).then(|| Duration::from_millis(config.timeouts.connect_timeout_ms as u64))
}

pub fn tcp_fast_open_enabled(config: &RuntimeConfig) -> bool {
    config.network.tfo
}

pub fn group_uses_direct_syn_data_tfo(group: &DesyncGroup) -> bool {
    group.policy.ext_socks.is_none()
        && group.actions.tcp_chain.iter().any(|step| step.kind() == TcpChainStepKind::SynData)
}

pub fn group_requests_direct_syn_data_tfo(group: &DesyncGroup, payload: Option<&[u8]>) -> bool {
    payload.is_some_and(|bytes| !bytes.is_empty()) && group_uses_direct_syn_data_tfo(group)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TcpRouteSynDataSettings {
    direct_syn_data_groups: Vec<bool>,
}

pub fn tcp_route_syn_data_settings(config: &RuntimeConfig) -> TcpRouteSynDataSettings {
    TcpRouteSynDataSettings {
        direct_syn_data_groups: config.groups.iter().map(group_uses_direct_syn_data_tfo).collect(),
    }
}

pub fn connection_route_requests_direct_syn_data_tfo_with(
    settings: &TcpRouteSynDataSettings,
    route: &ConnectionRoute,
    payload: Option<&[u8]>,
) -> bool {
    payload.is_some_and(|bytes| !bytes.is_empty())
        && settings.direct_syn_data_groups.get(route.group_index).copied().unwrap_or(false)
}

#[derive(Clone)]
pub struct TcpRouteConnectSettings {
    pub tfo_enabled: bool,
    pub upstream_socks_addr: Option<SocketAddr>,
    pub pre_connect_rcvbuf: Option<u32>,
    pub connect_timeout: Option<Duration>,
    pub protect_path: Option<String>,
    pub drop_sack: bool,
    pub window_clamp: Option<u32>,
    pub strip_timestamps: bool,
}

#[derive(Clone)]
pub struct TcpRouteConnectProfile {
    pub tfo_enabled: bool,
    pub direct_syn_data_tfo: bool,
    pub upstream_socks_addr: Option<SocketAddr>,
    pub pre_connect_rcvbuf: Option<u32>,
    pub connect_timeout: Option<Duration>,
    pub protect_path: Option<String>,
    pub drop_sack: bool,
    pub window_clamp: Option<u32>,
    pub strip_timestamps: bool,
}

#[derive(Clone)]
pub struct TcpRouteConnectSettingsTable {
    groups: Vec<TcpRouteConnectProfile>,
}

pub fn tcp_route_connect_settings_table(config: &RuntimeConfig) -> TcpRouteConnectSettingsTable {
    TcpRouteConnectSettingsTable {
        groups: config.groups.iter().map(|group| tcp_route_connect_profile(config, group)).collect(),
    }
}

fn tcp_route_connect_profile(config: &RuntimeConfig, group: &DesyncGroup) -> TcpRouteConnectProfile {
    let pre_connect_rcvbuf = group.actions.wsize.map(|w| match w.scale {
        Some(scale) if (scale as u32) < 32 => w.window.checked_shl(scale as u32).unwrap_or(u32::MAX),
        Some(_) => u32::MAX,
        None => w.window,
    });
    TcpRouteConnectProfile {
        tfo_enabled: tcp_fast_open_enabled(config),
        direct_syn_data_tfo: group_uses_direct_syn_data_tfo(group),
        upstream_socks_addr: group.policy.ext_socks.map(|upstream| upstream.addr),
        pre_connect_rcvbuf,
        connect_timeout: connect_timeout(config),
        protect_path: protect_path_owned(config),
        drop_sack: group.actions.drop_sack,
        window_clamp: group.actions.wsize.map(|w| w.window).or(group.actions.window_clamp),
        strip_timestamps: group.actions.strip_timestamps,
    }
}

pub fn tcp_route_connect_settings_with(
    table: &TcpRouteConnectSettingsTable,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> Option<TcpRouteConnectSettings> {
    let profile = table.groups.get(group_index)?;
    let tfo_enabled = allow_tfo
        && (profile.tfo_enabled || (payload.is_some_and(|bytes| !bytes.is_empty()) && profile.direct_syn_data_tfo));
    Some(TcpRouteConnectSettings {
        tfo_enabled,
        upstream_socks_addr: profile.upstream_socks_addr,
        pre_connect_rcvbuf: profile.pre_connect_rcvbuf,
        connect_timeout: profile.connect_timeout,
        protect_path: profile.protect_path.clone(),
        drop_sack: profile.drop_sack,
        window_clamp: profile.window_clamp,
        strip_timestamps: profile.strip_timestamps,
    })
}

pub fn tcp_route_connect_settings(
    config: &RuntimeConfig,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> Option<TcpRouteConnectSettings> {
    tcp_route_connect_settings_with(&tcp_route_connect_settings_table(config), group_index, payload, allow_tfo)
}

pub fn route_requests_direct_syn_data_tfo(config: &RuntimeConfig, group_index: usize, payload: Option<&[u8]>) -> bool {
    selected_desync_group(config, group_index).is_some_and(|group| group_requests_direct_syn_data_tfo(group, payload))
}

pub fn connection_route_requests_direct_syn_data_tfo(
    config: &RuntimeConfig,
    route: &ConnectionRoute,
    payload: Option<&[u8]>,
) -> bool {
    connection_route_requests_direct_syn_data_tfo_with(&tcp_route_syn_data_settings(config), route, payload)
}

pub fn ws_tunnel_always_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.ws_tunnel_mode == WsTunnelMode::Always
}

pub fn ws_tunnel_fallback_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.ws_tunnel_mode == WsTunnelMode::Fallback
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WsTunnelSettings {
    pub always_enabled: bool,
    pub fallback_enabled: bool,
    pub protect_path: Option<String>,
    pub connect_timeout: Option<Duration>,
    pub fake_sni: Option<String>,
}

pub fn ws_tunnel_settings(config: &RuntimeConfig) -> WsTunnelSettings {
    WsTunnelSettings {
        always_enabled: ws_tunnel_always_enabled(config),
        fallback_enabled: ws_tunnel_fallback_enabled(config),
        protect_path: protect_path_owned(config),
        connect_timeout: connect_timeout(config),
        fake_sni: config.adaptive.ws_tunnel_fake_sni.clone(),
    }
}

pub fn ws_tunnel_config(
    config: &RuntimeConfig,
    resolved_addr: Option<SocketAddr>,
) -> ripdpi_ws_bootstrap::WsTunnelConfig {
    ws_tunnel_config_with(&ws_tunnel_settings(config), resolved_addr)
}

pub fn ws_tunnel_config_with(
    settings: &WsTunnelSettings,
    resolved_addr: Option<SocketAddr>,
) -> ripdpi_ws_bootstrap::WsTunnelConfig {
    ripdpi_ws_bootstrap::WsTunnelConfig {
        protect_path: settings.protect_path.clone(),
        resolved_addr,
        connect_timeout: settings.connect_timeout,
        fake_sni: settings.fake_sni.clone(),
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResponseFailureEvidenceSettings {
    pub protect_path: Option<String>,
}

pub fn response_failure_evidence_settings(config: &RuntimeConfig) -> ResponseFailureEvidenceSettings {
    ResponseFailureEvidenceSettings { protect_path: protect_path_owned(config) }
}

#[derive(Clone, Copy)]
pub struct FirstResponseSettings {
    pub buffer_size: usize,
    pub partial_timeout_ms: u32,
    pub timeout_ms: u32,
    pub timeout_count_limit: i32,
    pub timeout_bytes_limit: i32,
    pub fallback_timeout_required: bool,
}

pub fn first_response_settings(config: &RuntimeConfig) -> FirstResponseSettings {
    FirstResponseSettings {
        buffer_size: config.network.buffer_size.max(16_384),
        partial_timeout_ms: config.timeouts.partial_timeout_ms,
        timeout_ms: config.timeouts.timeout_ms,
        timeout_count_limit: config.timeouts.timeout_count_limit.max(1),
        timeout_bytes_limit: config.timeouts.timeout_bytes_limit,
        fallback_timeout_required: config.groups.iter().any(|group| {
            [DETECT_HTTP_LOCAT, DETECT_HTTP_BLOCKPAGE, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TLS_ALERT, DETECT_TORST]
                .iter()
                .any(|flag| group.matches.detect & *flag != 0)
        }),
    }
}

pub fn first_response_timeout(settings: FirstResponseSettings, tls_partial_active: bool) -> Option<Duration> {
    if tls_partial_active {
        Some(Duration::from_millis(settings.partial_timeout_ms as u64))
    } else if settings.timeout_ms != 0 {
        Some(Duration::from_millis(settings.timeout_ms as u64))
    } else if settings.fallback_timeout_required {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

pub fn first_response_timeout_count_limit(settings: FirstResponseSettings) -> i32 {
    settings.timeout_count_limit
}

pub fn first_response_bytes_limit(settings: FirstResponseSettings, default_limit: usize) -> usize {
    match usize::try_from(settings.timeout_bytes_limit) {
        Ok(limit) if limit != 0 => limit,
        _ => default_limit,
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UdpSourceRebindPolicy {
    pub after_handshake: bool,
}

pub fn udp_source_rebind_policy(config: &RuntimeConfig, group_index: usize) -> UdpSourceRebindPolicy {
    UdpSourceRebindPolicy {
        after_handshake: config.groups.get(group_index).is_some_and(|group| group.actions.quic_migrate_after_handshake),
    }
}

pub fn should_rebind_udp_source_port_with(
    policy: UdpSourceRebindPolicy,
    quic_migrated: bool,
    round_count: u32,
    inbound_payload: &[u8],
) -> bool {
    !quic_migrated
        && inbound_payload.first().is_some_and(|first| first & 0x80 == 0)
        && round_count >= 2
        && policy.after_handshake
}

pub fn should_rebind_udp_source_port(
    config: &RuntimeConfig,
    group_index: usize,
    quic_migrated: bool,
    round_count: u32,
    inbound_payload: &[u8],
) -> bool {
    should_rebind_udp_source_port_with(
        udp_source_rebind_policy(config, group_index),
        quic_migrated,
        round_count,
        inbound_payload,
    )
}

#[derive(Clone, Copy)]
pub struct UdpGroupSocketSettings {
    pub bind_low_port: bool,
}

pub fn udp_group_socket_settings(config: &RuntimeConfig, group_index: usize) -> UdpGroupSocketSettings {
    UdpGroupSocketSettings { bind_low_port: udp_bind_low_port(config, group_index) }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UdpGroupPacketSettings {
    pub default_ttl: u8,
    pub ip_id_mode: Option<IpIdMode>,
}

pub fn udp_group_packet_settings(config: &RuntimeConfig, group_index: usize) -> UdpGroupPacketSettings {
    UdpGroupPacketSettings { default_ttl: udp_default_ttl(config), ip_id_mode: udp_ip_id_mode(config, group_index) }
}

#[derive(Clone, Copy)]
pub struct UdpGroupSettings {
    pub socket: UdpGroupSocketSettings,
    pub packet: UdpGroupPacketSettings,
    pub source_rebind: UdpSourceRebindPolicy,
}

#[derive(Clone)]
pub struct UdpGroupSettingsTable {
    groups: Vec<UdpGroupSettings>,
}

pub fn udp_group_settings_table(config: &RuntimeConfig) -> UdpGroupSettingsTable {
    UdpGroupSettingsTable {
        groups: config
            .groups
            .iter()
            .enumerate()
            .map(|(group_index, _)| UdpGroupSettings {
                socket: udp_group_socket_settings(config, group_index),
                packet: udp_group_packet_settings(config, group_index),
                source_rebind: udp_source_rebind_policy(config, group_index),
            })
            .collect(),
    }
}

pub fn udp_group_settings_with(table: &UdpGroupSettingsTable, group_index: usize) -> Option<UdpGroupSettings> {
    table.groups.get(group_index).copied()
}

pub fn udp_bind_low_port(config: &RuntimeConfig, group_index: usize) -> bool {
    config.groups.get(group_index).is_some_and(|group| group.actions.quic_bind_low_port)
}

pub fn udp_ip_id_mode(config: &RuntimeConfig, group_index: usize) -> Option<IpIdMode> {
    config.groups.get(group_index).and_then(|group| group.actions.ip_id_mode)
}

pub fn udp_default_ttl(config: &RuntimeConfig) -> u8 {
    config.network.default_ttl
}

pub fn ensure_default_ttl(
    config: &mut RuntimeConfig,
    detect_default_ttl: impl FnOnce() -> io::Result<u8>,
) -> io::Result<()> {
    if config.network.default_ttl == 0 {
        config.network.default_ttl = detect_default_ttl()?;
    }
    Ok(())
}

pub fn quic_route_and_cache_enabled(config: &RuntimeConfig) -> bool {
    matches!(config.quic.initial_mode, QuicInitialMode::RouteAndCache)
}

pub fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => quic_route_and_cache_enabled(config),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

pub fn runtime_buffer_size(config: &RuntimeConfig) -> usize {
    config.network.buffer_size.max(16_384)
}

pub fn relay_timeout_settings(config: &RuntimeConfig) -> RuntimeTimeoutSettings {
    config.timeouts
}

pub fn group_drop_sack_enabled(config: &RuntimeConfig, group_index: usize) -> Option<bool> {
    selected_desync_group(config, group_index).map(|group| group.actions.drop_sack)
}

pub fn group_rotation_policy_enabled(config: &RuntimeConfig, group_index: usize) -> bool {
    selected_desync_group(config, group_index).is_some_and(|group| group.actions.rotation_policy.is_some())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RelayGroupSettings {
    pub drop_sack: bool,
    pub rotation_enabled: bool,
    pub timeouts: RuntimeTimeoutSettings,
}

pub fn relay_group_settings(config: &RuntimeConfig, group_index: usize) -> Option<RelayGroupSettings> {
    let group = selected_desync_group(config, group_index)?;
    Some(RelayGroupSettings {
        drop_sack: group.actions.drop_sack,
        rotation_enabled: group.actions.rotation_policy.is_some(),
        timeouts: relay_timeout_settings(config),
    })
}

#[derive(Clone)]
pub struct RelayGroupSettingsTable {
    groups: Vec<RelayGroupSettings>,
    rotation_seeds: Vec<Option<(DesyncGroup, RotationPolicy)>>,
    primary_strategy_families: Vec<Option<&'static str>>,
}

pub fn relay_group_settings_table(config: &RuntimeConfig) -> RelayGroupSettingsTable {
    RelayGroupSettingsTable {
        groups: config
            .groups
            .iter()
            .map(|group| RelayGroupSettings {
                drop_sack: group.actions.drop_sack,
                rotation_enabled: group.actions.rotation_policy.is_some(),
                timeouts: relay_timeout_settings(config),
            })
            .collect(),
        rotation_seeds: config
            .groups
            .iter()
            .map(|group| group.actions.rotation_policy.clone().map(|policy| (group.clone(), policy)))
            .collect(),
        primary_strategy_families: config
            .groups
            .iter()
            .map(ripdpi_desync_runtime::primary_tcp_strategy_family)
            .collect(),
    }
}

pub fn relay_group_settings_with(table: &RelayGroupSettingsTable, group_index: usize) -> Option<RelayGroupSettings> {
    table.groups.get(group_index).copied()
}

pub fn tcp_rotation_seed_with(
    table: &RelayGroupSettingsTable,
    group_index: usize,
) -> io::Result<Option<(DesyncGroup, RotationPolicy)>> {
    table
        .rotation_seeds
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
}

pub fn primary_tcp_strategy_family_with(table: &RelayGroupSettingsTable, group_index: usize) -> Option<&'static str> {
    table.primary_strategy_families.get(group_index).copied().flatten()
}

pub fn tcp_rotation_seed(
    config: &RuntimeConfig,
    group_index: usize,
) -> io::Result<Option<(DesyncGroup, RotationPolicy)>> {
    tcp_rotation_seed_with(&relay_group_settings_table(config), group_index)
}

pub fn primary_tcp_strategy_family_for_group(config: &RuntimeConfig, group_index: usize) -> Option<&'static str> {
    primary_tcp_strategy_family_with(&relay_group_settings_table(config), group_index)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ShadowsocksTargetPolicy {
    pub ipv6_enabled: bool,
    pub resolve_enabled: bool,
}

pub fn shadowsocks_target_policy(config: &RuntimeConfig) -> ShadowsocksTargetPolicy {
    ShadowsocksTargetPolicy { ipv6_enabled: config.network.ipv6, resolve_enabled: config.network.resolve }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn direct_syn_data_tfo_requires_payload_and_direct_upstream() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));

        assert!(group_uses_direct_syn_data_tfo(&group));
        assert!(group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
        assert!(!group_requests_direct_syn_data_tfo(&group, None));
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(&[])));

        group.policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::from(([127, 0, 0, 1], 1080)) });
        assert!(!group_uses_direct_syn_data_tfo(&group));
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
    }

    #[test]
    fn connection_route_direct_syn_data_tfo_uses_route_group() {
        let mut direct = DesyncGroup::new(0);
        direct.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
        let plain = DesyncGroup::new(1);
        let config = RuntimeConfig { groups: vec![direct, plain], ..Default::default() };

        let direct_route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let plain_route = ConnectionRoute { group_index: 1, attempted_mask: 0 };

        assert!(
            connection_route_requests_direct_syn_data_tfo(&config, &direct_route, Some(b"GET / HTTP/1.1\r\n\r\n"),)
        );
        assert!(
            !connection_route_requests_direct_syn_data_tfo(&config, &plain_route, Some(b"GET / HTTP/1.1\r\n\r\n"),)
        );
    }

    #[test]
    fn projected_syn_data_settings_preserve_payload_and_route_group_policy() {
        let mut direct = DesyncGroup::new(0);
        direct.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
        let plain = DesyncGroup::new(1);
        let config = RuntimeConfig { groups: vec![direct, plain], ..Default::default() };
        let settings = tcp_route_syn_data_settings(&config);

        let direct_route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let plain_route = ConnectionRoute { group_index: 1, attempted_mask: 0 };

        assert!(connection_route_requests_direct_syn_data_tfo_with(
            &settings,
            &direct_route,
            Some(b"GET / HTTP/1.1\r\n\r\n"),
        ));
        assert!(!connection_route_requests_direct_syn_data_tfo_with(&settings, &direct_route, Some(&[])));
        assert!(!connection_route_requests_direct_syn_data_tfo_with(
            &settings,
            &plain_route,
            Some(b"GET / HTTP/1.1\r\n\r\n"),
        ));
    }

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

    #[test]
    fn tcp_route_connect_settings_project_socket_context() {
        let mut config = RuntimeConfig::default();
        config.timeouts.connect_timeout_ms = 1500;
        config.process.protect_path = Some("/tmp/protect.sock".to_string());
        config.groups[0].actions.drop_sack = true;

        let settings = tcp_route_connect_settings(&config, 0, None, true).expect("connect settings");

        assert_eq!(settings.connect_timeout, Some(Duration::from_millis(1500)));
        assert_eq!(settings.protect_path.as_deref(), Some("/tmp/protect.sock"));
        assert!(settings.drop_sack);
    }

    #[test]
    fn tcp_route_connect_settings_table_preserves_tfo_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.network.tfo = false;
        let table = tcp_route_connect_settings_table(&config);

        let without_payload = tcp_route_connect_settings_with(&table, 0, None, true).expect("connect settings");
        let with_payload = tcp_route_connect_settings_with(&table, 0, Some(b"GET / HTTP/1.1\r\n\r\n"), true)
            .expect("connect settings");
        let tfo_disallowed = tcp_route_connect_settings_with(&table, 0, Some(b"GET / HTTP/1.1\r\n\r\n"), false)
            .expect("connect settings");

        assert!(!without_payload.tfo_enabled);
        assert!(with_payload.tfo_enabled);
        assert!(!tfo_disallowed.tfo_enabled);
        assert!(tcp_route_connect_settings_with(&table, 1, Some(b"x"), true).is_none());
    }

    #[test]
    fn relay_group_settings_project_drop_sack_rotation_and_timeouts() {
        let mut group = DesyncGroup::new(0);
        group.actions.drop_sack = true;
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.timeouts.freeze_max_stalls = 7;

        let settings = relay_group_settings(&config, 0).expect("relay group settings");

        assert!(settings.drop_sack);
        assert!(!settings.rotation_enabled);
        assert_eq!(settings.timeouts.freeze_max_stalls, 7);
        assert!(relay_group_settings(&config, 1).is_none());
    }

    #[test]
    fn relay_group_settings_table_preserves_group_and_rotation_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.drop_sack = true;
        group.actions.rotation_policy = Some(RotationPolicy::default());
        let config = RuntimeConfig { groups: vec![group], ..Default::default() };
        let table = relay_group_settings_table(&config);

        let settings = relay_group_settings_with(&table, 0).expect("relay group settings");
        let seed = tcp_rotation_seed_with(&table, 0).expect("rotation lookup");

        assert!(settings.drop_sack);
        assert!(settings.rotation_enabled);
        assert!(seed.is_some());
        assert_eq!(primary_tcp_strategy_family_with(&table, 0), primary_tcp_strategy_family_for_group(&config, 0));
        assert!(relay_group_settings_with(&table, 1).is_none());
        assert!(tcp_rotation_seed_with(&table, 1).is_err());
        assert!(primary_tcp_strategy_family_with(&table, 1).is_none());
    }

    #[test]
    fn response_failure_evidence_settings_project_protect_path() {
        let mut config = RuntimeConfig::default();
        config.process.protect_path = Some("/tmp/protect.sock".to_string());

        assert_eq!(
            response_failure_evidence_settings(&config),
            ResponseFailureEvidenceSettings { protect_path: Some("/tmp/protect.sock".to_string()) },
        );
    }

    #[test]
    fn proxy_protocol_mode_prefers_listener_level_modes_before_byte_prefixed_protocols() {
        let mut config = RuntimeConfig::default();
        config.network.shadowsocks = true;
        assert_eq!(proxy_protocol_mode(&config), ProxyProtocolMode::BytePrefixed { shadowsocks_enabled: true });

        config.network.http_connect = true;
        assert_eq!(proxy_protocol_mode(&config), ProxyProtocolMode::HttpConnect);

        config.network.transparent = true;
        assert_eq!(proxy_protocol_mode(&config), ProxyProtocolMode::Transparent);
    }

    #[test]
    fn proxy_handshake_settings_project_protocol_session_udp_and_protect_policy() {
        let mut config = RuntimeConfig::default();
        config.network.shadowsocks = true;
        config.network.udp = true;
        config.network.resolve = false;
        config.network.ipv6 = true;
        config.network.listen.auth_token = Some("secret".to_string());
        config.process.protect_path = Some("/tmp/protect.sock".to_string());

        let settings = proxy_handshake_settings(&config);

        assert_eq!(settings.protocol_mode, ProxyProtocolMode::BytePrefixed { shadowsocks_enabled: true },);
        assert_eq!(settings.auth_token.as_deref(), Some("secret"));
        assert!(!settings.session_config.resolve);
        assert!(settings.session_config.ipv6);
        assert_eq!(
            settings.shadowsocks_target_policy,
            ShadowsocksTargetPolicy { ipv6_enabled: true, resolve_enabled: false },
        );
        assert!(settings.udp_associate_enabled);
        assert_eq!(settings.protect_path.as_deref(), Some("/tmp/protect.sock"));
    }

    #[test]
    fn udp_flow_capacity_only_rejects_new_flows_at_limit() {
        assert!(!udp_flow_at_capacity(true, 2, 2));
        assert!(udp_flow_at_capacity(false, 2, 2));
        assert!(!udp_flow_at_capacity(false, 1, 2));
    }

    #[test]
    fn udp_group_socket_settings_project_bind_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_bind_low_port = true;
        let config = RuntimeConfig { groups: vec![group], ..Default::default() };

        assert!(udp_group_socket_settings(&config, 0).bind_low_port);
        assert!(!udp_group_socket_settings(&config, 1).bind_low_port);
    }

    #[test]
    fn udp_group_settings_table_preserves_udp_group_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_bind_low_port = true;
        group.actions.quic_migrate_after_handshake = true;
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.network.default_ttl = 42;

        let table = udp_group_settings_table(&config);
        let settings = udp_group_settings_with(&table, 0).expect("udp group settings");

        assert!(settings.socket.bind_low_port);
        assert_eq!(settings.packet.default_ttl, 42);
        assert!(settings.source_rebind.after_handshake);
        assert!(udp_group_settings_with(&table, 1).is_none());
    }

    #[test]
    fn udp_source_rebind_policy_projects_quic_migration_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_migrate_after_handshake = true;
        let config = RuntimeConfig { groups: vec![group], ..Default::default() };

        assert_eq!(udp_source_rebind_policy(&config, 0), UdpSourceRebindPolicy { after_handshake: true });
        assert_eq!(udp_source_rebind_policy(&config, 1), UdpSourceRebindPolicy { after_handshake: false });
    }

    #[test]
    fn udp_source_rebind_policy_waits_for_short_header_after_two_rounds() {
        let policy = UdpSourceRebindPolicy { after_handshake: true };

        assert!(!should_rebind_udp_source_port_with(policy, true, 2, &[0x40]));
        assert!(!should_rebind_udp_source_port_with(policy, false, 1, &[0x40]));
        assert!(!should_rebind_udp_source_port_with(policy, false, 2, &[0xc0]));
        assert!(!should_rebind_udp_source_port_with(
            UdpSourceRebindPolicy { after_handshake: false },
            false,
            2,
            &[0x40],
        ));
        assert!(should_rebind_udp_source_port_with(policy, false, 2, &[0x40]));
    }

    #[test]
    fn udp_group_packet_settings_project_ttl_and_ip_id_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.ip_id_mode = Some(IpIdMode::Seq);
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.network.default_ttl = 42;

        assert_eq!(
            udp_group_packet_settings(&config, 0),
            UdpGroupPacketSettings { default_ttl: 42, ip_id_mode: Some(IpIdMode::Seq) },
        );
        assert_eq!(udp_group_packet_settings(&config, 1), UdpGroupPacketSettings { default_ttl: 42, ip_id_mode: None });
    }

    #[test]
    fn tcp_route_retry_settings_project_retry_limit() {
        let mut config = RuntimeConfig { max_route_retries: 3, ..Default::default() };

        assert_eq!(tcp_route_retry_settings(&config), TcpRouteRetrySettings { max_route_retries: 3 });

        config.max_route_retries = 0;
        assert_eq!(tcp_route_retry_settings(&config), TcpRouteRetrySettings { max_route_retries: 0 });
    }

    #[test]
    fn listener_settings_project_bind_capacity_and_route_count() {
        let mut config = RuntimeConfig::default();
        config.network.max_open = 0;
        config.groups = vec![DesyncGroup::new(0), DesyncGroup::new(1)];

        assert_eq!(
            listener_settings(&config),
            ListenerSettings {
                bind_addr: SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port),
                client_capacity: 1,
                route_group_count: 2,
            },
        );
    }

    #[test]
    fn delayed_connect_settings_project_enablement_and_buffer_size() {
        let mut config = RuntimeConfig::default();
        config.network.delay_conn = true;
        config.network.buffer_size = 512;

        assert_eq!(delayed_connect_settings(&config), DelayedConnectSettings { enabled: true, buffer_size: 16_384 },);
    }

    #[test]
    fn warmup_probe_settings_require_enablement_and_fallback_group() {
        let mut config = RuntimeConfig::default();
        config.host_autolearn.enabled = true;
        config.host_autolearn.warmup_probe_enabled = true;
        config.network.buffer_size = 512;
        config.network.ipv6 = true;
        config.process.protect_path = Some("/tmp/protect.sock".to_string());
        config.groups = vec![DesyncGroup::new(0)];

        assert_eq!(
            warmup_probe_settings(&config),
            WarmupProbeSettings {
                scheduler_enabled: false,
                response_buffer_size: 16_384,
                ipv6_enabled: true,
                protect_path: Some("/tmp/protect.sock".to_string()),
            },
        );

        config.groups.push(DesyncGroup::new(1));
        assert_eq!(
            warmup_probe_settings(&config),
            WarmupProbeSettings {
                scheduler_enabled: true,
                response_buffer_size: 16_384,
                ipv6_enabled: true,
                protect_path: Some("/tmp/protect.sock".to_string()),
            },
        );
    }
}
