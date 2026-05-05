use std::io;
use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

/// Coarse port trait that abstracts all adaptive tuning, fake-TTL resolution,
/// strategy evolution, morph policy, and strategy context.
///
/// Implementations hold the concrete [`AdaptivePlannerResolver`],
/// [`AdaptiveFakeTtlResolver`], [`StrategyEvolutionResolver`], and related
/// objects. The port is passed by `Arc<dyn AdaptivePort>` so proxy-runtime
/// never takes a direct dependency on the concrete adaptive types.
pub trait AdaptivePort: Send + Sync {
    // --- Hint resolution ---

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

    // --- Adaptive feedback ---

    fn note_tcp_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_tcp_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_udp_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_udp_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_fake_ttl_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
    ) -> io::Result<()>;

    fn note_fake_ttl_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
    ) -> io::Result<()>;

    fn note_server_ttl(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()>;

    // --- Hint resolution with strategy-evolution fallback ---

    /// Like `resolve_tcp_hints` but tries the strategy evolver first when
    /// `config.adaptive.strategy_evolution` is enabled.
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

    /// Like `resolve_udp_hints` but tries the strategy evolver first when
    /// `config.adaptive.strategy_evolution` is enabled.
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

    // --- Strategy evolution ---

    fn note_evolver_failure(&self, class: FailureClass);
    fn note_evolver_success(&self);
    fn note_evolver_connect_failure(&self);

    // --- Morph policy (applies adjustments to resolved hints) ---

    fn apply_tcp_morph(&self, hints: AdaptivePlannerHints) -> AdaptivePlannerHints;
    fn apply_udp_morph(&self, hints: AdaptivePlannerHints) -> AdaptivePlannerHints;

    // --- Strategy context ---

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

    // --- Retry pacing ---

    /// Record a successful connection; clears any backoff for the signature.
    fn note_retry_success(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<()>;

    /// Record a failed connection attempt; returns the resulting backoff decision.
    fn note_retry_failure(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<()>;

    /// Build retry-selection penalty map for all groups.
    fn build_retry_penalties(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<std::collections::BTreeMap<usize, ripdpi_runtime_policy::runtime_policy::RetrySelectionPenalty>>;

    /// Apply retry pacing delay before a reconnect attempt.
    /// Calls `on_paced(target, group_index, reason, backoff_ms)` if a backoff
    /// fires, then sleeps for the computed duration.
    fn apply_retry_pacing(
        &self,
        config: &RuntimeConfig,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
        on_paced: &dyn Fn(SocketAddr, usize, &'static str, u64),
    ) -> io::Result<()>;

    // --- Reprobe reset (called after network identity change) ---

    /// Reset the strategy evolver so it re-learns after a network handover.
    fn reset_evolver(&self);

    /// Clear all adaptive tuning state after a network handover.
    fn clear_adaptive_tuning(&self);

    // --- Persistence ---

    fn flush_adaptive_store(&self, config: &RuntimeConfig);
}

/// Result of strategy-context preferred-target resolution.
#[derive(Debug, Default)]
pub struct PreferredTargets {
    pub targets: Vec<SocketAddr>,
    pub suppressed_targets: Vec<SocketAddr>,
    pub suppressed_udp: bool,
}
