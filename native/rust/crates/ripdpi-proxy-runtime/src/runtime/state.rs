use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use std::collections::BTreeMap;
use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream, UdpSocket};
use std::time::Duration;

use super::config::{
    connection_route_requests_direct_syn_data_tfo_with, delayed_route_matches_payload_with, ensure_default_ttl,
    first_response_timeout, first_response_timeout_count_limit, listener_settings, primary_tcp_strategy_family_with,
    relay_group_settings_with, route_matches_transport_payload_with, route_requires_delay_payload_with,
    runtime_config_projection_with_geo, should_rebind_udp_source_port_with, tcp_rotation_seed_with,
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
use super::failure::{
    runtime_block_signal_from_failure, runtime_classify_first_response_closed_before_response,
    runtime_classify_first_response_partial_tls_timeout, runtime_classify_probe_connect_error,
    runtime_classify_probe_read_error, runtime_classify_probe_tls_response, runtime_classify_probe_write_error,
    runtime_classify_quic_probe, runtime_classify_relay_connection_freeze, runtime_classify_response_failure,
    runtime_classify_strategy_execution_failure, runtime_classify_transport_error,
    runtime_classify_warmup_closed_before_response, runtime_classify_warmup_first_response_error,
    runtime_classify_warmup_send_error, runtime_response_requires_dns_tampering_evidence,
    runtime_should_track_strategy_target, RuntimeBlockSignal, RuntimeClassifiedFailure, RuntimeDnsTamperingEvidence,
    RuntimeFailureAction, RuntimeFailureClass, RuntimeFailureStage, RuntimeProbeResult,
};
use super::payload::{
    runtime_build_probe_client_hello, runtime_first_response_boundary_tracker,
    runtime_outbound_tls_client_hello_assembler, RuntimeFirstResponseBoundaryTracker,
    RuntimeOutboundTlsClientHelloAssembler,
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
    read_upstream_socks_reply, runtime_classify_udp_payload, runtime_parse_socks5_udp_packet,
    runtime_session_projection, validate_http_proxy_auth, FirstOutboundPayloadPolicy, OutboundPayloadInfo,
    PayloadHostExtractor, ProxyReply, RuntimeSessionProjection, SocketType, UdpPacketParser, UdpPayloadClassifier,
    UdpPayloadInfo, S_ATP_I4, S_ATP_I6, S_AUTH_BAD, S_AUTH_NONE, S_AUTH_USERPASS, S_ER_CMD, S_ER_GEN, S_VER5,
};
use super::types::{
    runtime_classify_first_outbound_payload, runtime_client_request, runtime_outbound_progress, runtime_session_error,
    RuntimeClientRequest, RuntimeConnectionRoute, RuntimeOutboundProgress, RuntimeProxyProtocolMode,
    RuntimeRelayGroupSettings, RuntimeRelayRotationSeed, RuntimeRelayTimeouts, RuntimeRetrySelectionPenalty,
    RuntimeRouteAdvance, RuntimeSessionError, RuntimeSessionState, RuntimeTransportProtocol,
};
use super::udp::{
    runtime_udp_packet_settings, RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy,
    UdpFlowGroupPolicy,
};
use super::ws::{
    runtime_classify_mtproto_seed, runtime_detect_telegram_dc, runtime_encrypted_dns_ip_answers_for_host,
    runtime_relay_ws_tunnel, runtime_resolve_host_via_encrypted_dns, runtime_resolve_ws_tunnel_addr,
    runtime_should_ws_tunnel_fallback, runtime_should_ws_tunnel_first, runtime_telegram_dc_host,
    runtime_ws_tunnel_config, RuntimeEncryptedDnsIpAnswers, RuntimeTelegramDc, RuntimeWsTunnelConfig,
    WsSeedClassification,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{NetworkReprobeTracker, NetworkSnapshot, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink,
};
use ripdpi_proxy_runtime_adapter::model::services::GeoMatcher;
use ripdpi_proxy_runtime_adapter::model::services::{
    new_services_handle, reprobe_reset_handle, ReprobeResetHandle, ServicesStateHandle,
};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;
use ripdpi_proxy_runtime_adapter::raw_packet_requirements::{
    raw_packet_requirements, validate_ip_fragmentation_support,
};
pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

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
    geo_matcher: Option<std::sync::Arc<dyn GeoMatcher + Send + Sync>>,
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

mod adaptive;
mod control;
mod desync;
mod direct_path;
mod failure;
mod handshake;
mod listener;
mod payload;
mod relay;
mod retry;
mod routing;
mod services;
mod session;
mod telemetry;
mod test_support;
mod udp;
mod warmup;
mod ws;

pub(super) use listener::ClientSlotGuard;
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
        let geo_matcher = super::geo::load_runtime_geo_matcher(&config);

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
        } = runtime_config_projection_with_geo(&config, geo_matcher.clone());
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
            geo_matcher,
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
}
