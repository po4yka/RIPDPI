use crate::exit_ip_cap::{ExitIpSessionCaps, ExitIpSessionGuard, ExitIpSessionLimiter};
use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use crate::{SameSniProfileCaps, SameSniProfileGuard, SameSniProfileLimiter};
use ripdpi_proxy_runtime_adapter::model::session::TargetAddr;
use std::collections::BTreeMap;
use std::io::{self, Read};
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::time::Duration;
use std::time::{SystemTime, UNIX_EPOCH};

/// Transport id used when accounting per-exit-IP concurrent outbound TCP
/// sessions through [`ExitIpSessionLimiter`].
const EXIT_SESSION_TRANSPORT_TCP: &str = "tcp";

use super::config::{
    DETECT_CONNECT, DelayedConnectSettings, FirstResponseSettings, ListenerSettings, NetworkReprobeSettings,
    ProxyHandshakeSettings, ProxyProtocolMode, RelayGroupSettingsTable, ResponseFailureEvidenceSettings,
    RoutePayloadMatcher, RuntimeConfig, RuntimeConfigProjection, TcpRouteConnectSettingsTable, TcpRouteRetrySettings,
    TcpRouteSynDataSettings, UdpGroupSettingsTable, WarmupProbeSettings, WsTunnelSettings,
    connection_route_requests_direct_syn_data_tfo_with, delayed_route_matches_payload_with, ensure_default_ttl,
    first_response_timeout, first_response_timeout_count_limit, listener_settings, primary_tcp_strategy_family_with,
    relay_group_settings_with, route_matches_transport_payload_with, route_requires_delay_payload_with,
    runtime_config_projection_with_geo, should_rebind_udp_source_port_with, tcp_rotation_seed_with,
    tcp_route_connect_settings_with, udp_flow_at_capacity, udp_group_settings_with,
};
use super::destination_routing::{DestinationEgress, DestinationRoutingEvaluator};
use super::desync::{
    DesyncSendRequest, OutboundSendError, OutboundSendOutcome, RuntimeDesyncProjection, TcpDesyncExecutionContext,
    TcpDesyncExecutor, TcpExecutionDisposition, TcpExecutionReceipt, TcpFallbackReason, TcpOffsetMarkerBase,
    TcpStrategyFamily, TcpTerminalReason, UdpActionExecContext, UdpDesyncAction, UdpDesyncPlanContext,
    UdpDesyncPlanRequest, UdpDesyncPlanner, UdpExecutionError, UdpExecutionOutcome, execute_udp_actions,
    plan_udp_actions_for_runtime, runtime_desync_projection, send_tcp_desync_payload,
};
use super::failure::{
    RuntimeBlockSignal, RuntimeClassifiedFailure, RuntimeDnsTamperingEvidence, RuntimeFailureAction,
    RuntimeFailureClass, RuntimeFailureStage, RuntimeProbeResult, runtime_block_signal_from_failure,
    runtime_classify_first_response_closed_before_response, runtime_classify_first_response_partial_tls_timeout,
    runtime_classify_probe_connect_error, runtime_classify_probe_read_error, runtime_classify_probe_tls_response,
    runtime_classify_probe_write_error, runtime_classify_quic_probe, runtime_classify_relay_connection_freeze,
    runtime_classify_response_failure, runtime_classify_strategy_execution_failure, runtime_classify_transport_error,
    runtime_classify_warmup_closed_before_response, runtime_classify_warmup_first_response_error,
    runtime_classify_warmup_send_error, runtime_response_requires_dns_tampering_evidence,
    runtime_should_track_strategy_target,
};
use super::payload::{
    RuntimeFirstResponseBoundaryTracker, RuntimeOutboundTlsClientHelloAssembler, runtime_build_probe_client_hello,
    runtime_first_response_boundary_tracker, runtime_outbound_tls_client_hello_assembler,
};
use super::ports::{
    AdaptiveContextPort, AdaptiveFeedbackPort, DirectPathLearningObserver, DirectPathLearningPort, PolicyPort,
    RetryPacingPort,
};
use super::response::{
    RuntimeFirstResponseExchangePolicy, RuntimeResponseProjection, runtime_failure_penalizes_strategy,
    runtime_failure_trigger_mask, runtime_first_response_exchange_required, runtime_response_projection,
};
#[cfg(test)]
use super::response::{RuntimeTriggerEvent, runtime_response_trigger_flag, runtime_response_trigger_supported};
#[cfg(test)]
use super::session::runtime_parse_socks5_udp_packet;
use super::session::{
    FirstOutboundPayloadPolicy, OutboundPayloadInfo, ParsedSocks5UdpPacket, PayloadHostExtractor, ProxyReply,
    RuntimeSessionProjection, S_ATP_I4, S_ATP_I6, S_AUTH_BAD, S_AUTH_NONE, S_AUTH_USERPASS, S_ER_CMD, S_ER_CONN,
    S_ER_GEN, S_ER_HOST, S_ER_NET, S_ER_TTL, S_VER5, SocketType, UdpPacketParser, UdpPayloadClassifier, UdpPayloadInfo,
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, encode_socks5_udp_packet,
    encode_upstream_socks_connect, extract_payload_host_with, has_inbound_payload, new_session_state,
    observe_datagram_outbound_payload, observe_first_response_payload, observe_inbound_payload,
    observe_outbound_payload, observe_retry_response_payload, outbound_payload_count_this_round,
    parse_http_connect_request, parse_shadowsocks_target, parse_socks4_request, parse_socks5_request,
    read_upstream_socks_reply, runtime_classify_udp_payload, runtime_parse_socks5_udp_packet_with_host,
    runtime_session_projection, validate_http_proxy_auth,
};
use super::types::{
    RuntimeClientRequest, RuntimeConnectionRoute, RuntimeOutboundProgress, RuntimeProxyProtocolMode,
    RuntimeRelayGroupSettings, RuntimeRelayRotationSeed, RuntimeRelayTimeouts, RuntimeRetrySelectionPenalty,
    RuntimeRouteAdvance, RuntimeSessionError, RuntimeSessionState, RuntimeTransportProtocol,
    runtime_classify_first_outbound_payload, runtime_client_request, runtime_outbound_progress, runtime_session_error,
};
use super::udp::{
    RuntimeUdpPacketSettings, RuntimeUdpSocketSettings, RuntimeUdpSourceRebindPolicy, UdpFlowGroupPolicy,
    runtime_udp_packet_settings,
};
use super::ws::{
    RuntimeEncryptedDnsIpAnswers, RuntimeTelegramDc, RuntimeWsTunnelConfig, WsSeedClassification,
    runtime_classify_mtproto_seed, runtime_detect_telegram_dc, runtime_encrypted_dns_ip_answers_for_host,
    runtime_relay_ws_tunnel, runtime_resolve_host_via_encrypted_dns, runtime_resolve_ws_tunnel_addr,
    runtime_should_ws_tunnel_fallback, runtime_should_ws_tunnel_first, runtime_telegram_dc_host,
    runtime_ws_tunnel_config,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{NetworkReprobeTracker, NetworkSnapshot, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    EmbeddedProxyControl, RuntimeTelemetrySink, current_runtime_telemetry,
};
use ripdpi_proxy_runtime_adapter::model::services::GeoMatcher;
use ripdpi_proxy_runtime_adapter::model::services::{
    ReprobeResetHandle, RuntimeDecisionEngine, RuntimeDecisionInputs, ServicesStateHandle, new_decision_engine,
    new_services_handle, reprobe_reset_handle,
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
    destination_routing: DestinationRoutingEvaluator,
    services: ServicesStateHandle,
    /// Single decision boundary that proxy-runtime now composes through —
    /// see `ripdpi-runtime-decision-engine`. Today this engine delegates
    /// to the existing `services` ports byte-identically; consumers reach
    /// for it instead of touching individual port traits so future
    /// enrichment lands behind one seam, not at every call site. The
    /// `dead_code` allow is the migration anchor: the field is held by
    /// the state so per-flow routing call sites can switch to
    /// `decide_flow_route` without further plumbing, and the startup
    /// `on_runtime_decision_snapshot` telemetry hook already consumes
    /// the per-runtime decision the engine produces.
    #[allow(dead_code)]
    decision_engine: RuntimeDecisionEngine,
    active_clients: Arc<AtomicUsize>,
    active_tcp_sockets: ActiveSocketRegistry,
    active_upstream_tcp_sockets: ActiveSocketRegistry,
    telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    runtime_context: Option<ProxyRuntimeContext>,
    control: Option<std::sync::Arc<EmbeddedProxyControl>>,
    /// Session-level flag: once any connection discovers that per-socket TTL
    /// modification is rejected by the kernel (EROFS on Android), all
    /// subsequent connections skip TTL desync actions immediately.
    ttl_unavailable: Arc<AtomicBool>,
    /// Tracks network scope key changes for lightweight re-probing.
    reprobe_tracker: std::sync::Arc<NetworkReprobeTracker>,
    /// Per-exit-IP concurrent outbound-session admission gate. The limiter is
    /// `Arc`-backed, so every `RuntimeState` clone shares one session counter —
    /// required for the cap to be enforced across all worker threads.
    exit_ip_session_limiter: ExitIpSessionLimiter,
    same_sni_profile_limiter: SameSniProfileLimiter,
    #[allow(
        dead_code,
        reason = "owned-stack connectors use the learned profile key; browser pass-through routes intentionally use the fallback key"
    )]
    selected_tls_profile: String,
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

pub(super) use listener::{ActiveSocketRegistry, ClientSlotGuard};

pub(in crate::runtime) fn now_epoch_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_millis() as u64).unwrap_or_default()
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
        let decision_engine = new_decision_engine(&handle);
        // Compute the per-runtime decision snapshot once at startup. The
        // snapshot derivation today is byte-identical to the previous
        // direct call (the engine is a delegating facade) — see the engine
        // crate's parity test. Telemetry sinks observe it via
        // `on_runtime_decision_snapshot`; pre-versioned sinks fall through
        // to the trait's default no-op.
        let runtime_decision = decision_engine.decide_runtime(&RuntimeDecisionInputs {
            config: None,
            context: runtime_context.as_ref(),
            network_scope_key: None,
        });
        if let Some(ref sink) = telemetry {
            sink.on_runtime_decision_snapshot(&runtime_decision.snapshot);
        }
        let geo_matcher = super::geo::load_runtime_geo_matcher(&config);
        let destination_routing = DestinationRoutingEvaluator::compile(&config.destination_routing);

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
        let (selected_tls_profile, same_sni_caps) = connection_concurrency_runtime_state(runtime_context.as_ref());

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
            destination_routing,
            services: handle,
            decision_engine,
            active_clients: Arc::new(AtomicUsize::new(0)),
            active_tcp_sockets: ActiveSocketRegistry::default(),
            active_upstream_tcp_sockets: ActiveSocketRegistry::default(),
            telemetry,
            runtime_context,
            control,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(NetworkReprobeTracker::new()),
            exit_ip_session_limiter: ExitIpSessionLimiter::new(ExitIpSessionCaps::default()),
            same_sni_profile_limiter: SameSniProfileLimiter::new(same_sni_caps),
            selected_tls_profile,
            pcap_hook: None,
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }

    /// Try to reserve a per-exit-IP concurrent-session slot for an outbound TCP
    /// connection. Returns a RAII guard (released on drop) or `None` at cap.
    pub(super) fn try_acquire_exit_session(&self, exit_ip: IpAddr) -> Option<ExitIpSessionGuard> {
        self.exit_ip_session_limiter.try_acquire(exit_ip, EXIT_SESSION_TRANSPORT_TCP)
    }

    #[allow(
        dead_code,
        reason = "owned-stack connectors use this entry point; browser pass-through routes intentionally retain the fallback cap"
    )]
    pub(super) fn try_acquire_owned_sni_session(&self, sni: &str) -> Option<SameSniProfileGuard> {
        self.same_sni_profile_limiter.try_acquire(sni, &self.selected_tls_profile)
    }

    pub(super) fn try_acquire_pass_through_sni_session(&self, sni: &str) -> Option<SameSniProfileGuard> {
        self.same_sni_profile_limiter.try_acquire(sni, "pass-through")
    }

    /// Number of in-flight outbound sessions counted for `exit_ip` (test/diagnostic).
    #[cfg(all(test, not(feature = "loom")))]
    pub(super) fn active_exit_sessions(&self, exit_ip: IpAddr) -> usize {
        self.exit_ip_session_limiter.active(exit_ip, EXIT_SESSION_TRANSPORT_TCP)
    }
}

fn connection_concurrency_runtime_state(runtime_context: Option<&ProxyRuntimeContext>) -> (String, SameSniProfileCaps) {
    let policy = runtime_context.and_then(|context| context.connection_concurrency.as_ref());
    let selected_profile = policy.map_or_else(|| "unknown".to_string(), |policy| policy.selected_profile_id.clone());
    let caps = policy.map_or_else(SameSniProfileCaps::default, |policy| {
        policy
            .per_profile_caps
            .iter()
            .fold(SameSniProfileCaps::default(), |caps, (profile, cap)| caps.with_profile(profile, usize::from(*cap)))
    });
    (selected_profile, caps)
}

#[cfg(test)]
mod state_coverage_tests {
    use super::*;
    use ripdpi_proxy_runtime_adapter::desync_platform::{TcpExecutionReceipt, TcpTerminalReason};
    use ripdpi_proxy_runtime_adapter::failure::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};
    use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
    use std::io::Cursor;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
    use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};

    fn state() -> RuntimeState {
        RuntimeState::test(RuntimeConfig::default())
    }

    #[derive(Default)]
    struct CountingTelemetry {
        bytes: AtomicU64,
    }

    impl RuntimeTelemetrySink for CountingTelemetry {
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

        fn on_listener_stopped(&self) {}

        fn on_client_accepted(&self) {}

        fn on_client_finished(&self) {}

        fn on_client_error(&self, _error: &io::Error) {}

        fn on_route_selected(
            &self,
            _target: SocketAddr,
            _group_index: usize,
            _host: Option<&str>,
            _phase: &'static str,
        ) {
        }

        fn on_failure_classified(&self, _target: SocketAddr, _failure: &ClassifiedFailure, _host: Option<&str>) {}

        fn on_route_advanced(
            &self,
            _target: SocketAddr,
            _from_group: usize,
            _to_group: usize,
            _trigger: u32,
            _host: Option<&str>,
        ) {
        }

        fn on_host_autolearn_state(
            &self,
            _enabled: bool,
            _learned_host_count: usize,
            _penalized_host_count: usize,
            _blocked_host_count: usize,
            _last_block_signal: Option<&str>,
            _last_block_provider: Option<&str>,
        ) {
        }

        fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}

        fn on_upstream_application_bytes_forwarded(&self, bytes: u64, _epoch_ms: u64) {
            self.bytes.fetch_add(bytes, AtomicOrdering::Relaxed);
        }
    }

    #[test]
    fn upstream_application_send_result_records_partial_strategy_execution_bytes() {
        let telemetry = std::sync::Arc::new(CountingTelemetry::default());
        let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), Some(telemetry.clone()));
        let result = Err(OutboundSendError::StrategyExecution {
            action: "split",
            strategy_family: "tls_record_split",
            fallback: None,
            bytes_committed: 37,
            source_errno: None,
            execution_receipt: test_strategy_execution_receipt("tls_record_split", 37),
            source: io::Error::new(io::ErrorKind::BrokenPipe, "partial write"),
        });

        state.note_upstream_application_send_result(&result);

        assert_eq!(telemetry.bytes.load(AtomicOrdering::Relaxed), 37);
    }

    fn test_strategy_execution_receipt(
        strategy_family: &'static str,
        bytes_committed: usize,
    ) -> Box<TcpExecutionReceipt> {
        Box::new(TcpExecutionReceipt::failed_strategy_execution(
            Some(strategy_family),
            0,
            0,
            0,
            0,
            bytes_committed,
            TcpTerminalReason::StrategyExecution,
        ))
    }

    #[test]
    fn runtime_context_configures_selected_profile_cap_without_changing_exit_ip_cap() {
        let mut caps = BTreeMap::new();
        caps.insert("firefox_stable".to_string(), 2);
        let context = ProxyRuntimeContext {
            connection_concurrency: Some(
                ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyConnectionConcurrencyPolicy {
                    selected_profile_id: "firefox_stable".to_string(),
                    per_profile_caps: caps,
                },
            ),
            ..ProxyRuntimeContext::default()
        };
        let state = RuntimeState::test_with_context(RuntimeConfig::default(), Some(context));

        let first = state.try_acquire_owned_sni_session("Example.COM.").expect("first profile slot");
        let second = state.try_acquire_owned_sni_session("example.com").expect("second profile slot");
        assert!(state.try_acquire_owned_sni_session("example.com").is_none());
        let pass_through = (0..8)
            .map(|_| state.try_acquire_pass_through_sni_session("example.com").expect("pass-through slot"))
            .collect::<Vec<_>>();
        assert!(state.try_acquire_pass_through_sni_session("example.com").is_none());
        let exit_ip = IpAddr::V4(Ipv4Addr::new(198, 51, 100, 7));
        let exit_guard = state.try_acquire_exit_session(exit_ip).expect("exit-IP admission remains independent");
        drop((first, second, pass_through, exit_guard));
    }

    #[test]
    fn state_facade_covers_listener_handshake_session_and_udp_helpers() {
        let state = state();
        assert!(RuntimeState::listener_bind_addr(&RuntimeConfig::default()).port() > 0);
        assert!(RuntimeState::validate_runtime_requirements(&RuntimeConfig::default()).is_ok());
        let mut ttl_config = RuntimeConfig::default();
        ttl_config.network.default_ttl = 0;
        RuntimeState::ensure_config_default_ttl(&mut ttl_config, || Ok(77)).expect("default ttl detected");
        assert_eq!(ttl_config.network.default_ttl, 77);

        assert!(state.listener_client_capacity() > 0);
        assert!(state.listener_route_group_count() > 0);
        let slot = state.acquire_client_slot(1).expect("slot available");
        assert!(state.acquire_client_slot(1).is_none());
        drop(slot);
        assert!(state.acquire_client_slot(1).is_some());

        assert!(matches!(state.proxy_protocol_mode(), RuntimeProxyProtocolMode::BytePrefixed { .. }));
        assert_eq!(state.proxy_auth_token(), None);
        let _ = state.udp_associate_enabled();
        assert_eq!(state.handshake_protect_path(), None);
        assert!(!state.delayed_connect_enabled());
        assert!(state.delayed_connect_buffer_size() > 0);
        assert_eq!(RuntimeState::upstream_socks_auth_request(), [S_VER5, 1, S_AUTH_NONE]);
        assert!(RuntimeState::upstream_socks_auth_accepted([S_VER5, S_AUTH_NONE]));
        assert!(!RuntimeState::upstream_socks_auth_accepted([S_VER5, S_AUTH_BAD]));
        assert!(RuntimeState::upstream_socks_connect_succeeded(&[S_VER5, 0, 0, S_ATP_I4, 127, 0, 0, 1, 0, 80]));
        // Reply VER must be SOCKS5 even when REP signals success.
        assert!(!RuntimeState::upstream_socks_connect_succeeded(&[0x04, 0, 0, S_ATP_I4, 127, 0, 0, 1, 0, 80]));
        assert!(RuntimeState::upstream_socks_connect_succeeded(&[S_VER5, 0]));
        assert_eq!(RuntimeState::socks5_auth_selection(None, &[S_AUTH_NONE]), ([S_VER5, S_AUTH_NONE], true));
        assert!(RuntimeState::socks5_auth_selection(Some("token"), &[S_AUTH_USERPASS]).1);
        assert!(RuntimeState::is_socks5_version(S_VER5));
        assert_eq!(RuntimeState::socks5_command_unsupported_code(), S_ER_CMD);
        assert_eq!(RuntimeState::socks5_general_failure_code(), S_ER_GEN);
        assert_eq!(RuntimeState::socks5_fixed_address_tail_len(S_ATP_I4), Some(6));
        assert_eq!(RuntimeState::socks5_fixed_address_tail_len(S_ATP_I6), Some(18));
        assert!(RuntimeState::is_socks5_domain_address_type(0x03));
        assert!(RuntimeState::encode_socks4_reply(true).as_bytes().len() >= 8);
        assert!(
            RuntimeState::encode_socks5_reply(0, SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 80))
                .as_bytes()
                .starts_with(&[S_VER5, 0])
        );
        assert!(RuntimeState::encode_http_connect_reply(true).as_bytes().starts_with(b"HTTP/1.1 200"));
        assert!(
            RuntimeState::encode_upstream_socks_connect(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443))
                .starts_with(&[S_VER5, 1, 0])
        );
        let mut socks_reply = Cursor::new([S_VER5, 0, 0, S_ATP_I4, 127, 0, 0, 1, 0, 80]);
        assert!(
            RuntimeState::read_upstream_socks_reply(&mut socks_reply).expect("read reply").starts_with(&[S_VER5, 0])
        );
        assert!(state.resolve_proxy_name("localhost", SocketType::Stream).is_some());
        assert!(state.resolve_handshake_name("localhost").is_some());
        assert!(
            RuntimeState::parse_http_connect_client_request(
                b"CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n",
                |host| host.parse::<IpAddr>().ok().map(|ip| SocketAddr::new(ip, 0))
            )
            .is_ok()
        );
        assert!(!RuntimeState::validate_http_proxy_auth(b"", "token"));
        assert!(state.parse_shadowsocks_target(&[S_ATP_I4, 127, 0, 0, 1, 0, 80], |_| None).is_some());

        let mut session = RuntimeState::new_session_state();
        assert!(!RuntimeState::session_has_inbound_payload(&session));
        RuntimeState::observe_session_inbound_payload(&mut session, b"hello");
        assert!(RuntimeState::session_has_inbound_payload(&session));
        let progress = RuntimeState::observe_session_outbound_payload(&mut session, b"GET / HTTP/1.1\r\n\r\n");
        assert_eq!(progress.payload_size, 18);
        assert_eq!(RuntimeState::single_payload_progress(7).payload_size, 7);
        RuntimeState::observe_session_datagram_outbound_payload(&mut session, b"payload");
        RuntimeState::observe_session_first_response_payload(&mut session, b"HTTP/1.1 200 OK\r\n\r\n");
        RuntimeState::observe_session_retry_response_payload(&mut session, b"HTTP/1.1 403 Forbidden\r\n\r\n");
        let _ = RuntimeState::outbound_payload_count_this_round(&session);
        assert!(RuntimeState::session_round_count(&session) > 0);

        let udp_payload = b"\0\0\0\x01\x7f\0\0\x01\0\x35abc";
        let parsed = state
            .parse_socks5_udp_packet(udp_payload, |host, _socket_type| {
                host.parse::<IpAddr>().ok().map(|ip| SocketAddr::new(ip, 0))
            })
            .expect("parse udp packet");
        assert_eq!(parsed.1, b"abc");
        assert!(RuntimeState::encode_socks5_udp_packet(parsed.0, parsed.1).ends_with(b"abc"));
        assert_eq!(state.classify_udp_payload(b"").host, None);
        assert!(state.udp_flow_limit() > 0);
        assert!(RuntimeState::udp_flow_at_capacity(false, state.udp_flow_limit(), state.udp_flow_limit()));
        let udp_policy = state.udp_flow_group_policy(0, DestinationEgress::Tunneled).expect("udp policy");
        assert!(!RuntimeState::should_rebind_udp_flow_source_port(udp_policy.source_rebind, false, 0, b""));
    }

    #[test]
    fn socks5_reply_code_for_kind_maps_each_connect_failure() {
        use std::io::ErrorKind;

        assert_eq!(RuntimeState::socks5_reply_code_for_kind(ErrorKind::ConnectionRefused), S_ER_CONN);
        assert_eq!(RuntimeState::socks5_reply_code_for_kind(ErrorKind::HostUnreachable), S_ER_HOST);
        assert_eq!(RuntimeState::socks5_reply_code_for_kind(ErrorKind::NetworkUnreachable), S_ER_NET);
        assert_eq!(RuntimeState::socks5_reply_code_for_kind(ErrorKind::TimedOut), S_ER_TTL);
        // Unclassified kinds fall back to general failure.
        assert_eq!(RuntimeState::socks5_reply_code_for_kind(ErrorKind::PermissionDenied), S_ER_GEN);
        assert_eq!(RuntimeState::socks5_reply_code_for_kind(ErrorKind::Other), S_ER_GEN);
    }

    #[test]
    fn state_facade_covers_routing_relay_warmup_and_failure_helpers() {
        let state = state();
        let target = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443);
        let route = RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 };
        let failure = ClassifiedFailure::new(
            FailureClass::TcpReset,
            FailureStage::FirstResponse,
            FailureAction::RetryWithMatchingGroup,
            "reset",
        );

        assert!(state.route_requires_delay_payload(0).is_ok());
        assert!(state.delayed_route_matches_payload(
            0,
            target,
            b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            Some("example.com")
        ));
        assert!(state.route_matches_transport_payload(
            0,
            target,
            b"GET / HTTP/1.1\r\n\r\n",
            RuntimeTransportProtocol::Tcp
        ));
        assert!(!state.route_uses_direct_syn_data_tfo(&route, Some(b"GET / HTTP/1.1\r\n\r\n")));
        let policy = state
            .route_connect_policy(0, Some(b"GET / HTTP/1.1\r\n\r\n"), true, DestinationEgress::Tunneled)
            .expect("route policy");
        assert!(policy.connect_timeout.is_some());
        assert!(
            state
                .select_initial_route(
                    target,
                    Some(b"GET / HTTP/1.1\r\n\r\n"),
                    None,
                    true,
                    RuntimeTransportProtocol::Tcp
                )
                .is_some()
        );
        assert!(
            state
                .select_next_route(
                    &route,
                    target,
                    Some(b"GET / HTTP/1.1\r\n\r\n"),
                    None,
                    RuntimeTransportProtocol::Tcp,
                    RuntimeState::connect_failure_trigger(),
                    false,
                    None,
                )
                .is_none()
        );
        let connect_trigger = RuntimeState::connect_failure_trigger();
        let _ = state.runtime_supports_trigger(connect_trigger);
        let _ = state.retry_trigger_for_failure(&failure);
        assert!(RuntimeState::should_track_strategy_target(target));
        state.note_block_signal_for_failure(Some("example.com"), &failure, None);
        state.note_block_signal("example.com", RuntimeBlockSignal::TcpReset, None, true);
        assert!(
            state
                .advance_route(
                    &route,
                    RuntimeRouteAdvance {
                        dest: target,
                        payload: Some(b"GET / HTTP/1.1\r\n\r\n"),
                        transport: RuntimeTransportProtocol::Tcp,
                        trigger: connect_trigger,
                        can_reconnect: false,
                        host: Some("example.com".to_string()),
                        penalize_strategy_failure: true,
                        retry_penalties: None,
                    },
                )
                .is_ok()
        );
        state.store_udp_route_hint(target, route.group_index, route.attempted_mask, Some("example.com".to_string()));

        assert!(state.relay_group(0).is_ok());
        assert!(state.relay_rotation_seed(0).is_ok());
        assert!(state.relay_first_response_buffer_size() > 0);
        let tracker = state.relay_first_response_boundary_tracker(b"GET / HTTP/1.1\r\n\r\n");
        let _ = state.relay_first_response_timeout(&tracker);
        assert!(state.relay_first_response_timeout_count_limit() >= 0);
        let _ = state.relay_first_response_reports_timeout_failure();
        assert!(state.first_outbound_payload_buffer_size() > 0);
        assert!(state.first_response_exchange_required().is_ok());
        assert!(state.primary_tcp_strategy_family(0).is_none_or(|family| !family.is_empty()));
        assert_eq!(
            state.extract_relay_payload_host(b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"),
            Some("example.com".to_string())
        );
        assert_eq!(
            RuntimeState::classify_relay_connection_freeze(RuntimeRelayTimeouts {
                freeze_window_ms: 100,
                freeze_min_bytes: 10,
                freeze_max_stalls: 2,
            })
            .class,
            FailureClass::ConnectionFreeze
        );
        assert!(state.relay_timeouts(0).is_ok());

        assert!(matches!(
            RuntimeState::classify_probe_connect_error(&io::Error::new(io::ErrorKind::TimedOut, "timeout")),
            RuntimeProbeResult::DpiFailure(_)
        ));
        assert!(matches!(
            RuntimeState::classify_probe_write_error(&io::Error::new(io::ErrorKind::ConnectionReset, "reset")),
            RuntimeProbeResult::DpiFailure(_)
        ));
        assert!(matches!(
            RuntimeState::classify_probe_read_error(&io::Error::new(io::ErrorKind::UnexpectedEof, "eof")),
            RuntimeProbeResult::DpiFailure(_)
        ));
        assert_eq!(
            RuntimeState::classify_probe_tls_response([0x16, 0x03, 0x03, 0, 1], Some(0x02)),
            RuntimeProbeResult::Success
        );
        assert!(state.warmup_probe_response_buffer_size() > 0);
        assert!(!state.warmup_probe_scheduler_enabled());
        assert_eq!(
            RuntimeState::classify_warmup_send_error(&io::Error::new(io::ErrorKind::BrokenPipe, "closed")).class,
            FailureClass::TcpReset
        );
        assert_eq!(
            RuntimeState::classify_warmup_first_response_error(&io::Error::new(io::ErrorKind::TimedOut, "timeout"))
                .class,
            FailureClass::SilentDrop
        );
        assert_eq!(RuntimeState::classify_warmup_closed_before_response().class, FailureClass::SilentDrop);
        assert!(RuntimeState::failure_penalizes_strategy(&failure));
        assert_ne!(RuntimeState::failure_trigger_mask(&failure), 0);
        assert!(RuntimeState::udp_flow_timeout_failure().is_some());
        assert_eq!(RuntimeState::silent_drop_failure_class(), FailureClass::SilentDrop);
        assert_eq!(
            RuntimeState::classify_connect_transport_error(&io::Error::new(io::ErrorKind::TimedOut, "timeout")).class,
            FailureClass::SilentDrop
        );
        assert_eq!(
            RuntimeState::classify_first_response_transport_error(&io::Error::new(
                io::ErrorKind::ConnectionReset,
                "reset"
            ))
            .class,
            FailureClass::TcpReset
        );
        assert_eq!(RuntimeState::classify_first_response_closed_before_response().class, FailureClass::SilentDrop);
        assert_eq!(RuntimeState::classify_first_response_partial_tls_timeout().class, FailureClass::SilentDrop);
        assert!(
            state
                .classify_response_failure(
                    target,
                    b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                    b"HTTP/1.1 302 Found\r\nLocation: http://block.example/\r\n\r\n",
                    None,
                )
                .is_some()
        );
    }

    #[test]
    fn state_facade_covers_control_ws_services_and_telemetry_helpers() {
        let state = state();
        let target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(149, 154, 175, 50)), 443);
        let ipv6_target =
            SocketAddr::new(IpAddr::V6("2001:b28:f23f::1".parse::<Ipv6Addr>().expect("parse Telegram v6")), 443);
        let _ = state.network_reprobe_enabled();
        assert_eq!(state.network_reprobe_protect_path(), None);
        assert!(!state.shutdown_requested());
        assert!(!state.has_embedded_control());
        assert_eq!(state.current_network_snapshot(), None);
        assert!(state.block_signal_confirmation_allowed());
        let _ = state.should_reprobe_network(&NetworkSnapshot {
            transport: "wifi".to_string(),
            validated: true,
            private_dns_mode: "system".to_string(),
            ..Default::default()
        });
        assert_eq!(state.should_ws_tunnel_first(target), None);
        assert_eq!(state.should_ws_tunnel_fallback(target), None);
        let ws_config = state.ws_tunnel_config(Some(target));
        assert_eq!(ws_config.resolved_addr, Some(target));
        assert!(matches!(RuntimeState::classify_mtproto_seed(b"GET / HTTP/1.1\r\n"), WsSeedClassification::NotMtproto));
        assert!(RuntimeState::detect_telegram_dc(target).is_some());
        assert_eq!(RuntimeState::detect_telegram_dc(ipv6_target), Some(3));
        assert!(RuntimeState::telegram_dc_host(2).contains("2"));
        assert!(state.telegram_dc_host_hint(target).is_some());
        assert_eq!(state.telegram_dc_host_hint(ipv6_target).as_deref(), Some("telegram-dc3"));
        state.note_telegram_dc_detected(target, 2);
        state.note_ws_tunnel_escalation(target, 2, false);
        let _cleared_entries = state.clear_connection_cache();
        state.drain_autolearn_events();
        state.flush_autolearn_telemetry();
        state.flush_host_store();
        state.reprobe_reset_handle().reset_strategy_state();
        state.note_retry_paced(target, 0, "test", 10);
        state.note_route_selected(target, 0, None, "test");
        state.note_failure_classified(
            target,
            &ClassifiedFailure::new(FailureClass::Unknown, FailureStage::Connect, FailureAction::None, "unknown"),
            None,
        );
        state.note_route_advanced(target, 0, 1, RuntimeState::connect_failure_trigger(), None);
        state.note_adaptive_override(target, 0, RuntimeState::connect_failure_trigger(), "unknown", None, "test");
        state.note_upstream_connected(target, Some(1));
        state.note_quic_migration_status(target, "not_attempted", "test");
        state.note_tls_handshake_completed(target, 1);

        let _ = DesyncGroup::new(0);
    }
}
