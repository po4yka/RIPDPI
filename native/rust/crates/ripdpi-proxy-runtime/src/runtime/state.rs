use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use std::collections::BTreeMap;
use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::time::Duration;

use super::config::{
    connection_route_requests_direct_syn_data_tfo_with, delayed_route_matches_payload_with, ensure_default_ttl,
    first_response_timeout, first_response_timeout_count_limit, listener_settings, primary_tcp_strategy_family_with,
    relay_group_settings_with, route_matches_transport_payload_with, route_requires_delay_payload_with,
    runtime_config_projection, should_rebind_udp_source_port_with, tcp_rotation_seed_with,
    tcp_route_connect_settings_with, udp_flow_at_capacity, udp_group_settings_with, DelayedConnectSettings,
    FirstResponseSettings, ListenerSettings, NetworkReprobeSettings, ProxyHandshakeSettings, ProxyProtocolMode,
    RelayGroupSettingsTable, ResponseFailureEvidenceSettings, RoutePayloadMatcher, RuntimeConfig,
    RuntimeConfigProjection, TcpRouteConnectSettingsTable, TcpRouteRetrySettings, TcpRouteSynDataSettings,
    UdpGroupSettingsTable, WarmupProbeSettings, WsTunnelSettings, DETECT_CONNECT,
};
use super::desync::{
    execute_udp_actions, plan_udp_actions_for_runtime, runtime_desync_projection, send_tcp_desync_payload,
    DesyncSendRequest, OutboundSendError, OutboundSendOutcome, RuntimeDesyncProjection, TcpDesyncExecutionContext,
    TcpDesyncExecutor, UdpActionExecContext, UdpDesyncAction, UdpDesyncPlanContext, UdpDesyncPlanRequest,
    UdpDesyncPlanner,
};
use super::ports::{
    AdaptiveContextPort, AdaptiveFeedbackPort, DirectPathLearningObserver, DirectPathLearningPort, PolicyPort,
    RetryPacingPort,
};
use super::response::{
    runtime_failure_penalizes_strategy, runtime_failure_trigger_mask, runtime_first_response_exchange_required,
    runtime_response_projection, RuntimeFirstResponseExchangePolicy, RuntimeResponseProjection,
};
#[cfg(test)]
use super::response::{runtime_response_trigger_flag, runtime_response_trigger_supported, RuntimeTriggerEvent};
use super::session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, encode_socks5_udp_packet,
    encode_upstream_socks_connect, extract_payload_host_with, has_inbound_payload, new_session_state,
    observe_datagram_outbound_payload, observe_first_response_payload, observe_inbound_payload,
    observe_outbound_payload, observe_retry_response_payload, outbound_payload_count_this_round,
    parse_http_connect_request, parse_shadowsocks_target, parse_socks4_request, parse_socks5_request,
    read_upstream_socks_reply, runtime_session_projection, validate_http_proxy_auth, FirstOutboundPayloadPolicy,
    OutboundPayloadInfo, PayloadHostExtractor, ProxyReply, RuntimeSessionProjection, SocketType, UdpPacketParser,
    UdpPayloadClassifier, UdpPayloadInfo, S_ATP_I4, S_ATP_I6, S_AUTH_BAD, S_AUTH_NONE, S_AUTH_USERPASS, S_ER_CMD,
    S_ER_GEN, S_VER5,
};
use super::types::{
    runtime_block_signal_from_failure, runtime_build_probe_client_hello, runtime_classify_first_outbound_payload,
    runtime_classify_first_response_closed_before_response, runtime_classify_first_response_partial_tls_timeout,
    runtime_classify_mtproto_seed, runtime_classify_probe_connect_error, runtime_classify_probe_read_error,
    runtime_classify_probe_tls_response, runtime_classify_probe_write_error, runtime_classify_quic_probe,
    runtime_classify_relay_connection_freeze, runtime_classify_response_failure,
    runtime_classify_strategy_execution_failure, runtime_classify_transport_error, runtime_classify_udp_payload,
    runtime_classify_warmup_closed_before_response, runtime_classify_warmup_first_response_error,
    runtime_classify_warmup_send_error, runtime_client_request, runtime_detect_telegram_dc,
    runtime_encrypted_dns_ip_answers_for_host, runtime_first_response_boundary_tracker, runtime_outbound_progress,
    runtime_outbound_tls_client_hello_assembler, runtime_parse_socks5_udp_packet, runtime_relay_ws_tunnel,
    runtime_resolve_host_via_encrypted_dns, runtime_resolve_ws_tunnel_addr,
    runtime_response_requires_dns_tampering_evidence, runtime_session_error, runtime_should_track_strategy_target,
    runtime_should_ws_tunnel_fallback, runtime_should_ws_tunnel_first, runtime_telegram_dc_host,
    runtime_udp_packet_settings, runtime_ws_tunnel_config, RuntimeBlockSignal, RuntimeClassifiedFailure,
    RuntimeClientRequest, RuntimeConnectionRoute, RuntimeDnsTamperingEvidence, RuntimeEncryptedDnsIpAnswers,
    RuntimeFailureAction, RuntimeFailureClass, RuntimeFailureStage, RuntimeFirstResponseBoundaryTracker,
    RuntimeOutboundProgress, RuntimeOutboundTlsClientHelloAssembler, RuntimeProbeResult, RuntimeProxyProtocolMode,
    RuntimeRelayGroupSettings, RuntimeRelayRotationSeed, RuntimeRelayTimeouts, RuntimeRetrySelectionPenalty,
    RuntimeRouteAdvance, RuntimeSessionError, RuntimeSessionState, RuntimeTelegramDc, RuntimeTransportProtocol,
    RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy, RuntimeWsTunnelConfig,
    UdpFlowGroupPolicy, WsSeedClassification,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{NetworkReprobeTracker, NetworkSnapshot, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink,
};
use ripdpi_proxy_runtime_adapter::model::services::{
    new_services_handle, reprobe_reset_handle, ReprobeResetHandle, ServicesStateHandle,
};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;
use ripdpi_proxy_runtime_adapter::raw_packet_requirements::{
    raw_packet_requirements, validate_ip_fragmentation_support,
};
pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

struct RuntimeTelemetryDirectPathObserver<'a>(&'a dyn RuntimeTelemetrySink);

impl DirectPathLearningObserver for RuntimeTelemetryDirectPathObserver<'_> {
    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        self.0.on_direct_path_learning_signal(authority, ip_set_digest, event, strategy_family);
    }
}

#[derive(Clone)]
pub(super) struct RuntimeState {
    listener_settings: ListenerSettings,
    handshake_settings: ProxyHandshakeSettings,
    delayed_connect_settings: DelayedConnectSettings,
    network_reprobe_settings: NetworkReprobeSettings,
    ws_tunnel_settings: WsTunnelSettings,
    warmup_probe_settings: WarmupProbeSettings,
    route_retry_settings: TcpRouteRetrySettings,
    route_syn_data_settings: TcpRouteSynDataSettings,
    route_connect_settings: TcpRouteConnectSettingsTable,
    tcp_desync_executor: TcpDesyncExecutor,
    udp_group_settings: UdpGroupSettingsTable,
    route_payload_matcher: RoutePayloadMatcher,
    udp_desync_planner: UdpDesyncPlanner,
    udp_flow_limit: usize,
    udp_packet_parser: UdpPacketParser,
    udp_payload_classifier: UdpPayloadClassifier,
    relay_group_settings: RelayGroupSettingsTable,
    relay_host_extractor: PayloadHostExtractor,
    relay_first_response: FirstResponseSettings,
    first_outbound_payload_policy: FirstOutboundPayloadPolicy,
    first_response_exchange_policy: RuntimeFirstResponseExchangePolicy,
    response_failure_evidence_settings: ResponseFailureEvidenceSettings,
    services: ServicesStateHandle,
    active_clients: Arc<AtomicUsize>,
    telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    control: Option<std::sync::Arc<EmbeddedProxyControl>>,
    /// Session-level flag: once any connection discovers that per-socket TTL
    /// modification is rejected by the kernel (EROFS on Android), all
    /// subsequent connections skip TTL desync actions immediately.
    ttl_unavailable: Arc<AtomicBool>,
    /// Tracks network scope key changes for lightweight re-probing.
    reprobe_tracker: std::sync::Arc<NetworkReprobeTracker>,
    pcap_hook: Option<super::desync::PcapHook>,
    /// io_uring driver for zero-copy relay (Linux 6.0+, optional).
    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    io_uring: Option<std::sync::Arc<ripdpi_io_uring::IoUringDriver>>,
}

#[derive(Clone)]
pub(super) struct RouteConnectPolicy {
    pub(super) tfo_enabled: bool,
    pub(super) upstream_socks_addr: Option<SocketAddr>,
    pub(super) pre_connect_rcvbuf: Option<u32>,
    pub(super) connect_timeout: Option<Duration>,
    pub(super) protect_path: Option<String>,
    pub(super) drop_sack: bool,
    pub(super) window_clamp: Option<u32>,
    pub(super) strip_timestamps: bool,
}

impl RuntimeState {
    pub(super) fn validate_runtime_requirements(config: &RuntimeConfig) -> io::Result<()> {
        validate_ip_fragmentation_support(&raw_packet_requirements(config))
    }

    pub(super) fn listener_bind_addr(config: &RuntimeConfig) -> SocketAddr {
        listener_settings(config).bind_addr
    }

    pub(super) fn ensure_config_default_ttl(
        config: &mut RuntimeConfig,
        detect_default_ttl: impl FnOnce() -> io::Result<u8>,
    ) -> io::Result<()> {
        ensure_default_ttl(config, detect_default_ttl)
    }

    pub(super) fn new(config: RuntimeConfig, control: Option<std::sync::Arc<EmbeddedProxyControl>>) -> Self {
        let telemetry = control.as_ref().and_then(|c| c.telemetry_sink()).or_else(current_runtime_telemetry);
        let runtime_context = control.as_ref().and_then(|c| c.runtime_context());

        let handle = new_services_handle(config.clone(), telemetry.clone(), runtime_context.clone());

        let RuntimeConfigProjection {
            listener_settings,
            handshake_settings,
            delayed_connect_settings,
            network_reprobe_settings,
            ws_tunnel_settings,
            warmup_probe_settings,
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            udp_group_settings,
            route_payload_matcher,
            udp_flow_limit,
            relay_group_settings,
            relay_first_response,
            response_failure_evidence_settings,
        } = runtime_config_projection(&config);
        let RuntimeSessionProjection {
            udp_packet_parser,
            udp_payload_classifier,
            relay_host_extractor,
            first_outbound_payload_policy,
        } = runtime_session_projection(&config);
        let RuntimeDesyncProjection { tcp_desync_executor, udp_desync_planner } = runtime_desync_projection(&config);
        let RuntimeResponseProjection { first_response_exchange_policy } = runtime_response_projection(&config);

        Self {
            listener_settings,
            handshake_settings,
            delayed_connect_settings,
            network_reprobe_settings,
            ws_tunnel_settings,
            warmup_probe_settings,
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            tcp_desync_executor,
            udp_group_settings,
            route_payload_matcher,
            udp_desync_planner,
            udp_flow_limit,
            udp_packet_parser,
            udp_payload_classifier,
            relay_group_settings,
            relay_host_extractor,
            relay_first_response,
            first_outbound_payload_policy,
            first_response_exchange_policy,
            response_failure_evidence_settings,
            services: handle,
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry,
            runtime_context,
            control,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(NetworkReprobeTracker::new()),
            pcap_hook: None,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }

    pub(super) fn clear_connection_cache(&self) -> usize {
        PolicyPort::clear_connection_cache(&self.services)
    }

    pub(super) fn drain_autolearn_events(&self) {
        let _ = PolicyPort::drain_autolearn_events(&self.services);
    }

    pub(super) fn flush_autolearn_telemetry(&self) {
        if let Some(telemetry) = &self.telemetry {
            let autolearn = PolicyPort::autolearn_state(&self.services);
            telemetry.on_host_autolearn_state(
                autolearn.enabled,
                autolearn.learned_host_count,
                autolearn.penalized_host_count,
                autolearn.blocked_host_count,
                autolearn.last_block_signal.as_deref(),
                autolearn.last_block_provider.as_deref(),
            );
            for event in PolicyPort::drain_autolearn_events(&self.services) {
                telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
            }
        } else {
            self.drain_autolearn_events();
        }
    }

    pub(super) fn listener_client_capacity(&self) -> usize {
        self.listener_settings.client_capacity
    }

    pub(super) fn listener_route_group_count(&self) -> usize {
        self.listener_settings.route_group_count
    }

    pub(super) fn proxy_protocol_mode(&self) -> RuntimeProxyProtocolMode {
        match self.handshake_settings.protocol_mode {
            ProxyProtocolMode::Transparent => RuntimeProxyProtocolMode::Transparent,
            ProxyProtocolMode::HttpConnect => RuntimeProxyProtocolMode::HttpConnect,
            ProxyProtocolMode::BytePrefixed { shadowsocks_enabled } => {
                RuntimeProxyProtocolMode::BytePrefixed { shadowsocks_enabled }
            }
        }
    }

    pub(super) fn proxy_auth_token(&self) -> Option<&str> {
        self.handshake_settings.auth_token.as_deref()
    }

    pub(super) fn udp_associate_enabled(&self) -> bool {
        self.handshake_settings.udp_associate_enabled
    }

    pub(super) fn handshake_protect_path(&self) -> Option<String> {
        self.handshake_settings.protect_path.clone()
    }

    pub(super) fn delayed_connect_enabled(&self) -> bool {
        self.delayed_connect_settings.enabled
    }

    pub(super) fn delayed_connect_buffer_size(&self) -> usize {
        self.delayed_connect_settings.buffer_size
    }

    pub(super) fn network_reprobe_enabled(&self) -> bool {
        self.network_reprobe_settings.enabled
    }

    pub(super) fn network_reprobe_protect_path(&self) -> Option<String> {
        self.network_reprobe_settings.protect_path.clone()
    }

    pub(super) fn should_ws_tunnel_first(&self, target: SocketAddr) -> Option<RuntimeTelegramDc> {
        runtime_should_ws_tunnel_first(target, &self.ws_tunnel_settings)
    }

    pub(super) fn should_ws_tunnel_fallback(&self, target: SocketAddr) -> Option<RuntimeTelegramDc> {
        runtime_should_ws_tunnel_fallback(target, &self.ws_tunnel_settings)
    }

    pub(super) fn ws_tunnel_config(&self, resolved_addr: Option<SocketAddr>) -> RuntimeWsTunnelConfig {
        runtime_ws_tunnel_config(&self.ws_tunnel_settings, resolved_addr)
    }

    pub(super) fn classify_mtproto_seed(seed: &[u8]) -> WsSeedClassification {
        runtime_classify_mtproto_seed(seed)
    }

    pub(super) fn relay_ws_tunnel(
        client: TcpStream,
        dc: RuntimeTelegramDc,
        seed_request: Vec<u8>,
        config: &RuntimeWsTunnelConfig,
    ) -> io::Result<()> {
        runtime_relay_ws_tunnel(client, dc, seed_request, config)
    }

    pub(super) fn warmup_probe_scheduler_enabled(&self) -> bool {
        self.warmup_probe_settings.scheduler_enabled
    }

    pub(super) fn warmup_probe_response_buffer_size(&self) -> usize {
        self.warmup_probe_settings.response_buffer_size
    }

    pub(super) fn resolve_warmup_probe_host(&self, host: &str) -> io::Result<SocketAddr> {
        runtime_resolve_host_via_encrypted_dns(
            host,
            self.runtime_context.as_ref(),
            self.warmup_probe_settings.protect_path.as_deref(),
            self.warmup_probe_settings.ipv6_enabled,
        )
    }

    pub(super) fn note_retry_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: RuntimeTransportProtocol,
    ) -> io::Result<()> {
        RetryPacingPort::note_retry_success(&self.services, target, group_index, host, payload, transport)
    }

    pub(super) fn note_retry_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: RuntimeTransportProtocol,
        now_ms: u64,
    ) -> io::Result<()> {
        RetryPacingPort::note_retry_failure(&self.services, target, group_index, host, payload, transport, now_ms)
    }

    pub(super) fn build_retry_penalties(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: RuntimeTransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RuntimeRetrySelectionPenalty>> {
        RetryPacingPort::build_retry_penalties(&self.services, target, host, payload, transport, now_ms)
    }

    pub(super) fn apply_retry_pacing(
        &self,
        target: SocketAddr,
        route: &RuntimeConnectionRoute,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
    ) -> io::Result<()> {
        let telemetry = self.telemetry.clone();
        RetryPacingPort::apply_retry_pacing(
            &self.services,
            target,
            route.group_index,
            host,
            payload,
            now_ms,
            &|target, group_index, reason, backoff_ms| {
                if let Some(tel) = &telemetry {
                    tel.on_retry_paced(target, group_index, reason, backoff_ms);
                }
            },
        )
    }

    pub(super) fn relay_group(&self, group_index: usize) -> io::Result<RuntimeRelayGroupSettings> {
        relay_group_settings_with(&self.relay_group_settings, group_index)
            .map(RuntimeRelayGroupSettings::from_adapter)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
    }

    pub(super) fn relay_rotation_seed(&self, group_index: usize) -> io::Result<Option<RuntimeRelayRotationSeed>> {
        tcp_rotation_seed_with(&self.relay_group_settings, group_index)
    }

    pub(super) fn relay_first_response_buffer_size(&self) -> usize {
        self.relay_first_response.buffer_size
    }

    pub(super) fn relay_first_response_boundary_tracker(&self, request: &[u8]) -> RuntimeFirstResponseBoundaryTracker {
        runtime_first_response_boundary_tracker(request, self.relay_first_response)
    }

    pub(super) fn relay_first_response_timeout(
        &self,
        tls_partial: &RuntimeFirstResponseBoundaryTracker,
    ) -> Option<Duration> {
        first_response_timeout(self.relay_first_response, tls_partial.active())
    }

    pub(super) fn relay_first_response_timeout_count_limit(&self) -> i32 {
        first_response_timeout_count_limit(self.relay_first_response)
    }

    pub(super) fn relay_first_response_reports_timeout_failure(&self) -> bool {
        self.relay_first_response.timeout_ms != 0
    }

    pub(super) fn start_relay_rotation_round(
        &self,
        rotation: &mut CircularTcpRotationController,
        progress: RuntimeOutboundProgress,
        payload: &[u8],
        retrans_baseline: Option<u32>,
        host: Option<&str>,
        target: Option<SocketAddr>,
    ) {
        rotation.start_round(
            self.relay_first_response,
            progress.round,
            progress.stream_start,
            payload,
            retrans_baseline,
            host,
            target,
        );
    }

    pub(super) fn append_relay_rotation_request_chunk(
        &self,
        rotation: &mut CircularTcpRotationController,
        round: u32,
        payload: &[u8],
    ) {
        rotation.append_request_chunk(self.relay_first_response, round, payload);
    }

    pub(super) fn first_outbound_payload_buffer_size(&self) -> usize {
        self.first_outbound_payload_policy.buffer_size
    }

    pub(super) fn new_session_state() -> RuntimeSessionState {
        RuntimeSessionState(new_session_state())
    }

    pub(super) fn observe_session_inbound_payload(state: &mut RuntimeSessionState, payload: &[u8]) {
        observe_inbound_payload(&mut state.0, payload);
    }

    pub(super) fn session_has_inbound_payload(state: &RuntimeSessionState) -> bool {
        has_inbound_payload(&state.0)
    }

    pub(super) fn observe_session_outbound_payload(
        state: &mut RuntimeSessionState,
        payload: &[u8],
    ) -> RuntimeOutboundProgress {
        runtime_outbound_progress(observe_outbound_payload(&mut state.0, payload))
    }

    pub(super) fn observe_session_datagram_outbound_payload(
        state: &mut RuntimeSessionState,
        payload: &[u8],
    ) -> RuntimeOutboundProgress {
        runtime_outbound_progress(observe_datagram_outbound_payload(&mut state.0, payload))
    }

    pub(super) fn single_payload_progress(payload_size: usize) -> RuntimeOutboundProgress {
        RuntimeOutboundProgress { round: 1, payload_size, stream_start: 0, stream_end: payload_size.saturating_sub(1) }
    }

    pub(super) fn observe_session_first_response_payload(state: &mut RuntimeSessionState, payload: &[u8]) -> bool {
        observe_first_response_payload(&mut state.0, payload)
    }

    pub(super) fn observe_session_retry_response_payload(state: &mut RuntimeSessionState, payload: &[u8]) {
        observe_retry_response_payload(&mut state.0, payload);
    }

    pub(super) fn outbound_payload_count_this_round(state: &RuntimeSessionState) -> usize {
        outbound_payload_count_this_round(&state.0)
    }

    pub(super) fn session_round_count(state: &RuntimeSessionState) -> u32 {
        state.0.round_count
    }

    pub(super) fn encode_upstream_socks_connect(target: SocketAddr) -> Vec<u8> {
        encode_upstream_socks_connect(target)
    }

    pub(super) fn read_upstream_socks_reply(reader: &mut impl Read) -> io::Result<Vec<u8>> {
        read_upstream_socks_reply(reader)
    }

    pub(super) fn upstream_socks_auth_request() -> [u8; 3] {
        [S_VER5, 1, S_AUTH_NONE]
    }

    pub(super) fn upstream_socks_auth_accepted(reply: [u8; 2]) -> bool {
        reply == [S_VER5, S_AUTH_NONE]
    }

    pub(super) fn upstream_socks_connect_succeeded(reply: &[u8]) -> bool {
        reply.get(1).copied().unwrap_or(S_ER_GEN) == 0
    }

    pub(super) fn socks5_auth_selection(auth_token: Option<&str>, methods: &[u8]) -> ([u8; 2], bool) {
        let method = if auth_token.is_some() {
            if methods.contains(&S_AUTH_USERPASS) {
                S_AUTH_USERPASS
            } else {
                S_AUTH_BAD
            }
        } else if methods.contains(&S_AUTH_NONE) {
            S_AUTH_NONE
        } else {
            S_AUTH_BAD
        };
        ([S_VER5, method], method != S_AUTH_BAD)
    }

    pub(super) fn is_socks5_version(version: u8) -> bool {
        version == S_VER5
    }

    pub(super) fn socks5_command_unsupported_code() -> u8 {
        S_ER_CMD
    }

    pub(super) fn socks5_general_failure_code() -> u8 {
        S_ER_GEN
    }

    pub(super) fn socks5_fixed_address_tail_len(address_type: u8) -> Option<usize> {
        match address_type {
            S_ATP_I4 => Some(6),
            S_ATP_I6 => Some(18),
            _ => None,
        }
    }

    pub(super) fn is_socks5_domain_address_type(address_type: u8) -> bool {
        address_type == 0x03
    }

    pub(super) fn encode_socks4_reply(granted: bool) -> ProxyReply {
        encode_socks4_reply(granted)
    }

    pub(super) fn encode_socks5_reply(code: u8, addr: SocketAddr) -> ProxyReply {
        encode_socks5_reply(code, addr)
    }

    pub(super) fn encode_http_connect_reply(success: bool) -> ProxyReply {
        encode_http_connect_reply(success)
    }

    pub(super) fn resolve_proxy_name(&self, host: &str, _socket_type: SocketType) -> Option<SocketAddr> {
        use std::net::IpAddr;

        if let Ok(ip) = host.parse::<IpAddr>() {
            return Some(SocketAddr::new(ip, 0));
        }

        let session_config = self.handshake_settings.session_config;
        if let Some(loopback) = resolve_localhost(host, session_config.ipv6) {
            return Some(loopback);
        }
        if !session_config.resolve {
            return None;
        }

        let protect_path = self.handshake_protect_path();
        self.resolve_encrypted_dns_host(host, protect_path.as_deref(), session_config.ipv6).ok()
    }

    pub(super) fn resolve_handshake_name(&self, host: &str) -> Option<SocketAddr> {
        self.resolve_proxy_name(host, SocketType::Stream)
    }

    pub(super) fn parse_socks4_client_request(
        &self,
        request: &[u8],
        resolve_name: impl Fn(&str) -> Option<SocketAddr>,
    ) -> Result<RuntimeClientRequest, RuntimeSessionError> {
        let resolver = |host: &str, socket_type: SocketType| {
            let _ = socket_type;
            resolve_name(host)
        };
        parse_socks4_request(request, self.handshake_settings.session_config, &resolver)
            .map(runtime_client_request)
            .map_err(runtime_session_error)
    }

    pub(super) fn parse_socks5_client_request(
        &self,
        request: &[u8],
        resolve_name: impl Fn(&str) -> Option<SocketAddr>,
    ) -> Result<RuntimeClientRequest, RuntimeSessionError> {
        let resolver = |host: &str, socket_type: SocketType| {
            let _ = socket_type;
            resolve_name(host)
        };
        parse_socks5_request(request, SocketType::Stream, self.handshake_settings.session_config, &resolver)
            .map(runtime_client_request)
            .map_err(runtime_session_error)
    }

    pub(super) fn parse_http_connect_client_request(
        request: &[u8],
        resolve_name: impl Fn(&str) -> Option<SocketAddr>,
    ) -> Result<RuntimeClientRequest, RuntimeSessionError> {
        let resolver = |host: &str, socket_type: SocketType| {
            let _ = socket_type;
            resolve_name(host)
        };
        parse_http_connect_request(request, &resolver).map(runtime_client_request).map_err(runtime_session_error)
    }

    pub(super) fn validate_http_proxy_auth(request: &[u8], token: &str) -> bool {
        validate_http_proxy_auth(request, token)
    }

    pub(super) fn parse_shadowsocks_target(
        &self,
        request: &[u8],
        mut resolve_name: impl FnMut(&str) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, usize)> {
        parse_shadowsocks_target(request, self.handshake_settings.shadowsocks_target_policy, |host, socket_type| {
            let _ = socket_type;
            resolve_name(host)
        })
    }

    pub(super) fn classify_first_outbound_payload(&self, payload: &[u8]) -> OutboundPayloadInfo {
        runtime_classify_first_outbound_payload(&self.first_outbound_payload_policy, payload)
    }

    pub(super) fn first_outbound_tls_client_hello_assembler() -> RuntimeOutboundTlsClientHelloAssembler {
        runtime_outbound_tls_client_hello_assembler()
    }

    pub(super) fn build_probe_client_hello(domain: &str) -> Vec<u8> {
        runtime_build_probe_client_hello(domain)
    }

    pub(super) fn first_response_exchange_required(&self) -> io::Result<bool> {
        runtime_first_response_exchange_required(self.first_response_exchange_policy, |trigger| {
            Ok(PolicyPort::supports_trigger(&self.services, trigger))
        })
    }

    pub(super) fn primary_tcp_strategy_family(&self, group_index: usize) -> Option<&'static str> {
        primary_tcp_strategy_family_with(&self.relay_group_settings, group_index)
    }

    pub(super) fn extract_relay_payload_host(&self, payload: &[u8]) -> Option<String> {
        extract_payload_host_with(&self.relay_host_extractor, payload)
    }

    pub(super) fn route_requires_delay_payload(&self, group_index: usize) -> io::Result<bool> {
        route_requires_delay_payload_with(&self.route_payload_matcher, group_index)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
    }

    pub(super) fn delayed_route_matches_payload(
        &self,
        group_index: usize,
        target: SocketAddr,
        payload: &[u8],
        host_hint: Option<&str>,
    ) -> bool {
        delayed_route_matches_payload_with(&self.route_payload_matcher, group_index, target, payload, host_hint)
    }

    pub(super) fn route_matches_transport_payload(
        &self,
        group_index: usize,
        target: SocketAddr,
        payload: &[u8],
        transport: RuntimeTransportProtocol,
    ) -> bool {
        route_matches_transport_payload_with(&self.route_payload_matcher, group_index, target, payload, transport)
    }

    pub(super) fn parse_socks5_udp_packet<'a>(
        &self,
        packet: &'a [u8],
        resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, &'a [u8])> {
        runtime_parse_socks5_udp_packet(&self.udp_packet_parser, packet, resolve_name)
    }

    pub(super) fn encode_socks5_udp_packet(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
        encode_socks5_udp_packet(target, payload)
    }

    pub(super) fn classify_udp_payload(&self, payload: &[u8]) -> UdpPayloadInfo {
        runtime_classify_udp_payload(&self.udp_payload_classifier, payload)
    }

    pub(super) fn udp_flow_limit(&self) -> usize {
        self.udp_flow_limit
    }

    pub(super) fn udp_flow_at_capacity(flow_exists: bool, active_flow_count: usize, flow_limit: usize) -> bool {
        udp_flow_at_capacity(flow_exists, active_flow_count, flow_limit)
    }

    pub(super) fn udp_flow_group_policy(&self, group_index: usize) -> Option<UdpFlowGroupPolicy> {
        let group = udp_group_settings_with(&self.udp_group_settings, group_index)?;
        Some(UdpFlowGroupPolicy {
            socket: RuntimeUdpSocketSettings { bind_low_port: group.socket.bind_low_port },
            packet: runtime_udp_packet_settings(group.packet),
            source_rebind: RuntimeUdpSourceRebindPolicy { after_handshake: group.source_rebind.after_handshake },
        })
    }

    pub(super) fn should_rebind_udp_flow_source_port(
        policy: RuntimeUdpSourceRebindPolicy,
        quic_migrated: bool,
        round_count: u32,
        inbound_payload: &[u8],
    ) -> bool {
        should_rebind_udp_source_port_with(policy.into_adapter(), quic_migrated, round_count, inbound_payload)
    }

    pub(super) fn max_route_retries(&self) -> usize {
        self.route_retry_settings.max_route_retries
    }

    pub(super) fn route_uses_direct_syn_data_tfo(
        &self,
        route: &RuntimeConnectionRoute,
        payload: Option<&[u8]>,
    ) -> bool {
        connection_route_requests_direct_syn_data_tfo_with(&self.route_syn_data_settings, route, payload)
    }

    pub(super) fn route_connect_policy(
        &self,
        group_index: usize,
        payload: Option<&[u8]>,
        allow_tfo: bool,
    ) -> Option<RouteConnectPolicy> {
        let settings = tcp_route_connect_settings_with(&self.route_connect_settings, group_index, payload, allow_tfo)?;
        Some(RouteConnectPolicy {
            tfo_enabled: settings.tfo_enabled,
            upstream_socks_addr: settings.upstream_socks_addr,
            pre_connect_rcvbuf: settings.pre_connect_rcvbuf,
            connect_timeout: settings.connect_timeout,
            protect_path: settings.protect_path,
            drop_sack: settings.drop_sack,
            window_clamp: settings.window_clamp,
            strip_timestamps: settings.strip_timestamps,
        })
    }

    pub(super) fn select_initial_route(
        &self,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: RuntimeTransportProtocol,
    ) -> Option<RuntimeConnectionRoute> {
        PolicyPort::select_initial(&self.services, target, payload, host, allow_unknown_payload, transport)
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn select_next_route(
        &self,
        route: &RuntimeConnectionRoute,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: RuntimeTransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RuntimeRetrySelectionPenalty>>,
    ) -> Option<RuntimeConnectionRoute> {
        PolicyPort::select_next(
            &self.services,
            route,
            target,
            payload,
            host,
            transport,
            trigger,
            can_reconnect,
            retry_penalties,
        )
    }

    pub(super) fn note_route_success(
        &self,
        target: SocketAddr,
        route: &RuntimeConnectionRoute,
        host: Option<&str>,
        transport: RuntimeTransportProtocol,
    ) -> io::Result<()> {
        PolicyPort::note_success(&self.services, target, route, host, transport)
    }

    pub(super) fn runtime_supports_trigger(&self, trigger: u32) -> bool {
        PolicyPort::supports_trigger(&self.services, trigger)
    }

    pub(super) fn retry_trigger_for_failure(&self, failure: &RuntimeClassifiedFailure) -> Option<u32> {
        let trigger = runtime_failure_trigger_mask(failure);
        if failure.action != RuntimeFailureAction::RetryWithMatchingGroup
            || trigger == 0
            || !self.runtime_supports_trigger(trigger)
        {
            return None;
        }
        Some(trigger)
    }

    pub(super) fn failure_penalizes_strategy(failure: &RuntimeClassifiedFailure) -> bool {
        runtime_failure_penalizes_strategy(failure)
    }

    #[cfg(test)]
    pub(super) fn failure_trigger_mask(failure: &RuntimeClassifiedFailure) -> u32 {
        runtime_failure_trigger_mask(failure)
    }

    #[cfg(test)]
    pub(super) fn trigger_flag(trigger: RuntimeTriggerEvent) -> u32 {
        runtime_response_trigger_flag(trigger)
    }

    #[cfg(test)]
    pub(super) fn response_trigger_supported(config: &RuntimeConfig, trigger: RuntimeTriggerEvent) -> bool {
        runtime_response_trigger_supported(config, trigger)
    }

    pub(super) fn udp_flow_timeout_failure() -> Option<RuntimeClassifiedFailure> {
        runtime_classify_quic_probe("quic_timeout", Some("UDP flow expired before first response"))
    }

    pub(super) fn silent_drop_failure_class() -> RuntimeFailureClass {
        RuntimeFailureClass::SilentDrop
    }

    pub(super) fn connect_failure_trigger() -> u32 {
        DETECT_CONNECT
    }

    pub(super) fn classify_connect_transport_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_transport_error(RuntimeFailureStage::Connect, source)
    }

    pub(super) fn classify_first_response_transport_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_transport_error(RuntimeFailureStage::FirstResponse, source)
    }

    pub(super) fn classify_first_response_closed_before_response() -> RuntimeClassifiedFailure {
        runtime_classify_first_response_closed_before_response()
    }

    pub(super) fn classify_first_response_partial_tls_timeout() -> RuntimeClassifiedFailure {
        runtime_classify_first_response_partial_tls_timeout()
    }

    pub(super) fn classify_relay_connection_freeze(timeouts: RuntimeRelayTimeouts) -> RuntimeClassifiedFailure {
        runtime_classify_relay_connection_freeze(timeouts.into_adapter())
    }

    pub(super) fn relay_timeouts(&self, group_index: usize) -> io::Result<RuntimeRelayTimeouts> {
        self.relay_group(group_index).map(|group| group.timeouts())
    }

    pub(super) fn classify_warmup_send_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_warmup_send_error(source)
    }

    pub(super) fn classify_warmup_first_response_error(source: &io::Error) -> RuntimeClassifiedFailure {
        runtime_classify_warmup_first_response_error(source)
    }

    pub(super) fn classify_warmup_closed_before_response() -> RuntimeClassifiedFailure {
        runtime_classify_warmup_closed_before_response()
    }

    pub(super) fn classify_probe_connect_error(source: &io::Error) -> RuntimeProbeResult {
        runtime_classify_probe_connect_error(source)
    }

    pub(super) fn classify_probe_write_error(source: &io::Error) -> RuntimeProbeResult {
        runtime_classify_probe_write_error(source)
    }

    pub(super) fn classify_probe_read_error(source: &io::Error) -> RuntimeProbeResult {
        runtime_classify_probe_read_error(source)
    }

    pub(super) fn classify_probe_tls_response(header: [u8; 5], handshake_type: Option<u8>) -> RuntimeProbeResult {
        runtime_classify_probe_tls_response(header, handshake_type)
    }

    pub(super) fn classify_first_write_failure(error: &OutboundSendError) -> RuntimeClassifiedFailure {
        match error {
            OutboundSendError::Transport(source) => {
                runtime_classify_transport_error(RuntimeFailureStage::FirstWrite, source)
            }
            OutboundSendError::StrategyExecution {
                action,
                strategy_family,
                fallback,
                bytes_committed,
                source_errno,
                source,
            } => {
                let mut failure = runtime_classify_strategy_execution_failure(
                    RuntimeFailureStage::FirstWrite,
                    action,
                    source.kind(),
                    *source_errno,
                    error.to_string(),
                )
                .unwrap_or_else(|| runtime_classify_transport_error(RuntimeFailureStage::FirstWrite, source));
                failure = failure.with_tag("strategyFamily", (*strategy_family).to_string());
                failure = failure.with_tag("bytesCommitted", bytes_committed.to_string());
                if let Some(fallback_family) = fallback {
                    failure = failure.with_tag("fallback", (*fallback_family).to_string());
                }
                if *bytes_committed > 0 {
                    failure.action = RuntimeFailureAction::SurfaceOnly;
                }
                failure
            }
        }
    }

    pub(super) fn first_write_failure_retries_syn_data_without_tfo(
        route_requests_direct_syn_data_tfo: bool,
        failure: &RuntimeClassifiedFailure,
        already_retried: bool,
    ) -> bool {
        !already_retried
            && route_requests_direct_syn_data_tfo
            && failure.action != RuntimeFailureAction::SurfaceOnly
            && matches!(failure.class, RuntimeFailureClass::ConnectFailure | RuntimeFailureClass::TcpReset)
    }

    pub(super) fn connect_failure_retries_without_tfo(
        tcp_fast_open_enabled: bool,
        failure: &RuntimeClassifiedFailure,
    ) -> bool {
        tcp_fast_open_enabled
            && matches!(failure.class, RuntimeFailureClass::ConnectFailure | RuntimeFailureClass::TcpReset)
    }

    pub(super) fn should_track_strategy_target(target: SocketAddr) -> bool {
        runtime_should_track_strategy_target(target)
    }

    pub(super) fn note_block_signal_for_failure(
        &self,
        host: Option<&str>,
        failure: &RuntimeClassifiedFailure,
        tcp_total_retransmissions: Option<u32>,
    ) {
        let Some(host) = host else {
            return;
        };
        let Some(signal) = runtime_block_signal_from_failure(failure, tcp_total_retransmissions) else {
            return;
        };
        self.note_block_signal(
            host,
            signal.signal,
            signal.provider.as_deref(),
            self.block_signal_confirmation_allowed(),
        );
    }

    pub(super) fn classify_response_failure(
        &self,
        target: SocketAddr,
        request: &[u8],
        response: &[u8],
        host: Option<&str>,
    ) -> Option<RuntimeClassifiedFailure> {
        let answer_set = if runtime_response_requires_dns_tampering_evidence(request, response) {
            host.and_then(|value| self.encrypted_dns_ip_answers_for_host(value).ok())
        } else {
            None
        };
        let dns_evidence = host.zip(answer_set.as_ref()).map(|(value, answers)| RuntimeDnsTamperingEvidence {
            host: value,
            target_ip: target.ip(),
            answers: &answers.answers,
            resolver_label: &answers.label,
        });
        runtime_classify_response_failure(request, response, dns_evidence)
    }

    pub(super) fn note_block_signal(
        &self,
        host: &str,
        signal: RuntimeBlockSignal,
        provider: Option<&str>,
        confirmation_allowed: bool,
    ) {
        PolicyPort::note_block_signal(&self.services, host, signal, provider, confirmation_allowed);
    }

    pub(super) fn advance_route(
        &self,
        route: &RuntimeConnectionRoute,
        advance: RuntimeRouteAdvance<'_>,
    ) -> io::Result<Option<RuntimeConnectionRoute>> {
        PolicyPort::advance_route(&self.services, route, advance)
    }

    pub(super) fn store_udp_route_hint(
        &self,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    ) {
        PolicyPort::store_route(&self.services, dest, group_index, attempted_mask, host);
    }

    pub(super) fn note_adaptive_tcp_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_tcp_success(&self.services, group_index, target, host, payload)
    }

    pub(super) fn note_adaptive_tcp_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_tcp_failure(&self.services, group_index, target, host, payload)
    }

    pub(super) fn note_adaptive_udp_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_udp_success(&self.services, group_index, target, host, payload)
    }

    pub(super) fn note_adaptive_udp_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_udp_failure(&self.services, group_index, target, host, payload)
    }

    pub(super) fn note_adaptive_fake_ttl_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_fake_ttl_success(&self.services, group_index, target, host)
    }

    pub(super) fn note_adaptive_fake_ttl_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_fake_ttl_failure(&self.services, group_index, target, host)
    }

    pub(super) fn note_server_ttl(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()> {
        AdaptiveFeedbackPort::note_server_ttl(&self.services, group_index, target, host, observed_ttl)
    }

    pub(super) fn note_evolver_success(&self) {
        AdaptiveFeedbackPort::note_evolver_success(&self.services);
    }

    pub(super) fn note_evolver_failure(&self, class: RuntimeFailureClass) {
        AdaptiveFeedbackPort::note_evolver_failure(&self.services, class);
    }

    pub(super) fn send_tcp_desync_payload(
        &self,
        writer: &mut TcpStream,
        request: DesyncSendRequest<'_>,
    ) -> Result<OutboundSendOutcome, OutboundSendError> {
        send_tcp_desync_payload(writer, self.tcp_desync_execution_context(), request)
    }

    fn tcp_desync_execution_context(&self) -> TcpDesyncExecutionContext<'_> {
        TcpDesyncExecutionContext {
            executor: &self.tcp_desync_executor,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
            ttl_unavailable: &self.ttl_unavailable,
            pcap_hook: self.pcap_hook.as_ref(),
        }
    }

    fn plan_udp_desync_actions(&self, request: UdpDesyncPlanRequest<'_>) -> io::Result<Vec<UdpDesyncAction>> {
        plan_udp_actions_for_runtime(self.udp_desync_plan_context(), request)
    }

    pub(super) fn plan_udp_flow_actions(
        &self,
        group_index: usize,
        payload: &[u8],
        progress: RuntimeOutboundProgress,
        host: Option<&str>,
        target: SocketAddr,
        default_ttl: u8,
    ) -> io::Result<Vec<UdpDesyncAction>> {
        self.plan_udp_desync_actions(UdpDesyncPlanRequest {
            group_index,
            payload,
            progress: progress.into_adapter(),
            host,
            target,
            default_ttl,
        })
    }

    pub(super) fn execute_udp_desync_actions(
        upstream: &UdpSocket,
        target: SocketAddr,
        packet_settings: RuntimeUdpPacketSettings,
        protect_path: Option<&str>,
        actions: &[UdpDesyncAction],
    ) -> io::Result<()> {
        execute_udp_actions(
            UdpActionExecContext {
                upstream,
                target,
                default_ttl: packet_settings.default_ttl,
                protect_path,
                ip_id_mode: packet_settings.ip_id_mode,
            },
            actions,
        )
    }

    fn udp_desync_plan_context(&self) -> UdpDesyncPlanContext<'_> {
        UdpDesyncPlanContext {
            planner: &self.udp_desync_planner,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
        }
    }

    pub(super) fn encrypted_dns_ip_answers_for_host(&self, host: &str) -> io::Result<RuntimeEncryptedDnsIpAnswers> {
        runtime_encrypted_dns_ip_answers_for_host(
            host,
            self.runtime_context.as_ref(),
            self.response_failure_evidence_settings.protect_path.as_deref(),
        )
    }

    pub(super) fn shutdown_requested(&self) -> bool {
        self.control.as_ref().map_or_else(crate::process::shutdown_requested, |control| control.shutdown_requested())
    }

    pub(super) fn has_embedded_control(&self) -> bool {
        self.control.is_some()
    }

    pub(super) fn current_network_snapshot(&self) -> Option<NetworkSnapshot> {
        self.control.as_ref().and_then(|control| control.current_network_snapshot())
    }

    pub(super) fn should_reprobe_network(&self, snapshot: &NetworkSnapshot) -> bool {
        self.reprobe_tracker.check_snapshot(snapshot)
    }

    pub(super) fn block_signal_confirmation_allowed(&self) -> bool {
        self.current_network_snapshot().is_none_or(|snapshot| snapshot.validated && !snapshot.captive_portal)
    }

    pub(super) fn acquire_client_slot(&self, client_capacity: usize) -> Option<ClientSlotGuard> {
        ClientSlotGuard::acquire(self.active_clients.clone(), client_capacity)
    }

    pub(super) fn note_listener_started(&self, bind_addr: SocketAddr, max_clients: usize, group_count: usize) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_listener_started(bind_addr, max_clients, group_count);
        }
    }

    pub(super) fn note_listener_stopped(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_listener_stopped();
        }
    }

    pub(super) fn note_client_slot_exhausted(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_slot_exhausted();
        }
    }

    pub(super) fn note_client_accepted(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_accepted();
        }
    }

    pub(super) fn note_client_finished(&self) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_finished();
        }
    }

    pub(super) fn note_client_error(&self, error: &io::Error) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_client_error(error);
        }
    }

    pub(super) fn note_telegram_dc_detected(&self, target: SocketAddr, dc: u8) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_telegram_dc_detected(target, dc);
        }
    }

    pub(super) fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
        runtime_detect_telegram_dc(target)
    }

    pub(super) fn telegram_dc_host(dc: u8) -> String {
        runtime_telegram_dc_host(dc)
    }

    pub(super) fn telegram_dc_host_hint(&self, target: SocketAddr) -> Option<String> {
        let dc = Self::detect_telegram_dc(target)?;
        self.note_telegram_dc_detected(target, dc);
        Some(Self::telegram_dc_host(dc))
    }

    pub(super) fn note_ws_tunnel_escalation(&self, target: SocketAddr, dc: u8, success: bool) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_ws_tunnel_escalation(target, dc, success);
        }
    }

    pub(super) fn note_retry_paced(
        &self,
        target: SocketAddr,
        group_index: usize,
        reason: &'static str,
        backoff_ms: u64,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_retry_paced(target, group_index, reason, backoff_ms);
        }
    }

    pub(super) fn note_route_selected(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        reason: &'static str,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_route_selected(target, group_index, host, reason);
        }
    }

    pub(super) fn note_failure_classified(
        &self,
        target: SocketAddr,
        failure: &RuntimeClassifiedFailure,
        host: Option<&str>,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_failure_classified(target, failure, host);
        }
    }

    pub(super) fn note_route_advanced(
        &self,
        target: SocketAddr,
        previous_group_index: usize,
        next_group_index: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_route_advanced(target, previous_group_index, next_group_index, trigger, host);
        }
    }

    pub(super) fn note_adaptive_override(
        &self,
        target: SocketAddr,
        group_index: usize,
        trigger: u32,
        failure_class: &'static str,
        host: Option<&str>,
        reason: &'static str,
    ) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_adaptive_override(target, group_index, trigger, failure_class, host, reason);
        }
    }

    pub(super) fn note_upstream_connected(&self, upstream_addr: SocketAddr, upstream_rtt_ms: Option<u64>) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_upstream_connected(upstream_addr, upstream_rtt_ms);
        }
    }

    pub(super) fn note_quic_migration_status(&self, target: SocketAddr, status: &'static str, reason: &'static str) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_quic_migration_status(target, status, reason);
        }
    }

    pub(super) fn note_tls_handshake_completed(&self, target: SocketAddr, elapsed_ms: u64) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_tls_handshake_completed(target, elapsed_ms);
        }
    }

    pub(super) fn resolve_encrypted_dns_host(
        &self,
        host: &str,
        protect_path: Option<&str>,
        ipv6_enabled: bool,
    ) -> io::Result<SocketAddr> {
        runtime_resolve_host_via_encrypted_dns(host, self.runtime_context.as_ref(), protect_path, ipv6_enabled)
    }

    pub(super) fn resolve_ws_tunnel_addr(&self, dc: RuntimeTelegramDc) -> io::Result<SocketAddr> {
        runtime_resolve_ws_tunnel_addr(
            dc,
            self.runtime_context.as_ref(),
            self.ws_tunnel_settings.protect_path.as_deref(),
        )
    }

    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    pub(super) fn io_uring_driver(&self) -> Option<&std::sync::Arc<ripdpi_io_uring::IoUringDriver>> {
        self.io_uring.as_ref()
    }

    pub(super) fn note_direct_path_transport_attempt(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: RuntimeTransportProtocol,
    ) {
        DirectPathLearningPort::note_direct_path_transport_attempt(&self.services, host, targets, transport);
    }

    pub(super) fn preferred_targets_for_transport(
        &self,
        original_target: SocketAddr,
        host: Option<&str>,
        transport: RuntimeTransportProtocol,
        now_ms: i64,
    ) -> Vec<SocketAddr> {
        let decision = AdaptiveContextPort::preferred_targets(
            &self.services,
            self.runtime_context.as_ref(),
            original_target,
            host,
            transport,
            now_ms,
        );
        if decision.suppressed_udp {
            self.note_direct_path_udp_suppressed(host, &decision.suppressed_targets, now_ms.max(0) as u64);
        }
        decision.targets
    }

    pub(super) fn note_direct_path_udp_suppressed(&self, host: Option<&str>, targets: &[SocketAddr], now_ms: u64) {
        DirectPathLearningPort::note_direct_path_udp_suppressed(&self.services, host, targets, now_ms);
    }

    pub(super) fn note_direct_path_udp_failure(&self, host: Option<&str>, targets: &[SocketAddr]) {
        DirectPathLearningPort::note_direct_path_udp_failure(&self.services, host, targets);
    }

    pub(super) fn note_direct_path_quic_success(&self, host: Option<&str>, targets: &[SocketAddr]) {
        let observer = self.direct_path_observer();
        DirectPathLearningPort::note_direct_path_quic_success(
            &self.services,
            host,
            targets,
            observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
        );
    }

    pub(super) fn note_direct_path_tcp_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        strategy_family: Option<&str>,
    ) {
        let observer = self.direct_path_observer();
        DirectPathLearningPort::note_direct_path_tcp_success(
            &self.services,
            host,
            targets,
            strategy_family,
            observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
        );
    }

    pub(super) fn note_direct_path_tls_post_client_hello_failure(&self, host: Option<&str>, targets: &[SocketAddr]) {
        DirectPathLearningPort::note_direct_path_tls_post_client_hello_failure(&self.services, host, targets);
    }

    pub(super) fn note_direct_path_all_ips_failed(&self, host: Option<&str>, targets: &[SocketAddr]) {
        let observer = self.direct_path_observer();
        DirectPathLearningPort::note_direct_path_all_ips_failed(
            &self.services,
            host,
            targets,
            observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
        );
    }

    pub(super) fn emit_due_direct_path_learning_timeouts(&self, now_ms: u64) {
        let observer = self.direct_path_observer();
        DirectPathLearningPort::emit_due_direct_path_learning_timeouts(
            &self.services,
            now_ms,
            observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
        );
    }

    fn direct_path_observer(&self) -> Option<RuntimeTelemetryDirectPathObserver<'_>> {
        self.telemetry.as_deref().map(RuntimeTelemetryDirectPathObserver)
    }

    pub(super) fn reprobe_reset_handle(&self) -> ReprobeResetHandle {
        reprobe_reset_handle(&self.services)
    }

    #[cfg(test)]
    pub(super) fn test(config: RuntimeConfig) -> Self {
        Self::test_with_context(config, None)
    }

    #[cfg(test)]
    pub(super) fn test_with_context(config: RuntimeConfig, runtime_context: Option<ProxyRuntimeContext>) -> Self {
        Self::test_with_telemetry_and_context(config, None, runtime_context)
    }

    #[cfg(test)]
    pub(super) fn test_with_telemetry(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    ) -> Self {
        Self::test_with_telemetry_and_context(config, telemetry, None)
    }

    #[cfg(test)]
    fn test_with_telemetry_and_context(
        config: RuntimeConfig,
        telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        let handle = new_services_handle(config.clone(), telemetry.clone(), runtime_context.clone());
        let RuntimeConfigProjection {
            listener_settings,
            handshake_settings,
            delayed_connect_settings,
            network_reprobe_settings,
            ws_tunnel_settings,
            warmup_probe_settings,
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            udp_group_settings,
            route_payload_matcher,
            udp_flow_limit,
            relay_group_settings,
            relay_first_response,
            response_failure_evidence_settings,
        } = runtime_config_projection(&config);
        let RuntimeSessionProjection {
            udp_packet_parser,
            udp_payload_classifier,
            relay_host_extractor,
            first_outbound_payload_policy,
        } = runtime_session_projection(&config);
        let RuntimeDesyncProjection { tcp_desync_executor, udp_desync_planner } = runtime_desync_projection(&config);
        let RuntimeResponseProjection { first_response_exchange_policy } = runtime_response_projection(&config);

        Self {
            listener_settings,
            handshake_settings,
            delayed_connect_settings,
            network_reprobe_settings,
            ws_tunnel_settings,
            warmup_probe_settings,
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            tcp_desync_executor,
            udp_group_settings,
            route_payload_matcher,
            udp_desync_planner,
            udp_flow_limit,
            udp_packet_parser,
            udp_payload_classifier,
            relay_group_settings,
            relay_host_extractor,
            relay_first_response,
            first_outbound_payload_policy,
            first_response_exchange_policy,
            response_failure_evidence_settings,
            services: handle,
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry,
            runtime_context,
            control: None,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(NetworkReprobeTracker::new()),
            pcap_hook: None,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }
}

pub(super) struct ClientSlotGuard {
    active: Arc<AtomicUsize>,
}

impl ClientSlotGuard {
    pub(super) fn acquire(active: Arc<AtomicUsize>, limit: usize) -> Option<Self> {
        loop {
            let current = active.load(Ordering::Relaxed);
            if current >= limit {
                return None;
            }
            if active.compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Relaxed).is_ok() {
                return Some(Self { active });
            }
        }
    }
}

impl Drop for ClientSlotGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}

fn resolve_localhost(host: &str, ipv6_enabled: bool) -> Option<SocketAddr> {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    if !host.eq_ignore_ascii_case("localhost") && !host.eq_ignore_ascii_case("localhost.") {
        return None;
    }

    let ip = if ipv6_enabled { IpAddr::V6(Ipv6Addr::LOCALHOST) } else { IpAddr::V4(Ipv4Addr::LOCALHOST) };
    Some(SocketAddr::new(ip, 0))
}
