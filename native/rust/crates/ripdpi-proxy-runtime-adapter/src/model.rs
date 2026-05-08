pub mod config {
    use std::io;
    use std::net::SocketAddr;
    use std::path::PathBuf;
    use std::time::Duration;

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
        selected_desync_group(config, group_index).map(ripdpi_runtime_decision_ports::policy::group_requires_payload)
    }

    pub fn route_matches_transport_payload(
        config: &RuntimeConfig,
        group_index: usize,
        target: SocketAddr,
        payload: &[u8],
        transport: ripdpi_runtime_decision_ports::policy::TransportProtocol,
    ) -> bool {
        ripdpi_runtime_decision_ports::policy::route_matches_payload(config, group_index, target, payload, transport)
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
        transport: ripdpi_runtime_decision_ports::policy::TransportProtocol,
    ) -> bool {
        route_matches_transport_payload(&matcher.config, group_index, target, payload, transport)
    }

    pub fn delayed_route_matches_payload(
        config: &RuntimeConfig,
        group_index: usize,
        target: SocketAddr,
        payload: &[u8],
        host_hint: Option<&str>,
    ) -> bool {
        if route_matches_transport_payload(
            config,
            group_index,
            target,
            payload,
            ripdpi_runtime_decision_ports::policy::TransportProtocol::Tcp,
        ) {
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
        (config.timeouts.connect_timeout_ms > 0)
            .then(|| Duration::from_millis(config.timeouts.connect_timeout_ms as u64))
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
        route: &ripdpi_runtime_decision_ports::policy::ConnectionRoute,
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

    pub fn route_requests_direct_syn_data_tfo(
        config: &RuntimeConfig,
        group_index: usize,
        payload: Option<&[u8]>,
    ) -> bool {
        selected_desync_group(config, group_index)
            .is_some_and(|group| group_requests_direct_syn_data_tfo(group, payload))
    }

    pub fn connection_route_requests_direct_syn_data_tfo(
        config: &RuntimeConfig,
        route: &ripdpi_runtime_decision_ports::policy::ConnectionRoute,
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

    pub fn ws_tunnel_config(
        config: &RuntimeConfig,
        resolved_addr: Option<SocketAddr>,
    ) -> ripdpi_ws_bootstrap::WsTunnelConfig {
        ripdpi_ws_bootstrap::WsTunnelConfig {
            protect_path: protect_path_owned(config),
            resolved_addr,
            connect_timeout: connect_timeout(config),
            fake_sni: config.adaptive.ws_tunnel_fake_sni.clone(),
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
            after_handshake: config
                .groups
                .get(group_index)
                .is_some_and(|group| group.actions.quic_migrate_after_handshake),
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

    pub fn should_cache_udp_host(
        config: &RuntimeConfig,
        host: Option<&ripdpi_runtime_decision_ports::policy::ExtractedHost>,
    ) -> bool {
        use ripdpi_runtime_decision_ports::policy::HostSource;

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

    pub fn relay_group_settings_with(
        table: &RelayGroupSettingsTable,
        group_index: usize,
    ) -> Option<RelayGroupSettings> {
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

    pub fn primary_tcp_strategy_family_with(
        table: &RelayGroupSettingsTable,
        group_index: usize,
    ) -> Option<&'static str> {
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

            let direct_route =
                ripdpi_runtime_decision_ports::policy::ConnectionRoute { group_index: 0, attempted_mask: 0 };
            let plain_route =
                ripdpi_runtime_decision_ports::policy::ConnectionRoute { group_index: 1, attempted_mask: 0 };

            assert!(connection_route_requests_direct_syn_data_tfo(
                &config,
                &direct_route,
                Some(b"GET / HTTP/1.1\r\n\r\n"),
            ));
            assert!(!connection_route_requests_direct_syn_data_tfo(
                &config,
                &plain_route,
                Some(b"GET / HTTP/1.1\r\n\r\n"),
            ));
        }

        #[test]
        fn projected_syn_data_settings_preserve_payload_and_route_group_policy() {
            let mut direct = DesyncGroup::new(0);
            direct.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
            let plain = DesyncGroup::new(1);
            let config = RuntimeConfig { groups: vec![direct, plain], ..Default::default() };
            let settings = tcp_route_syn_data_settings(&config);

            let direct_route =
                ripdpi_runtime_decision_ports::policy::ConnectionRoute { group_index: 0, attempted_mask: 0 };
            let plain_route =
                ripdpi_runtime_decision_ports::policy::ConnectionRoute { group_index: 1, attempted_mask: 0 };

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
                    ripdpi_runtime_decision_ports::policy::TransportProtocol::Tcp,
                ),
                route_matches_transport_payload(
                    &config,
                    0,
                    target,
                    b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                    ripdpi_runtime_decision_ports::policy::TransportProtocol::Tcp,
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
            assert_eq!(
                udp_group_packet_settings(&config, 1),
                UdpGroupPacketSettings { default_ttl: 42, ip_id_mode: None }
            );
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

            assert_eq!(
                delayed_connect_settings(&config),
                DelayedConnectSettings { enabled: true, buffer_size: 16_384 },
            );
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
}

pub mod desync {
    pub use ripdpi_desync::{
        ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
        AdaptiveUdpBurstProfile, TcpSegmentHint,
    };
}

pub mod decision {
    pub use ripdpi_runtime_decision_ports::policy::{
        classify_response_failure, response_requires_dns_tampering_evidence, ConnectionRoute, DnsTamperingEvidence,
        ExtractedHost, HostSource, RetrySelectionPenalty, RouteAdvance, RuntimePolicy, TransportProtocol,
    };
}

pub mod ports {
    pub use ripdpi_runtime_decision_ports::adaptive::strategy_context::{
        direct_path_capability_for_route, merge_udp_hints_with_capability, network_scope_key,
    };
    pub use ripdpi_runtime_decision_ports::direct_path_learning::DirectPathLearningObserver;
    pub use ripdpi_runtime_decision_ports::{
        AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, DirectPathLearningPort, PolicyPort,
        RetryPacingPort,
    };
}

pub mod tcp_rotation {
    pub use crate::tcp_rotation::{CircularTcpRotationController, RotationFailureReason, RoundObservation};
}

pub mod proxy_config {
    pub use ripdpi_proxy_config::*;

    use std::sync::Mutex as StdMutex;

    pub struct NetworkReprobeTracker {
        last_identity: StdMutex<Option<String>>,
    }

    impl NetworkReprobeTracker {
        pub fn new() -> Self {
            Self { last_identity: StdMutex::new(None) }
        }

        pub fn check_snapshot(&self, snapshot: &NetworkSnapshot) -> bool {
            let identity = network_snapshot_identity(snapshot);
            let mut last = self.last_identity.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if last.as_deref() == Some(&identity) {
                return false;
            }
            let is_initial = last.is_none();
            *last = Some(identity);
            !is_initial
        }
    }

    impl Default for NetworkReprobeTracker {
        fn default() -> Self {
            Self::new()
        }
    }

    pub fn network_snapshot_identity(snapshot: &NetworkSnapshot) -> String {
        let mut id = snapshot.transport.clone();
        if let Some(ref wifi) = snapshot.wifi {
            id.push(':');
            id.push_str(&wifi.ssid_hash);
        }
        if let Some(ref cellular) = snapshot.cellular {
            id.push(':');
            id.push_str(&cellular.operator_code);
            id.push(':');
            id.push_str(&cellular.generation);
        }
        for dns in &snapshot.dns_servers {
            id.push(',');
            id.push_str(dns);
        }
        id
    }

    pub fn morph_policy(context: Option<&ProxyRuntimeContext>) -> Option<&ProxyMorphPolicy> {
        context?.morph_policy.as_ref()
    }

    pub fn morph_policy_id(policy: &ProxyMorphPolicy) -> &str {
        policy.id.as_str()
    }

    pub fn apply_udp_morph_policy_to_hints(
        policy: Option<&ProxyMorphPolicy>,
        hints: super::desync::AdaptivePlannerHints,
    ) -> super::desync::AdaptivePlannerHints {
        ripdpi_runtime_decision_ports::adaptive::morph_policy::apply_udp_morph_policy_to_hints(policy, hints)
    }

    pub fn apply_tcp_morph_policy_to_group(
        policy: Option<&ProxyMorphPolicy>,
        group: &super::config::DesyncGroup,
        payload: &[u8],
        hints: super::desync::AdaptivePlannerHints,
    ) -> super::config::DesyncGroup {
        ripdpi_runtime_decision_ports::adaptive::morph_policy::apply_tcp_morph_policy_to_group(
            policy, group, payload, hints,
        )
    }

    pub fn tcp_morph_hint_family(
        policy: Option<&ProxyMorphPolicy>,
        payload: &[u8],
        hints: super::desync::AdaptivePlannerHints,
    ) -> Option<String> {
        ripdpi_runtime_decision_ports::adaptive::morph_policy::tcp_morph_hint_family(policy, payload, hints)
    }

    pub fn udp_morph_hint_family(
        policy: Option<&ProxyMorphPolicy>,
        hints: super::desync::AdaptivePlannerHints,
    ) -> Option<String> {
        ripdpi_runtime_decision_ports::adaptive::morph_policy::udp_morph_hint_family(policy, hints)
    }

    pub fn emit_morph_hint_applied(
        telemetry: Option<&dyn super::runtime_api::RuntimeTelemetrySink>,
        policy: Option<&ProxyMorphPolicy>,
        target: std::net::SocketAddr,
        family: Option<String>,
    ) {
        let Some(telemetry) = telemetry else {
            return;
        };
        let Some(policy) = policy else {
            return;
        };
        let Some(family) = family.as_deref().filter(|value| !value.is_empty()) else {
            return;
        };
        telemetry.on_morph_hint_applied(target, morph_policy_id(policy), family);
    }

    pub fn emit_morph_rollback(
        telemetry: Option<&dyn super::runtime_api::RuntimeTelemetrySink>,
        policy: Option<&ProxyMorphPolicy>,
        target: std::net::SocketAddr,
        reason: impl AsRef<str>,
    ) {
        let Some(telemetry) = telemetry else {
            return;
        };
        let Some(policy) = policy else {
            return;
        };
        let reason = reason.as_ref();
        if reason.is_empty() {
            return;
        }
        telemetry.on_morph_rollback(target, morph_policy_id(policy), reason);
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use crate::protocol_payload::DEFAULT_FAKE_TLS;

        use super::super::config::{DesyncGroup, EntropyMode, QuicFakeProfile, TcpChainStep, TcpChainStepKind};
        use super::super::desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};

        fn policy() -> ProxyMorphPolicy {
            ProxyMorphPolicy {
                id: "balanced".to_string(),
                first_flight_size_min: 320,
                first_flight_size_max: 640,
                padding_envelope_min: 16,
                padding_envelope_max: 64,
                entropy_target_permil: 3400,
                tcp_burst_cadence_ms: vec![0, 12, 24],
                tls_burst_cadence_ms: vec![0, 8],
                quic_burst_profile: "compat_burst".to_string(),
                fake_packet_shape_profile: "compat_default".to_string(),
            }
        }

        #[test]
        fn tcp_morph_policy_updates_group_actions_and_cadence() {
            let policy = policy();
            let mut group = DesyncGroup::new(0);
            group.actions.tcp_chain = vec![
                TcpChainStep::new(TcpChainStepKind::TlsRec, super::super::config::OffsetExpr::tls_host(0)),
                TcpChainStep::new(TcpChainStepKind::Fake, super::super::config::OffsetExpr::host(1)),
            ];
            let hints = AdaptivePlannerHints {
                tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide),
                ..Default::default()
            };

            let morphed = apply_tcp_morph_policy_to_group(Some(&policy), &group, DEFAULT_FAKE_TLS, hints);

            assert_eq!(morphed.actions.fake_tls_size, 640);
            assert_eq!(morphed.actions.entropy_mode, EntropyMode::Popcount);
            assert_eq!(morphed.actions.entropy_padding_target_permil, Some(3400));
            assert_eq!(morphed.actions.entropy_padding_max, 64);
            assert_eq!(morphed.actions.tcp_chain[0].inter_segment_delay_ms(), 0);
            assert_eq!(morphed.actions.tcp_chain[1].inter_segment_delay_ms(), 8);
        }

        #[test]
        fn udp_morph_policy_overrides_hint_profiles() {
            let policy = ProxyMorphPolicy {
                id: "balanced".to_string(),
                first_flight_size_min: 0,
                first_flight_size_max: 0,
                padding_envelope_min: 0,
                padding_envelope_max: 0,
                entropy_target_permil: 0,
                tcp_burst_cadence_ms: Vec::new(),
                tls_burst_cadence_ms: Vec::new(),
                quic_burst_profile: "realistic_burst".to_string(),
                fake_packet_shape_profile: "realistic_initial".to_string(),
            };

            let hints = apply_udp_morph_policy_to_hints(Some(&policy), AdaptivePlannerHints::default());

            assert_eq!(hints.udp_burst_profile, Some(AdaptiveUdpBurstProfile::Aggressive));
            assert_eq!(hints.quic_fake_profile, Some(QuicFakeProfile::RealisticInitial));
        }
    }
}

pub mod runtime_api {
    pub use ripdpi_runtime_api::*;
}

pub mod services {
    use std::sync::Arc;

    use super::config::RuntimeConfig;
    use super::proxy_config::ProxyRuntimeContext;
    use super::runtime_api::RuntimeTelemetrySink;

    pub use ripdpi_runtime_services::{ServicesState, ServicesStateHandle};

    pub fn new_services_handle(
        config: RuntimeConfig,
        telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> ServicesStateHandle {
        ServicesStateHandle::new(ServicesState::new(config, telemetry, runtime_context))
    }
}

pub mod session {
    use std::io::{self, Read};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

    use super::config::{
        ipv6_enabled, name_resolution_enabled, runtime_buffer_size, should_cache_udp_host, RuntimeConfig,
    };

    pub use ripdpi_session::*;

    pub fn new_session_state() -> SessionState {
        SessionState::default()
    }

    pub fn observe_inbound_payload(session: &mut SessionState, payload: &[u8]) {
        session.observe_inbound(payload);
    }

    pub fn observe_outbound_payload(session: &mut SessionState, payload: &[u8]) -> OutboundProgress {
        session.observe_outbound(payload)
    }

    pub fn inbound_payload_count(session: &SessionState) -> usize {
        session.recv_count
    }

    pub struct OutboundPayloadInfo {
        pub host: Option<String>,
        pub is_tls: bool,
    }

    #[derive(Clone)]
    pub struct FirstOutboundPayloadPolicy {
        pub buffer_size: usize,
        config: RuntimeConfig,
    }

    pub struct UdpPayloadInfo {
        pub host: Option<String>,
        pub cache_host: bool,
    }

    #[derive(Clone)]
    pub struct PayloadHostExtractor {
        config: RuntimeConfig,
    }

    pub fn payload_host_extractor(config: &RuntimeConfig) -> PayloadHostExtractor {
        PayloadHostExtractor { config: config.clone() }
    }

    #[derive(Clone)]
    pub struct UdpPayloadClassifier {
        config: RuntimeConfig,
    }

    pub fn udp_payload_classifier(config: &RuntimeConfig) -> UdpPayloadClassifier {
        UdpPayloadClassifier { config: config.clone() }
    }

    pub fn first_outbound_payload_policy(config: &RuntimeConfig) -> FirstOutboundPayloadPolicy {
        FirstOutboundPayloadPolicy { buffer_size: runtime_buffer_size(config), config: config.clone() }
    }

    pub fn classify_first_outbound_payload(policy: &FirstOutboundPayloadPolicy, payload: &[u8]) -> OutboundPayloadInfo {
        classify_outbound_payload(&policy.config, payload)
    }

    pub fn classify_outbound_payload(config: &RuntimeConfig, payload: &[u8]) -> OutboundPayloadInfo {
        OutboundPayloadInfo {
            host: extract_payload_host(config, payload),
            is_tls: ripdpi_runtime_decision_ports::policy::is_tls_client_hello_payload(payload),
        }
    }

    pub fn extract_payload_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
        ripdpi_runtime_decision_ports::policy::extract_host(config, payload)
    }

    pub fn extract_payload_host_with(extractor: &PayloadHostExtractor, payload: &[u8]) -> Option<String> {
        extract_payload_host(&extractor.config, payload)
    }

    pub fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
        ripdpi_runtime_decision_ports::policy::is_tls_client_hello_payload(payload)
    }

    pub fn classify_udp_payload(config: &RuntimeConfig, payload: &[u8]) -> UdpPayloadInfo {
        let host_info = ripdpi_runtime_decision_ports::policy::extract_host_info(config, payload);
        UdpPayloadInfo {
            host: host_info.as_ref().map(|value| value.host.clone()),
            cache_host: should_cache_udp_host(config, host_info.as_ref()),
        }
    }

    pub fn classify_udp_payload_with(classifier: &UdpPayloadClassifier, payload: &[u8]) -> UdpPayloadInfo {
        classify_udp_payload(&classifier.config, payload)
    }

    pub fn parse_socks5_udp_packet<'a>(
        packet: &'a [u8],
        config: &RuntimeConfig,
        mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, &'a [u8])> {
        if packet.len() < 4 || packet[2] != 0 {
            return None;
        }
        let atyp = packet[3];
        match atyp {
            S_ATP_I4 => {
                if packet.len() < 10 {
                    return None;
                }
                let ip = Ipv4Addr::new(packet[4], packet[5], packet[6], packet[7]);
                let port = u16::from_be_bytes([packet[8], packet[9]]);
                Some((SocketAddr::new(IpAddr::V4(ip), port), &packet[10..]))
            }
            S_ATP_I6 => {
                if packet.len() < 22 || !ipv6_enabled(config) {
                    return None;
                }
                let mut raw = [0u8; 16];
                raw.copy_from_slice(&packet[4..20]);
                let port = u16::from_be_bytes([packet[20], packet[21]]);
                Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), &packet[22..]))
            }
            S_ATP_ID => {
                let len = *packet.get(4)? as usize;
                let offset = 5 + len;
                if packet.len() < offset + 2 || !name_resolution_enabled(config) {
                    return None;
                }
                let host = std::str::from_utf8(&packet[5..offset]).ok()?;
                let port = u16::from_be_bytes([packet[offset], packet[offset + 1]]);
                let resolved = resolve_name(host, SocketType::Datagram)?;
                Some((SocketAddr::new(resolved.ip(), port), &packet[offset + 2..]))
            }
            _ => None,
        }
    }

    pub fn encode_socks5_udp_packet(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
        let mut packet = vec![0, 0, 0];
        match sender {
            SocketAddr::V4(addr) => {
                packet.push(S_ATP_I4);
                packet.extend_from_slice(&addr.ip().octets());
                packet.extend_from_slice(&addr.port().to_be_bytes());
            }
            SocketAddr::V6(addr) => {
                packet.push(S_ATP_I6);
                packet.extend_from_slice(&addr.ip().octets());
                packet.extend_from_slice(&addr.port().to_be_bytes());
            }
        }
        packet.extend_from_slice(payload);
        packet
    }

    pub fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
        let mut out = vec![S_VER5, S_CMD_CONN, 0];
        match target {
            SocketAddr::V4(addr) => {
                out.push(S_ATP_I4);
                out.extend_from_slice(&addr.ip().octets());
                out.extend_from_slice(&addr.port().to_be_bytes());
            }
            SocketAddr::V6(addr) => {
                out.push(S_ATP_I6);
                out.extend_from_slice(&addr.ip().octets());
                out.extend_from_slice(&addr.port().to_be_bytes());
            }
        }
        out
    }

    pub fn read_upstream_socks_reply(reader: &mut impl Read) -> io::Result<Vec<u8>> {
        let mut header = [0u8; 4];
        reader.read_exact(&mut header)?;
        let mut out = header.to_vec();
        match header[3] {
            S_ATP_I4 => {
                let mut tail = [0u8; 6];
                reader.read_exact(&mut tail)?;
                out.extend_from_slice(&tail);
            }
            S_ATP_I6 => {
                let mut tail = [0u8; 18];
                reader.read_exact(&mut tail)?;
                out.extend_from_slice(&tail);
            }
            S_ATP_ID => {
                let mut len = [0u8; 1];
                reader.read_exact(&mut len)?;
                out.extend_from_slice(&len);
                let mut tail = vec![0u8; len[0] as usize + 2];
                reader.read_exact(&mut tail)?;
                out.extend_from_slice(&tail);
            }
            _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid upstream socks reply")),
        }
        Ok(out)
    }

    pub fn parse_shadowsocks_target(
        packet: &[u8],
        policy: super::config::ShadowsocksTargetPolicy,
        mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, usize)> {
        let atyp = *packet.first()?;
        match atyp {
            S_ATP_I4 => parse_ipv4_target(packet),
            S_ATP_I6 => parse_ipv6_target(packet, policy.ipv6_enabled),
            S_ATP_ID => parse_domain_target(packet, policy.resolve_enabled, &mut resolve_name),
            _ => None,
        }
    }

    fn parse_ipv4_target(packet: &[u8]) -> Option<(SocketAddr, usize)> {
        if packet.len() < 7 {
            return None;
        }

        let ip = Ipv4Addr::new(packet[1], packet[2], packet[3], packet[4]);
        let port = u16::from_be_bytes([packet[5], packet[6]]);
        Some((SocketAddr::new(IpAddr::V4(ip), port), 7))
    }

    fn parse_ipv6_target(packet: &[u8], ipv6_enabled: bool) -> Option<(SocketAddr, usize)> {
        if packet.len() < 19 || !ipv6_enabled {
            return None;
        }

        let mut raw = [0u8; 16];
        raw.copy_from_slice(&packet[1..17]);
        let port = u16::from_be_bytes([packet[17], packet[18]]);
        Some((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(raw)), port), 19))
    }

    fn parse_domain_target(
        packet: &[u8],
        resolve_enabled: bool,
        mut resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, usize)> {
        let len = *packet.get(1)? as usize;
        if packet.len() < 2 + len + 2 || !resolve_enabled {
            return None;
        }

        let host = std::str::from_utf8(&packet[2..2 + len]).ok()?;
        let port = u16::from_be_bytes([packet[2 + len], packet[3 + len]]);
        let resolved = resolve_name(host, SocketType::Stream)?;
        Some((SocketAddr::new(resolved.ip(), port), 2 + len + 2))
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn first_outbound_payload_policy_applies_runtime_buffer_floor() {
            let mut config = RuntimeConfig::default();
            config.network.buffer_size = 512;
            let policy = first_outbound_payload_policy(&config);

            assert_eq!(policy.buffer_size, 16_384);
            let info = classify_first_outbound_payload(&policy, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
            assert_eq!(info.host.as_deref(), Some("example.com"));
            assert!(!info.is_tls);
        }

        #[test]
        fn payload_host_extractor_preserves_host_parsing() {
            let config = RuntimeConfig::default();
            let extractor = payload_host_extractor(&config);

            let host = extract_payload_host_with(&extractor, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");

            assert_eq!(host.as_deref(), Some("example.com"));
        }

        #[test]
        fn udp_payload_classifier_preserves_host_cache_policy() {
            let mut config = RuntimeConfig::default();
            config.quic.initial_mode = super::super::config::QuicInitialMode::RouteAndCache;
            let classifier = udp_payload_classifier(&config);

            let info = classify_udp_payload_with(&classifier, b"\xc3\x00\x00\x01\x08\x00\x00\x00\x00\x00");

            assert!(info.host.is_none());
            assert!(!info.cache_host);
        }
    }
}

pub mod protocol_auth {
    pub fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
        use base64::engine::{general_purpose::STANDARD, Engine};

        let Ok(request_str) = std::str::from_utf8(request) else { return false };
        for line in request_str.lines() {
            if let Some(value) = line.strip_prefix("Proxy-Authorization:") {
                let value = value.trim();
                if let Some(encoded) = value.strip_prefix("Basic ") {
                    let encoded = encoded.trim();
                    if let Ok(decoded) = STANDARD.decode(encoded) {
                        let expected = format!("ripdpi:{token}");
                        return decoded == expected.as_bytes();
                    }
                }
                return false;
            }
        }
        false
    }
}
