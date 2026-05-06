use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_failure_classifier::BlockSignal;
use ripdpi_runtime_policy::direct_path_learning::DirectPathLearningObserver;
use ripdpi_runtime_policy::runtime_policy::{
    ConnectionRoute, HostAutolearnEvent, HostAutolearnState, RetrySelectionPenalty, RouteAdvance, RuntimePolicy,
    TransportProtocol,
};
use ripdpi_runtime_policy::{DirectPathLearningPort, PolicyPort};

use crate::services_state::ServicesState;
use crate::ServicesStateHandle;

impl PolicyPort for ServicesStateHandle {
    fn select_initial(
        &self,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        config: &RuntimeConfig,
    ) -> Option<ConnectionRoute> {
        let mut cache = self.0.cache.write().ok()?;
        let result = cache.select_initial(target, payload, host, allow_unknown_payload, transport, config);
        self.0.flush_autolearn(&mut cache);
        result
    }

    fn note_success(
        &self,
        target: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
        transport: TransportProtocol,
        config: &RuntimeConfig,
    ) -> io::Result<()> {
        let mut cache = self.0.cache.write().map_err(|_| io::Error::other("policy cache lock poisoned"))?;
        cache.note_route_success_for_transport(config, target, route, host, transport)?;
        self.0.flush_autolearn(&mut cache);
        Ok(())
    }

    fn advance_route(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        advance: RouteAdvance<'_>,
    ) -> io::Result<Option<ConnectionRoute>> {
        let mut cache = self.0.cache.write().map_err(|_| io::Error::other("policy cache lock poisoned"))?;
        let result = cache.advance_route(config, route, advance)?;
        self.0.flush_autolearn(&mut cache);
        Ok(result)
    }

    fn note_block_signal(
        &self,
        config: &RuntimeConfig,
        host: &str,
        signal: BlockSignal,
        provider: Option<&str>,
        confirmation_allowed: bool,
    ) {
        let Ok(mut cache) = self.0.cache.write() else {
            return;
        };
        cache.note_block_signal(config, host, signal, provider, confirmation_allowed);
        self.0.flush_autolearn(&mut cache);
    }

    fn supports_trigger(&self, trigger: u32) -> bool {
        let Ok(cache) = self.0.cache.read() else {
            return false;
        };
        cache.supports_trigger(trigger)
    }

    fn select_next(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute> {
        let cache = self.0.cache.read().ok()?;
        cache.select_next(config, route, dest, payload, host, transport, trigger, can_reconnect, retry_penalties)
    }

    fn store_route(
        &self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    ) {
        let Ok(mut cache) = self.0.cache.write() else {
            return;
        };
        cache.store(config, dest, group_index, attempted_mask, host);
    }

    fn clear_connection_cache(&self, config: &RuntimeConfig) -> usize {
        let Ok(mut cache) = self.0.cache.write() else {
            return 0;
        };
        let cleared = cache.clear_connection_cache(config);
        self.0.flush_autolearn(&mut cache);
        cleared
    }

    fn build_retry_penalties(
        &self,
        _group_count: usize,
        _signatures: &[(usize, u64)],
        _now_ms: u64,
    ) -> BTreeMap<usize, RetrySelectionPenalty> {
        // Phase 3 will wire full RetrySignature objects through the port.
        // RetrySignature cannot be reconstructed from a raw hash alone because
        // the pacer also needs the family_hash; both are fields on RetrySignature
        // and are not stored separately in this crate.
        BTreeMap::new()
    }

    fn autolearn_state(&self, config: &RuntimeConfig) -> HostAutolearnState {
        let Ok(mut cache) = self.0.cache.write() else {
            return HostAutolearnState::default();
        };
        cache.autolearn_state(config)
    }

    fn drain_autolearn_events(&self) -> Vec<HostAutolearnEvent> {
        let Ok(mut cache) = self.0.cache.write() else {
            return Vec::new();
        };
        cache.drain_autolearn_events()
    }

    fn flush_host_store(&self, config: &RuntimeConfig) {
        let Ok(mut cache) = self.0.cache.write() else {
            return;
        };
        cache.flush_host_store(config);
        let _ = cache.dump_stdout_groups(config, std::io::stdout());
    }
}

impl DirectPathLearningPort for ServicesStateHandle {
    fn note_direct_path_transport_attempt(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: TransportProtocol,
    ) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_transport_attempt(host, targets, transport);
    }

    fn note_direct_path_udp_suppressed(&self, host: Option<&str>, targets: &[SocketAddr], now_ms: u64) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_udp_suppressed(host, targets, now_ms);
    }

    fn note_direct_path_udp_failure(&self, host: Option<&str>, targets: &[SocketAddr]) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_udp_failure(host, targets);
    }

    fn note_direct_path_quic_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    ) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_quic_success(observer, host, targets);
    }

    fn note_direct_path_tcp_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        strategy_family: Option<&str>,
        observer: Option<&dyn DirectPathLearningObserver>,
    ) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_tcp_success(observer, host, targets, strategy_family);
    }

    fn note_direct_path_tls_post_client_hello_failure(&self, host: Option<&str>, targets: &[SocketAddr]) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_tls_post_client_hello_failure(host, targets);
    }

    fn note_direct_path_all_ips_failed(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    ) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.note_all_ips_failed(observer, host, targets);
    }

    fn emit_due_direct_path_learning_timeouts(&self, now_ms: u64, observer: Option<&dyn DirectPathLearningObserver>) {
        let Ok(mut dpl) = self.0.direct_path_learning.write() else {
            return;
        };
        dpl.emit_due_timeouts(observer, now_ms);
    }
}

impl ServicesState {
    pub(crate) fn flush_autolearn(&self, cache: &mut RuntimePolicy) {
        let Some(telemetry) = &self.telemetry else {
            let _ = cache.drain_autolearn_events();
            return;
        };
        let autolearn = cache.autolearn_state(&self.config);
        telemetry.on_host_autolearn_state(
            autolearn.enabled,
            autolearn.learned_host_count,
            autolearn.penalized_host_count,
            autolearn.blocked_host_count,
            autolearn.last_block_signal.as_deref(),
            autolearn.last_block_provider.as_deref(),
        );
        for event in cache.drain_autolearn_events() {
            telemetry.on_host_autolearn_event(event.action, event.host.as_deref(), event.group_index);
        }
    }
}
