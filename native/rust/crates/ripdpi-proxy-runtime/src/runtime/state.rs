use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::desync_platform::{
    tcp_desync_executor, TcpDesyncExecutionContext, TcpDesyncExecutor,
};
use ripdpi_proxy_runtime_adapter::failure::{BlockSignal, FailureClass};
use ripdpi_proxy_runtime_adapter::model::config::{
    connection_route_requests_direct_syn_data_tfo_with, delayed_connect_settings, delayed_route_matches_payload_with,
    first_response_settings, listener_settings, network_reprobe_settings, primary_tcp_strategy_family_with,
    proxy_handshake_settings, relay_group_settings_table, relay_group_settings_with,
    response_failure_evidence_settings, route_matches_transport_payload_with, route_payload_matcher,
    route_requires_delay_payload_with, tcp_rotation_seed_with, tcp_route_connect_settings_table,
    tcp_route_connect_settings_with, tcp_route_retry_settings, tcp_route_syn_data_settings, udp_flow_limit,
    udp_group_settings_table, udp_group_settings_with, warmup_probe_settings, ws_tunnel_settings,
    DelayedConnectSettings, DesyncGroup, FirstResponseSettings, ListenerSettings, NetworkReprobeSettings,
    ProxyHandshakeSettings, RelayGroupSettings, RelayGroupSettingsTable, ResponseFailureEvidenceSettings,
    RotationPolicy, RoutePayloadMatcher, RuntimeConfig, TcpRouteConnectSettings, TcpRouteConnectSettingsTable,
    TcpRouteRetrySettings, TcpRouteSynDataSettings, UdpGroupSettings, UdpGroupSettingsTable, WarmupProbeSettings,
    WsTunnelSettings,
};
use ripdpi_proxy_runtime_adapter::model::decision::{
    ConnectionRoute, RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};
use ripdpi_proxy_runtime_adapter::model::ports::{
    AdaptiveContextPort, AdaptiveFeedbackPort, DirectPathLearningObserver, DirectPathLearningPort, PolicyPort,
    RetryPacingPort,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{NetworkReprobeTracker, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink,
};
use ripdpi_proxy_runtime_adapter::model::services::{
    new_services_handle, reprobe_reset_handle, ReprobeResetHandle, ServicesStateHandle,
};
use ripdpi_proxy_runtime_adapter::model::session::{
    extract_payload_host_with, first_outbound_payload_policy, payload_host_extractor, udp_packet_parser,
    udp_payload_classifier, FirstOutboundPayloadPolicy, PayloadHostExtractor, SocketType, UdpPacketParser,
    UdpPayloadClassifier,
};
use ripdpi_proxy_runtime_adapter::response_triggers::{first_response_exchange_policy, FirstResponseExchangePolicy};
use ripdpi_proxy_runtime_adapter::udp_desync::{udp_desync_planner, UdpDesyncPlanContext, UdpDesyncPlanner};

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
    pub(super) listener_settings: ListenerSettings,
    pub(super) handshake_settings: ProxyHandshakeSettings,
    pub(super) delayed_connect_settings: DelayedConnectSettings,
    pub(super) network_reprobe_settings: NetworkReprobeSettings,
    pub(super) ws_tunnel_settings: WsTunnelSettings,
    pub(super) warmup_probe_settings: WarmupProbeSettings,
    route_retry_settings: TcpRouteRetrySettings,
    route_syn_data_settings: TcpRouteSynDataSettings,
    route_connect_settings: TcpRouteConnectSettingsTable,
    pub(super) tcp_desync_executor: TcpDesyncExecutor,
    udp_group_settings: UdpGroupSettingsTable,
    route_payload_matcher: RoutePayloadMatcher,
    pub(super) udp_desync_planner: UdpDesyncPlanner,
    udp_flow_limit: usize,
    udp_packet_parser: UdpPacketParser,
    udp_payload_classifier: UdpPayloadClassifier,
    relay_group_settings: RelayGroupSettingsTable,
    relay_host_extractor: PayloadHostExtractor,
    relay_first_response: FirstResponseSettings,
    first_outbound_payload_policy: FirstOutboundPayloadPolicy,
    first_response_exchange_policy: FirstResponseExchangePolicy,
    pub(super) response_failure_evidence_settings: ResponseFailureEvidenceSettings,
    pub(super) services: ServicesStateHandle,
    pub(super) active_clients: Arc<AtomicUsize>,
    pub(super) telemetry: Option<std::sync::Arc<dyn RuntimeTelemetrySink>>,
    pub(super) runtime_context: Option<ProxyRuntimeContext>,
    pub(super) control: Option<std::sync::Arc<EmbeddedProxyControl>>,
    /// Session-level flag: once any connection discovers that per-socket TTL
    /// modification is rejected by the kernel (EROFS on Android), all
    /// subsequent connections skip TTL desync actions immediately.
    pub(super) ttl_unavailable: Arc<AtomicBool>,
    /// Tracks network scope key changes for lightweight re-probing.
    pub(super) reprobe_tracker: std::sync::Arc<NetworkReprobeTracker>,
    pub(super) pcap_hook: Option<super::desync::PcapHook>,
    /// io_uring driver for zero-copy relay (Linux 6.0+, optional).
    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    pub(super) io_uring: Option<std::sync::Arc<ripdpi_io_uring::IoUringDriver>>,
}

impl RuntimeState {
    pub(super) fn new(config: RuntimeConfig, control: Option<std::sync::Arc<EmbeddedProxyControl>>) -> Self {
        let telemetry = control.as_ref().and_then(|c| c.telemetry_sink()).or_else(current_runtime_telemetry);
        let runtime_context = control.as_ref().and_then(|c| c.runtime_context());

        let handle = new_services_handle(config.clone(), telemetry.clone(), runtime_context.clone());

        let listener_settings = listener_settings(&config);
        let handshake_settings = proxy_handshake_settings(&config);
        let delayed_connect_settings = delayed_connect_settings(&config);
        let network_reprobe_settings = network_reprobe_settings(&config);
        let ws_tunnel_settings = ws_tunnel_settings(&config);
        let warmup_probe_settings = warmup_probe_settings(&config);
        let route_retry_settings = tcp_route_retry_settings(&config);
        let route_syn_data_settings = tcp_route_syn_data_settings(&config);
        let route_connect_settings = tcp_route_connect_settings_table(&config);
        let tcp_desync_executor = tcp_desync_executor(&config);
        let udp_group_settings = udp_group_settings_table(&config);
        let route_payload_matcher = route_payload_matcher(&config);
        let udp_desync_planner = udp_desync_planner(&config);
        let udp_flow_limit = udp_flow_limit(&config);
        let udp_packet_parser = udp_packet_parser(&config);
        let udp_payload_classifier = udp_payload_classifier(&config);
        let relay_group_settings = relay_group_settings_table(&config);
        let relay_host_extractor = payload_host_extractor(&config);
        let relay_first_response = first_response_settings(&config);
        let first_outbound_payload_policy = first_outbound_payload_policy(&config);
        let first_response_exchange_policy = first_response_exchange_policy(&config);
        let response_failure_evidence_settings = response_failure_evidence_settings(&config);

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

    pub(super) fn note_retry_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<()> {
        RetryPacingPort::note_retry_success(&self.services, target, group_index, host, payload, transport)
    }

    pub(super) fn note_retry_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<()> {
        RetryPacingPort::note_retry_failure(&self.services, target, group_index, host, payload, transport, now_ms)
    }

    pub(super) fn build_retry_penalties(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>> {
        RetryPacingPort::build_retry_penalties(&self.services, target, host, payload, transport, now_ms)
    }

    pub(super) fn apply_retry_pacing(
        &self,
        target: SocketAddr,
        route: &ConnectionRoute,
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

    pub(super) fn relay_group(&self, group_index: usize) -> io::Result<RelayGroupSettings> {
        relay_group_settings_with(&self.relay_group_settings, group_index)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
    }

    pub(super) fn relay_rotation_seed(&self, group_index: usize) -> io::Result<Option<(DesyncGroup, RotationPolicy)>> {
        tcp_rotation_seed_with(&self.relay_group_settings, group_index)
    }

    pub(super) fn relay_host_extractor(&self) -> PayloadHostExtractor {
        self.relay_host_extractor.clone()
    }

    pub(super) fn relay_first_response_settings(&self) -> FirstResponseSettings {
        self.relay_first_response
    }

    pub(super) fn first_outbound_payload_policy(&self) -> FirstOutboundPayloadPolicy {
        self.first_outbound_payload_policy.clone()
    }

    pub(super) fn first_response_exchange_policy(&self) -> FirstResponseExchangePolicy {
        self.first_response_exchange_policy
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
        transport: TransportProtocol,
    ) -> bool {
        route_matches_transport_payload_with(&self.route_payload_matcher, group_index, target, payload, transport)
    }

    pub(super) fn parse_socks5_udp_packet<'a>(
        &self,
        packet: &'a [u8],
        resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
    ) -> Option<(SocketAddr, &'a [u8])> {
        ripdpi_proxy_runtime_adapter::model::session::parse_socks5_udp_packet_with(
            &self.udp_packet_parser,
            packet,
            resolve_name,
        )
    }

    pub(super) fn udp_payload_classifier(&self) -> UdpPayloadClassifier {
        self.udp_payload_classifier.clone()
    }

    pub(super) fn udp_flow_limit(&self) -> usize {
        self.udp_flow_limit
    }

    pub(super) fn udp_group(&self, group_index: usize) -> Option<UdpGroupSettings> {
        udp_group_settings_with(&self.udp_group_settings, group_index)
    }

    pub(super) fn route_retry_settings(&self) -> TcpRouteRetrySettings {
        self.route_retry_settings
    }

    pub(super) fn route_uses_direct_syn_data_tfo(&self, route: &ConnectionRoute, payload: Option<&[u8]>) -> bool {
        connection_route_requests_direct_syn_data_tfo_with(&self.route_syn_data_settings, route, payload)
    }

    pub(super) fn route_connect_settings(
        &self,
        group_index: usize,
        payload: Option<&[u8]>,
        allow_tfo: bool,
    ) -> Option<TcpRouteConnectSettings> {
        tcp_route_connect_settings_with(&self.route_connect_settings, group_index, payload, allow_tfo)
    }

    pub(super) fn select_initial_route(
        &self,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
    ) -> Option<ConnectionRoute> {
        PolicyPort::select_initial(&self.services, target, payload, host, allow_unknown_payload, transport)
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn select_next_route(
        &self,
        route: &ConnectionRoute,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
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
        route: &ConnectionRoute,
        host: Option<&str>,
        transport: TransportProtocol,
    ) -> io::Result<()> {
        PolicyPort::note_success(&self.services, target, route, host, transport)
    }

    pub(super) fn runtime_supports_trigger(&self, trigger: u32) -> bool {
        PolicyPort::supports_trigger(&self.services, trigger)
    }

    pub(super) fn note_block_signal(
        &self,
        host: &str,
        signal: BlockSignal,
        provider: Option<&str>,
        confirmation_allowed: bool,
    ) {
        PolicyPort::note_block_signal(&self.services, host, signal, provider, confirmation_allowed);
    }

    pub(super) fn advance_route(
        &self,
        route: &ConnectionRoute,
        advance: RouteAdvance<'_>,
    ) -> io::Result<Option<ConnectionRoute>> {
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

    pub(super) fn note_evolver_failure(&self, class: FailureClass) {
        AdaptiveFeedbackPort::note_evolver_failure(&self.services, class);
    }

    pub(super) fn tcp_desync_execution_context(&self) -> TcpDesyncExecutionContext<'_> {
        TcpDesyncExecutionContext {
            executor: &self.tcp_desync_executor,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
            ttl_unavailable: &self.ttl_unavailable,
            pcap_hook: self.pcap_hook.as_ref(),
        }
    }

    pub(super) fn udp_desync_plan_context(&self) -> UdpDesyncPlanContext<'_> {
        UdpDesyncPlanContext {
            planner: &self.udp_desync_planner,
            runtime_context: self.runtime_context.as_ref(),
            telemetry: self.telemetry.as_deref(),
            adaptive_hints: &self.services,
        }
    }

    pub(super) fn note_direct_path_transport_attempt(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: TransportProtocol,
    ) {
        DirectPathLearningPort::note_direct_path_transport_attempt(&self.services, host, targets, transport);
    }

    pub(super) fn preferred_targets_for_transport(
        &self,
        original_target: SocketAddr,
        host: Option<&str>,
        transport: TransportProtocol,
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
        let listener_settings = listener_settings(&config);
        let handshake_settings = proxy_handshake_settings(&config);
        let delayed_connect_settings = delayed_connect_settings(&config);
        let network_reprobe_settings = network_reprobe_settings(&config);
        let ws_tunnel_settings = ws_tunnel_settings(&config);
        let warmup_probe_settings = warmup_probe_settings(&config);
        let route_retry_settings = tcp_route_retry_settings(&config);
        let route_syn_data_settings = tcp_route_syn_data_settings(&config);
        let route_connect_settings = tcp_route_connect_settings_table(&config);
        let tcp_desync_executor = tcp_desync_executor(&config);
        let udp_group_settings = udp_group_settings_table(&config);
        let route_payload_matcher = route_payload_matcher(&config);
        let udp_desync_planner = udp_desync_planner(&config);
        let udp_flow_limit = udp_flow_limit(&config);
        let udp_packet_parser = udp_packet_parser(&config);
        let udp_payload_classifier = udp_payload_classifier(&config);
        let relay_group_settings = relay_group_settings_table(&config);
        let relay_host_extractor = payload_host_extractor(&config);
        let relay_first_response = first_response_settings(&config);
        let first_outbound_payload_policy = first_outbound_payload_policy(&config);
        let first_response_exchange_policy = first_response_exchange_policy(&config);
        let response_failure_evidence_settings = response_failure_evidence_settings(&config);

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
