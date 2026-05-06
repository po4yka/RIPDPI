use std::io;
use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_proxy_config::ProxyRuntimeContext;

/// Hint resolution port for adaptive tuning and strategy-evolution hints.
pub trait AdaptiveHintPort: Send + Sync {
    fn resolve_tcp_hints(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints>;

    fn resolve_udp_hints(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints>;

    fn resolve_fake_ttl(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
    ) -> io::Result<Option<u8>>;

    fn resolve_tcp_hints_with_evolver(
        &self,
        config: &RuntimeConfig,
        context: Option<&ProxyRuntimeContext>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints>;

    fn resolve_udp_hints_with_evolver(
        &self,
        config: &RuntimeConfig,
        context: Option<&ProxyRuntimeContext>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        group: &DesyncGroup,
        payload: &[u8],
    ) -> io::Result<AdaptivePlannerHints>;
}
