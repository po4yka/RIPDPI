use crate::ServicesStateHandle;
use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_adaptive::adaptive_port::{AdaptiveContextPort, AdaptiveHintPort};
use ripdpi_runtime_adaptive::morph_policy;
use ripdpi_runtime_adaptive::strategy_context;
use ripdpi_runtime_adaptive::strategy_context::merge_udp_hints_with_capability;
use std::io;
use std::net::SocketAddr;
impl AdaptiveHintPort for ServicesStateHandle {
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
        Ok(Some(resolver.resolve(group_index, target, host, auto_ttl, group.actions.ttl)))
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
}
