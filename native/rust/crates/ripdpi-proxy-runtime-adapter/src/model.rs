pub mod config {
    use std::net::SocketAddr;
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

    pub fn listener_bind_addr(config: &RuntimeConfig) -> SocketAddr {
        SocketAddr::new(config.network.listen.listen_ip, config.network.listen.listen_port)
    }

    pub fn client_capacity(config: &RuntimeConfig) -> usize {
        config.network.max_open.max(1) as usize
    }

    pub fn max_route_retries(config: &RuntimeConfig) -> usize {
        config.max_route_retries
    }

    pub fn route_group_count(config: &RuntimeConfig) -> usize {
        config.groups.len()
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

    pub fn udp_associate_enabled(config: &RuntimeConfig) -> bool {
        config.network.udp
    }

    pub fn delayed_connect_enabled(config: &RuntimeConfig) -> bool {
        config.network.delay_conn
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

    pub fn host_autolearn_enabled(config: &RuntimeConfig) -> bool {
        config.host_autolearn.enabled
    }

    pub fn proxy_auth_token(config: &RuntimeConfig) -> Option<&str> {
        config.network.listen.auth_token.as_deref()
    }

    pub fn proxy_session_config(config: &RuntimeConfig) -> ripdpi_session::SessionConfig {
        ripdpi_session::SessionConfig { resolve: config.network.resolve, ipv6: config.network.ipv6 }
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

    pub fn group_requests_direct_syn_data_tfo(group: &DesyncGroup, payload: Option<&[u8]>) -> bool {
        payload.is_some_and(|bytes| !bytes.is_empty())
            && group.policy.ext_socks.is_none()
            && group.actions.tcp_chain.iter().any(|step| step.kind() == TcpChainStepKind::SynData)
    }

    pub fn route_requests_direct_syn_data_tfo(
        config: &RuntimeConfig,
        group_index: usize,
        payload: Option<&[u8]>,
    ) -> bool {
        config.groups.get(group_index).is_some_and(|group| group_requests_direct_syn_data_tfo(group, payload))
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

    pub fn should_rebind_udp_source_port(
        config: &RuntimeConfig,
        group_index: usize,
        quic_migrated: bool,
        round_count: u32,
        inbound_payload: &[u8],
    ) -> bool {
        !quic_migrated
            && inbound_payload.first().is_some_and(|first| first & 0x80 == 0)
            && round_count >= 2
            && config.groups.get(group_index).is_some_and(|group| group.actions.quic_migrate_after_handshake)
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

    pub fn quic_route_and_cache_enabled(config: &RuntimeConfig) -> bool {
        matches!(config.quic.initial_mode, QuicInitialMode::RouteAndCache)
    }

    pub fn runtime_buffer_size(config: &RuntimeConfig) -> usize {
        config.network.buffer_size.max(16_384)
    }

    #[derive(Clone, Copy)]
    pub struct ShadowsocksTargetPolicy {
        pub ipv6_enabled: bool,
        pub resolve_enabled: bool,
    }

    pub fn shadowsocks_target_policy(config: &RuntimeConfig) -> ShadowsocksTargetPolicy {
        ShadowsocksTargetPolicy { ipv6_enabled: config.network.ipv6, resolve_enabled: config.network.resolve }
    }
}

pub mod desync {
    pub use ripdpi_desync::{
        ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
        AdaptiveUdpBurstProfile, TcpSegmentHint,
    };
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
}

pub mod runtime_api {
    pub use ripdpi_runtime_api::*;
}

pub mod services {
    pub use ripdpi_runtime_services::{ServicesState, ServicesStateHandle};
}

pub mod session {
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
}
