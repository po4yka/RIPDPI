use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;
use std::thread;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyMorphPolicy, ProxyRuntimeContext};
use ripdpi_runtime_adaptive::adaptive_port::{AdaptivePort, PreferredTargets};
use ripdpi_runtime_adaptive::morph_policy;
use ripdpi_runtime_adaptive::retry_stealth::{adaptive_signature_hash, target_key, RetrySignature};
use ripdpi_runtime_adaptive::strategy_context;
use ripdpi_runtime_adaptive::strategy_context::{
    merge_udp_hints_with_capability, network_scope_key, retry_lane_for_payload,
};
use ripdpi_runtime_policy::runtime_policy::{RetrySelectionPenalty, TransportProtocol};

use crate::ServicesStateHandle;

impl AdaptivePort for ServicesStateHandle {
    fn resolve_tcp_hints(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints> {
        let hints = {
            let mut resolver =
                self.0.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
            resolver.resolve_tcp_hints(scope_key, group_index, target, host, group, payload)
        };
        Ok(morph_policy::apply_tcp_morph_policy_to_hints(self.morph_policy(), hints))
    }

    fn resolve_udp_hints(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints> {
        let hints = {
            let mut resolver =
                self.0.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
            resolver.resolve_udp_hints(scope_key, group_index, target, host, group, payload)
        };
        Ok(morph_policy::apply_udp_morph_policy_to_hints(self.morph_policy(), hints))
    }

    fn resolve_fake_ttl(
        &self,
        _scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
    ) -> io::Result<Option<u8>> {
        let Some(auto_ttl) = group.actions.auto_ttl else {
            return Ok(None);
        };
        let mut resolver =
            self.0.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
        // AdaptiveFakeTtlResolver keys by (group_index, host/addr); scope_key is not used.
        Ok(Some(resolver.resolve(group_index, target, host, auto_ttl, group.actions.ttl)))
    }

    fn note_tcp_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
        resolver.note_tcp_success(scope_key, group_index, target, host, payload);
        resolver.persist_if_due(self.0.config.as_ref());
        Ok(())
    }

    fn note_tcp_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
        resolver.note_tcp_failure(scope_key, group_index, target, host, payload);
        resolver.persist_if_due(self.0.config.as_ref());
        Ok(())
    }

    fn note_udp_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
        resolver.note_udp_success(scope_key, group_index, target, host, payload);
        resolver.persist_if_due(self.0.config.as_ref());
        Ok(())
    }

    fn note_udp_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_tuning.write().map_err(|_| io::Error::other("adaptive tuning lock poisoned"))?;
        resolver.note_udp_failure(scope_key, group_index, target, host, payload);
        resolver.persist_if_due(self.0.config.as_ref());
        Ok(())
    }

    fn note_fake_ttl_success(
        &self,
        _scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
        resolver.note_success(group_index, target, host);
        Ok(())
    }

    fn note_fake_ttl_failure(
        &self,
        _scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
        resolver.note_failure(group_index, target, host);
        Ok(())
    }

    fn note_server_ttl(
        &self,
        _scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()> {
        let mut resolver =
            self.0.adaptive_fake_ttl.write().map_err(|_| io::Error::other("adaptive fake ttl lock poisoned"))?;
        resolver.note_server_ttl(group_index, target, host, observed_ttl);
        Ok(())
    }

    fn note_retry_success(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<()> {
        let Some(signature) = self.build_retry_signature(config, target, group_index, host, payload, transport)? else {
            return Ok(());
        };
        let mut pacer = self.0.retry_stealth.write().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
        pacer.clear_success(&signature);
        Ok(())
    }

    fn note_retry_failure(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<()> {
        let Some(signature) = self.build_retry_signature(config, target, group_index, host, payload, transport)? else {
            return Ok(());
        };
        let mut pacer = self.0.retry_stealth.write().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
        pacer.record_failure(&signature, now_ms);
        Ok(())
    }

    fn build_retry_penalties(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>> {
        let mut signatures = Vec::with_capacity(config.groups.len());
        for group_index in 0..config.groups.len() {
            if let Some(sig) = self.build_retry_signature(config, target, group_index, host, payload, transport)? {
                signatures.push((group_index, sig));
            }
        }
        let pacer = self.0.retry_stealth.read().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
        let mut penalties = BTreeMap::new();
        for (group_index, signature) in signatures {
            penalties.insert(group_index, pacer.penalty_for(&signature, now_ms));
        }
        Ok(penalties)
    }

    fn apply_retry_pacing(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
        on_paced: &dyn Fn(SocketAddr, usize, &'static str, u64),
    ) -> io::Result<()> {
        let Some(signature) =
            self.build_retry_signature(config, target, group_index, host, payload, TransportProtocol::Tcp)?
        else {
            return Ok(());
        };
        let decision = {
            let pacer = self.0.retry_stealth.read().map_err(|_| io::Error::other("retry pacing rwlock poisoned"))?;
            pacer.retry_delay_for(&signature, now_ms)
        };
        let Some(decision) = decision.filter(|d| d.backoff_ms > 0) else {
            return Ok(());
        };
        on_paced(target, group_index, decision.reason, decision.backoff_ms);
        thread::sleep(Duration::from_millis(decision.backoff_ms));
        Ok(())
    }

    fn resolve_tcp_hints_with_evolver(
        &self,
        config: &RuntimeConfig,
        context: Option<&ProxyRuntimeContext>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints> {
        if config.adaptive.strategy_evolution {
            if let Ok(mut evolver) = self.0.strategy_evolver.write() {
                if let Some(hints) = evolver.tcp_hints(config, context, target, host, payload) {
                    return Ok(morph_policy::apply_tcp_morph_policy_to_hints(self.morph_policy(), hints));
                }
            }
        }
        self.resolve_tcp_hints(self.network_scope_key(config).as_deref(), group_index, target, host, group, payload)
    }

    fn resolve_udp_hints_with_evolver(
        &self,
        config: &RuntimeConfig,
        context: Option<&ProxyRuntimeContext>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints> {
        if config.adaptive.strategy_evolution {
            if let Ok(mut evolver) = self.0.strategy_evolver.write() {
                if let Some(hints) = evolver.udp_hints(config, context, target, host, payload) {
                    let hints = morph_policy::apply_udp_morph_policy_to_hints(self.morph_policy(), hints);
                    let capability = strategy_context::direct_path_capability_for_route(context, host, target);
                    return Ok(merge_udp_hints_with_capability(hints, capability));
                }
            }
        }
        self.resolve_udp_hints(self.network_scope_key(config).as_deref(), group_index, target, host, group, payload)
    }

    fn note_evolver_failure(&self, class: FailureClass) {
        if let Ok(mut evolver) = self.0.strategy_evolver.write() {
            evolver.record_failure(class);
        }
    }

    fn note_evolver_success(&self) {
        if let Ok(mut evolver) = self.0.strategy_evolver.write() {
            // Phase 3 will thread the actual latency through; use 0 as sentinel.
            evolver.record_success(0);
        }
    }

    fn note_evolver_connect_failure(&self) {
        if let Ok(mut evolver) = self.0.strategy_evolver.write() {
            evolver.reset();
        }
    }

    fn apply_tcp_morph(&self, hints: AdaptivePlannerHints) -> AdaptivePlannerHints {
        morph_policy::apply_tcp_morph_policy_to_hints(self.morph_policy(), hints)
    }

    fn apply_udp_morph(&self, hints: AdaptivePlannerHints) -> AdaptivePlannerHints {
        morph_policy::apply_udp_morph_policy_to_hints(self.morph_policy(), hints)
    }

    fn preferred_targets(
        &self,
        context: Option<&ProxyRuntimeContext>,
        original: SocketAddr,
        host: Option<&str>,
        transport: TransportProtocol,
        now_ms: i64,
    ) -> PreferredTargets {
        let decision = strategy_context::preferred_targets_for_transport(context, original, host, transport, now_ms);
        PreferredTargets {
            targets: decision.targets,
            suppressed_targets: decision.suppressed_targets,
            suppressed_udp: decision.suppressed_udp,
        }
    }

    fn direct_path_capability<'a>(
        &self,
        context: Option<&'a ProxyRuntimeContext>,
        host: Option<&str>,
        target: SocketAddr,
    ) -> Option<&'a ProxyDirectPathCapability> {
        strategy_context::direct_path_capability_for_route(context, host, target)
    }

    fn network_scope_key(&self, config: &RuntimeConfig) -> Option<String> {
        strategy_context::network_scope_key(config).map(ToOwned::to_owned)
    }

    fn reset_evolver(&self) {
        if let Ok(mut evolver) = self.0.strategy_evolver.write() {
            evolver.reset();
        }
    }

    fn clear_adaptive_tuning(&self) {
        if let Ok(mut tuning) = self.0.adaptive_tuning.write() {
            tuning.clear_all();
        }
    }

    fn flush_adaptive_store(&self, config: &RuntimeConfig) {
        if let Ok(mut resolver) = self.0.adaptive_tuning.write() {
            resolver.flush_store(config);
        }
    }
}

impl ServicesStateHandle {
    fn morph_policy(&self) -> Option<&ProxyMorphPolicy> {
        self.0.runtime_context.as_ref()?.morph_policy.as_ref()
    }

    /// Build a `RetrySignature` for the given (target, group_index, transport)
    /// triple by resolving adaptive hints and fake-TTL through the port.
    fn build_retry_signature(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<Option<RetrySignature>> {
        let Some(group) = config.groups.get(group_index) else {
            return Ok(None);
        };
        let scope = network_scope_key(config).unwrap_or("default");
        let resolved_fake_ttl = self.resolve_fake_ttl(Some(scope), group_index, target, host, group)?;
        use ripdpi_desync::AdaptivePlannerHints;
        let adaptive_hints: AdaptivePlannerHints = match (transport, payload) {
            (TransportProtocol::Tcp, Some(bytes)) => {
                self.resolve_tcp_hints(Some(scope), group_index, target, host, group, bytes)?
            }
            (TransportProtocol::Udp, Some(bytes)) => {
                self.resolve_udp_hints(Some(scope), group_index, target, host, group, bytes)?
            }
            _ => AdaptivePlannerHints::default(),
        };
        Ok(Some(RetrySignature::new(
            scope,
            retry_lane_for_payload(transport, payload),
            target_key(host, target),
            group_index,
            adaptive_signature_hash(resolved_fake_ttl, adaptive_hints),
        )))
    }
}
