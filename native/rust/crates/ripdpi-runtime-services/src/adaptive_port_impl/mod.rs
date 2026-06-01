mod context;
mod feedback;
mod hints;
mod retry;

use std::io;
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::ProxyMorphPolicy;
use ripdpi_runtime_adaptive::adaptive_port::AdaptiveHintPort;
use ripdpi_runtime_adaptive::retry_stealth::{RetrySignature, adaptive_signature_hash, target_key};
use ripdpi_runtime_adaptive::strategy_context::{network_scope_key, retry_lane_for_payload};
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use crate::ServicesStateHandle;

impl ServicesStateHandle {
    pub(crate) fn morph_policy(&self) -> Option<&ProxyMorphPolicy> {
        self.0.runtime.runtime_context.as_ref()?.morph_policy.as_ref()
    }

    pub(crate) fn build_retry_signature(
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
        let resolved_fake_ttl =
            <Self as AdaptiveHintPort>::resolve_fake_ttl(self, Some(scope), group_index, target, host, group)?;
        let adaptive_hints = match (transport, payload) {
            (TransportProtocol::Tcp, Some(bytes)) => <Self as AdaptiveHintPort>::resolve_tcp_hints(
                self,
                Some(scope),
                group_index,
                target,
                host,
                group,
                bytes,
            )?,
            (TransportProtocol::Udp, Some(bytes)) => <Self as AdaptiveHintPort>::resolve_udp_hints(
                self,
                Some(scope),
                group_index,
                target,
                host,
                group,
                bytes,
            )?,
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
