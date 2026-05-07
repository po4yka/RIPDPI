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

    #[derive(Clone, Copy)]
    pub struct TcpRouteConnectSettings {
        pub tfo_enabled: bool,
        pub upstream_socks_addr: Option<SocketAddr>,
        pub pre_connect_rcvbuf: Option<u32>,
        pub drop_sack: bool,
        pub window_clamp: Option<u32>,
        pub strip_timestamps: bool,
    }

    pub fn tcp_route_connect_settings(
        config: &RuntimeConfig,
        group_index: usize,
        payload: Option<&[u8]>,
        allow_tfo: bool,
    ) -> Option<TcpRouteConnectSettings> {
        let group = selected_desync_group(config, group_index)?;
        let tfo_enabled =
            allow_tfo && (tcp_fast_open_enabled(config) || group_requests_direct_syn_data_tfo(group, payload));
        let pre_connect_rcvbuf = group.actions.wsize.map(|w| match w.scale {
            Some(scale) if (scale as u32) < 32 => w.window.checked_shl(scale as u32).unwrap_or(u32::MAX),
            Some(_) => u32::MAX,
            None => w.window,
        });
        Some(TcpRouteConnectSettings {
            tfo_enabled,
            upstream_socks_addr: group.policy.ext_socks.map(|upstream| upstream.addr),
            pre_connect_rcvbuf,
            drop_sack: group.actions.drop_sack,
            window_clamp: group.actions.wsize.map(|w| w.window).or(group.actions.window_clamp),
            strip_timestamps: group.actions.strip_timestamps,
        })
    }

    pub fn route_requests_direct_syn_data_tfo(
        config: &RuntimeConfig,
        group_index: usize,
        payload: Option<&[u8]>,
    ) -> bool {
        selected_desync_group(config, group_index)
            .is_some_and(|group| group_requests_direct_syn_data_tfo(group, payload))
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

    pub fn tcp_rotation_seed(
        config: &RuntimeConfig,
        group_index: usize,
    ) -> io::Result<Option<(DesyncGroup, RotationPolicy)>> {
        let group = selected_desync_group(config, group_index)
            .cloned()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
        Ok(group.actions.rotation_policy.clone().map(|policy| (group, policy)))
    }

    pub fn primary_tcp_strategy_family_for_group(config: &RuntimeConfig, group_index: usize) -> Option<&'static str> {
        selected_desync_group(config, group_index).and_then(ripdpi_desync_runtime::primary_tcp_strategy_family)
    }

    #[derive(Clone, Copy)]
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

            assert!(group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
            assert!(!group_requests_direct_syn_data_tfo(&group, None));
            assert!(!group_requests_direct_syn_data_tfo(&group, Some(&[])));

            group.policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::from(([127, 0, 0, 1], 1080)) });
            assert!(!group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
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

    use super::config::{ipv6_enabled, name_resolution_enabled, should_cache_udp_host, RuntimeConfig};

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

    pub struct UdpPayloadInfo {
        pub host: Option<String>,
        pub cache_host: bool,
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
