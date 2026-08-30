use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_adaptive::adaptive_port::{AdaptiveContextPort, PreferredTargets};
use ripdpi_runtime_adaptive::morph_policy;
use ripdpi_runtime_adaptive::strategy_context;
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use crate::ServicesStateHandle;

impl AdaptiveContextPort for ServicesStateHandle {
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
            suppression_reason: decision.suppression_reason,
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
}
