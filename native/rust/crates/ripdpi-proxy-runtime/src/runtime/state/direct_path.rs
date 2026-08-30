use super::*;

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

impl RuntimeState {
    pub(in crate::runtime) fn note_direct_path_transport_attempt(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: RuntimeTransportProtocol,
    ) {
        DirectPathLearningPort::note_direct_path_transport_attempt(&self.services, host, targets, transport);
    }
    pub(in crate::runtime) fn preferred_targets_for_transport(
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
    pub(in crate::runtime) fn owned_stack_required_for_transparent_target(
        &self,
        original_target: SocketAddr,
        host: Option<&str>,
        now_ms: i64,
    ) -> bool {
        let Some(host) = host.map(str::trim).filter(|host| !host.is_empty()) else {
            return false;
        };
        let decision = AdaptiveContextPort::preferred_targets(
            &self.services,
            self.runtime_context.as_ref(),
            original_target,
            Some(host),
            RuntimeTransportProtocol::Tcp,
            now_ms,
        );
        if decision.suppression_reason != Some(PreferredTargetSuppressionReason::OwnedStackRequired) {
            return false;
        }

        let observer = self.direct_path_observer();
        DirectPathLearningPort::note_direct_path_owned_stack_required(
            &self.services,
            Some(host),
            &decision.suppressed_targets,
            observer.as_ref().map(|observer| observer as &dyn DirectPathLearningObserver),
        );
        true
    }
    pub(in crate::runtime) fn note_direct_path_udp_suppressed(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        now_ms: u64,
    ) {
        DirectPathLearningPort::note_direct_path_udp_suppressed(&self.services, host, targets, now_ms);
    }
    pub(in crate::runtime) fn note_direct_path_udp_failure(&self, host: Option<&str>, targets: &[SocketAddr]) {
        DirectPathLearningPort::note_direct_path_udp_failure(&self.services, host, targets);
    }
    pub(in crate::runtime) fn note_direct_path_quic_success(&self, host: Option<&str>, targets: &[SocketAddr]) {
        let observer = self.direct_path_observer();
        DirectPathLearningPort::note_direct_path_quic_success(
            &self.services,
            host,
            targets,
            observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
        );
    }
    pub(in crate::runtime) fn note_direct_path_tcp_success(
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
    pub(in crate::runtime) fn note_direct_path_tls_post_client_hello_failure(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
    ) {
        DirectPathLearningPort::note_direct_path_tls_post_client_hello_failure(&self.services, host, targets);
    }
    pub(in crate::runtime) fn note_direct_path_all_ips_failed(&self, host: Option<&str>, targets: &[SocketAddr]) {
        let observer = self.direct_path_observer();
        DirectPathLearningPort::note_direct_path_all_ips_failed(
            &self.services,
            host,
            targets,
            observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
        );
    }
    pub(in crate::runtime) fn emit_due_direct_path_learning_timeouts(&self, now_ms: u64) {
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
}
