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

    pub fn route_group_count(config: &RuntimeConfig) -> usize {
        config.groups.len()
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
