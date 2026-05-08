use crate::sync::{Arc, AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::{
    first_response_settings, relay_group_settings_table, response_failure_evidence_settings, route_payload_matcher,
    tcp_route_connect_settings_table, tcp_route_retry_settings, tcp_route_syn_data_settings, udp_group_settings_table,
    FirstResponseSettings, RelayGroupSettingsTable, ResponseFailureEvidenceSettings, RoutePayloadMatcher,
    RuntimeConfig, TcpRouteConnectSettingsTable, TcpRouteRetrySettings, TcpRouteSynDataSettings, UdpGroupSettingsTable,
};
use ripdpi_proxy_runtime_adapter::model::ports::{
    AdaptiveContextPort, AdaptiveFeedbackPort, AdaptiveHintPort, DirectPathLearningPort, PolicyPort, RetryPacingPort,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{NetworkReprobeTracker, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::{
    current_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink,
};
use ripdpi_proxy_runtime_adapter::model::services::{new_services_handle, ServicesStateHandle};
use ripdpi_proxy_runtime_adapter::model::session::{
    first_outbound_payload_policy, payload_host_extractor, FirstOutboundPayloadPolicy, PayloadHostExtractor,
};
use ripdpi_proxy_runtime_adapter::response_triggers::{first_response_exchange_policy, FirstResponseExchangePolicy};
use ripdpi_proxy_runtime_adapter::udp_desync::{udp_desync_planner, UdpDesyncPlanner};

pub(super) const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
pub(super) const UDP_FLOW_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Clone)]
pub(super) struct RuntimeState {
    pub(super) config: Arc<RuntimeConfig>,
    pub(super) route_retry_settings: TcpRouteRetrySettings,
    pub(super) route_syn_data_settings: TcpRouteSynDataSettings,
    pub(super) route_connect_settings: TcpRouteConnectSettingsTable,
    pub(super) udp_group_settings: UdpGroupSettingsTable,
    pub(super) udp_route_matcher: RoutePayloadMatcher,
    pub(super) udp_desync_planner: UdpDesyncPlanner,
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

        let route_retry_settings = tcp_route_retry_settings(&config);
        let route_syn_data_settings = tcp_route_syn_data_settings(&config);
        let route_connect_settings = tcp_route_connect_settings_table(&config);
        let udp_group_settings = udp_group_settings_table(&config);
        let udp_route_matcher = route_payload_matcher(&config);
        let udp_desync_planner = udp_desync_planner(&config);
        let relay_group_settings = relay_group_settings_table(&config);
        let relay_host_extractor = payload_host_extractor(&config);
        let relay_first_response = first_response_settings(&config);
        let first_outbound_payload_policy = first_outbound_payload_policy(&config);
        let first_response_exchange_policy = first_response_exchange_policy(&config);
        let response_failure_evidence_settings = response_failure_evidence_settings(&config);

        Self {
            config: Arc::new(config),
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            udp_group_settings,
            udp_route_matcher,
            udp_desync_planner,
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

    pub(super) fn adaptive_feedback(&self) -> &dyn AdaptiveFeedbackPort {
        &self.services
    }

    pub(super) fn adaptive_context(&self) -> &dyn AdaptiveContextPort {
        &self.services
    }

    pub(super) fn retry_pacing(&self) -> &dyn RetryPacingPort {
        &self.services
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
        let route_retry_settings = tcp_route_retry_settings(&config);
        let route_syn_data_settings = tcp_route_syn_data_settings(&config);
        let route_connect_settings = tcp_route_connect_settings_table(&config);
        let udp_group_settings = udp_group_settings_table(&config);
        let udp_route_matcher = route_payload_matcher(&config);
        let udp_desync_planner = udp_desync_planner(&config);
        let relay_group_settings = relay_group_settings_table(&config);
        let relay_host_extractor = payload_host_extractor(&config);
        let relay_first_response = first_response_settings(&config);
        let first_outbound_payload_policy = first_outbound_payload_policy(&config);
        let first_response_exchange_policy = first_response_exchange_policy(&config);
        let response_failure_evidence_settings = response_failure_evidence_settings(&config);

        Self {
            config: Arc::new(config),
            route_retry_settings,
            route_syn_data_settings,
            route_connect_settings,
            udp_group_settings,
            udp_route_matcher,
            udp_desync_planner,
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
