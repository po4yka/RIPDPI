use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::desync_platform::{tcp_desync_executor, TcpDesyncExecutor};
use ripdpi_proxy_runtime_adapter::failure::FailureClass;
use ripdpi_proxy_runtime_adapter::model::config::{
    delayed_connect_settings, first_response_settings, listener_settings, network_reprobe_settings,
    proxy_handshake_settings, relay_group_settings_table, response_failure_evidence_settings, route_payload_matcher,
    tcp_route_connect_settings_table, tcp_route_retry_settings, tcp_route_syn_data_settings, udp_flow_limit,
    udp_group_settings_table, warmup_probe_settings, ws_tunnel_settings, DelayedConnectSettings, FirstResponseSettings,
    ListenerSettings, NetworkReprobeSettings, ProxyHandshakeSettings, RelayGroupSettingsTable,
    ResponseFailureEvidenceSettings, RoutePayloadMatcher, RuntimeConfig, TcpRouteConnectSettingsTable,
    TcpRouteRetrySettings, TcpRouteSynDataSettings, UdpGroupSettingsTable, WarmupProbeSettings, WsTunnelSettings,
};
use ripdpi_proxy_runtime_adapter::model::decision::{ConnectionRoute, RetrySelectionPenalty, TransportProtocol};
use ripdpi_proxy_runtime_adapter::model::ports::{
    AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, DirectPathLearningPort, PolicyPort, RetryPacingPort,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{NetworkReprobeTracker, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink,
};
use ripdpi_proxy_runtime_adapter::model::services::{
    new_services_handle, reprobe_reset_handle, ReprobeResetHandle, ServicesStateHandle,
};
use ripdpi_proxy_runtime_adapter::model::session::{
    first_outbound_payload_policy, payload_host_extractor, udp_packet_parser, udp_payload_classifier,
    FirstOutboundPayloadPolicy, PayloadHostExtractor, UdpPacketParser, UdpPayloadClassifier,
};
use ripdpi_proxy_runtime_adapter::response_triggers::{first_response_exchange_policy, FirstResponseExchangePolicy};
use ripdpi_proxy_runtime_adapter::udp_desync::{udp_desync_planner, UdpDesyncPlanner};

pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Clone)]
pub(super) struct RuntimeState {
    pub(super) listener_settings: ListenerSettings,
    pub(super) handshake_settings: ProxyHandshakeSettings,
    pub(super) delayed_connect_settings: DelayedConnectSettings,
    pub(super) network_reprobe_settings: NetworkReprobeSettings,
    pub(super) ws_tunnel_settings: WsTunnelSettings,
    pub(super) warmup_probe_settings: WarmupProbeSettings,
    pub(super) route_retry_settings: TcpRouteRetrySettings,
    pub(super) route_syn_data_settings: TcpRouteSynDataSettings,
    pub(super) route_connect_settings: TcpRouteConnectSettingsTable,
    pub(super) tcp_desync_executor: TcpDesyncExecutor,
    pub(super) udp_group_settings: UdpGroupSettingsTable,
    pub(super) route_payload_matcher: RoutePayloadMatcher,
    pub(super) udp_desync_planner: UdpDesyncPlanner,
    pub(super) udp_flow_limit: usize,
    pub(super) udp_packet_parser: UdpPacketParser,
    pub(super) udp_payload_classifier: UdpPayloadClassifier,
    pub(super) relay_group_settings: RelayGroupSettingsTable,
    pub(super) relay_host_extractor: PayloadHostExtractor,
    pub(super) relay_first_response: FirstResponseSettings,
    pub(super) first_outbound_payload_policy: FirstOutboundPayloadPolicy,
    pub(super) first_response_exchange_policy: FirstResponseExchangePolicy,
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

    pub(super) fn policy(&self) -> &dyn PolicyPort {
        &self.services
    }

    pub(super) fn direct_path_learning(&self) -> &dyn DirectPathLearningPort {
        &self.services
    }

    pub(super) fn adaptive_hints(&self) -> &dyn AdaptiveHintPort {
        &self.services
    }

    pub(super) fn adaptive_context(&self) -> &dyn AdaptiveContextPort {
        &self.services
    }

    pub(super) fn clear_connection_cache(&self) -> usize {
        self.policy().clear_connection_cache()
    }

    pub(super) fn drain_autolearn_events(&self) {
        let _ = self.policy().drain_autolearn_events();
    }

    pub(super) fn flush_autolearn_telemetry(&self) {
        if let Some(telemetry) = &self.telemetry {
            let autolearn = self.policy().autolearn_state();
            telemetry.on_host_autolearn_state(
                autolearn.enabled,
                autolearn.learned_host_count,
                autolearn.penalized_host_count,
                autolearn.blocked_host_count,
                autolearn.last_block_signal.as_deref(),
                autolearn.last_block_provider.as_deref(),
            );
            for event in self.policy().drain_autolearn_events() {
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
