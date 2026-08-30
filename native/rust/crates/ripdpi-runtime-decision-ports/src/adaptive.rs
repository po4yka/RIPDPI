use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};
use ripdpi_desync::AdaptivePlannerHints;
use ripdpi_failure_classifier::FailureClass;
use ripdpi_proxy_config::{ProxyDirectPathCapability, ProxyRuntimeContext};

use crate::policy::{RetrySelectionPenalty, TransportProtocol};

/// Result of strategy-context preferred-target resolution.
#[derive(Debug, Default)]
pub struct PreferredTargets {
    pub targets: Vec<SocketAddr>,
    pub suppressed_targets: Vec<SocketAddr>,
    pub suppressed_udp: bool,
    pub suppression_reason: Option<PreferredTargetSuppressionReason>,
}

/// Stable reason why direct-path targets were suppressed before transport I/O.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PreferredTargetSuppressionReason {
    OwnedStackRequired,
    NoDirectSolutionCooldown,
    UdpPolicy,
}

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

/// Feedback port for adaptive tuning, fake-TTL, and strategy evolution.
pub trait AdaptiveFeedbackPort: Send + Sync {
    fn note_tcp_success(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_tcp_failure(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_udp_success(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_udp_failure(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_fake_ttl_success(&self, group_index: usize, target: SocketAddr, host: Option<&str>) -> io::Result<()>;

    fn note_fake_ttl_failure(&self, group_index: usize, target: SocketAddr, host: Option<&str>) -> io::Result<()>;

    fn note_server_ttl(
        &self,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()>;

    fn note_evolver_failure(&self, class: FailureClass);
    fn note_evolver_success(&self);
    fn note_evolver_connect_failure(&self);
    fn reset_evolver(&self);
    fn clear_adaptive_tuning(&self);
    fn flush_adaptive_store(&self);
}

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

/// Retry pacing port for reconnect backoff and retry-selection penalties.
pub trait RetryPacingPort: Send + Sync {
    fn note_retry_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<()>;

    fn note_retry_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<()>;

    fn build_retry_penalties(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>>;

    fn apply_retry_pacing(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
        on_paced: &dyn Fn(SocketAddr, usize, &'static str, u64),
    ) -> io::Result<()>;
}
