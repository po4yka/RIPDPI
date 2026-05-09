use crate::strategy_evolution::StrategyEvolutionResolver;
use crate::ServicesStateHandle;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_runtime_adaptive::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
use ripdpi_runtime_adaptive::adaptive_port::AdaptiveFeedbackPort;
use ripdpi_runtime_adaptive::adaptive_tuning::AdaptivePlannerResolver;
use ripdpi_runtime_adaptive::strategy_context::network_scope_key;
use std::io;
use std::net::SocketAddr;
impl ServicesStateHandle {
    fn with_tuning(&self, action: impl FnOnce(&mut AdaptivePlannerResolver)) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive.tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
        action(&mut resolver);
        resolver.persist_if_due(self.0.config.as_ref());
        Ok(())
    }
    fn with_fake_ttl(&self, action: impl FnOnce(&mut AdaptiveFakeTtlResolver)) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive.fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
        action(&mut resolver);
        Ok(())
    }
    fn with_evolver(&self, action: impl FnOnce(&mut StrategyEvolutionResolver)) {
        if let Ok(mut evolver) = self.0.adaptive.strategy_evolver.write() {
            action(&mut evolver);
        }
    }
}
impl AdaptiveFeedbackPort for ServicesStateHandle {
    fn note_tcp_success(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let scope_key = network_scope_key(&self.0.config);
        self.with_tuning(|resolver| resolver.note_tcp_success(scope_key, group_index, target, host, payload))
    }
    fn note_tcp_failure(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let scope_key = network_scope_key(&self.0.config);
        self.with_tuning(|resolver| resolver.note_tcp_failure(scope_key, group_index, target, host, payload))
    }
    fn note_udp_success(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let scope_key = network_scope_key(&self.0.config);
        self.with_tuning(|resolver| resolver.note_udp_success(scope_key, group_index, target, host, payload))
    }
    fn note_udp_failure(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let scope_key = network_scope_key(&self.0.config);
        self.with_tuning(|resolver| resolver.note_udp_failure(scope_key, group_index, target, host, payload))
    }
    fn note_fake_ttl_success(&self, group_index: usize, target: SocketAddr, host: Option<&str>) -> io::Result<()> {
        self.with_fake_ttl(|resolver| resolver.note_success(group_index, target, host))
    }
    fn note_fake_ttl_failure(&self, group_index: usize, target: SocketAddr, host: Option<&str>) -> io::Result<()> {
        self.with_fake_ttl(|resolver| resolver.note_failure(group_index, target, host))
    }
    fn note_server_ttl(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()> {
        self.with_fake_ttl(|resolver| resolver.note_server_ttl(group_index, target, host, observed_ttl))
    }
    fn note_evolver_failure(&self, class: FailureClass) {
        self.with_evolver(|evolver| evolver.record_failure(class));
    }
    fn note_evolver_success(&self) {
        self.with_evolver(|evolver| evolver.record_success(0));
    }
    fn note_evolver_connect_failure(&self) {
        self.reset_evolver();
    }
    fn reset_evolver(&self) {
        self.with_evolver(StrategyEvolutionResolver::reset);
    }
    fn clear_adaptive_tuning(&self) {
        if let Ok(mut tuning) = self.0.adaptive.tuning.write() {
            tuning.clear_all();
        }
    }
    fn flush_adaptive_store(&self) {
        if let Ok(mut resolver) = self.0.adaptive.tuning.write() {
            resolver.flush_store(&self.0.config);
        }
    }
}
