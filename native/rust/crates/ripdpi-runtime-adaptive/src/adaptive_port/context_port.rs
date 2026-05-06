use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use super::PreferredTargets;

/// Context and morph-policy port for resolved hints and route context.
pub trait AdaptiveContextPort: Send + Sync {
    fn apply_tcp_morph(&self, hints: AdaptivePlannerHints) -> AdaptivePlannerHints;
    fn apply_udp_morph(&self, hints: AdaptivePlannerHints) -> AdaptivePlannerHints;

    fn preferred_targets(
        &self,
        context: Option<&ProxyRuntimeContext>,
        original: SocketAddr,
        host: Option<&str>,
        transport: TransportProtocol,
        now_ms: i64,
    ) -> PreferredTargets;

    fn direct_path_capability<'a>(
        &self,
        context: Option<&'a ProxyRuntimeContext>,
        host: Option<&str>,
        target: SocketAddr,
    ) -> Option<&'a ProxyDirectPathCapability>;

    fn network_scope_key(&self, config: &RuntimeConfig) -> Option<String>;
}
