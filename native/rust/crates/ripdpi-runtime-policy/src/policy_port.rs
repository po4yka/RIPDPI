use std::collections::BTreeMap;
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_failure_classifier::BlockSignal;

use crate::direct_path_learning::DirectPathLearningObserver;
use crate::runtime_policy::{
    ConnectionRoute, HostAutolearnEvent, HostAutolearnState, RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};

/// Coarse port trait that abstracts route selection and direct-path learning.
///
/// Implementations hold the concrete [`RuntimePolicy`] and
/// [`DirectPathLearningState`] objects. The port is passed by `Arc<dyn
/// PolicyPort>` so proxy-runtime never takes a direct dependency on the
/// concrete types.
pub trait PolicyPort: Send + Sync {
    // --- Route selection & recording ---

    fn select_initial(
        &self,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        config: &RuntimeConfig,
    ) -> Option<ConnectionRoute>;

    fn note_success(
        &self,
        target: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
        transport: TransportProtocol,
        config: &RuntimeConfig,
    ) -> std::io::Result<()>;

    fn advance_route(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        advance: RouteAdvance<'_>,
    ) -> std::io::Result<Option<ConnectionRoute>>;

    fn note_block_signal(
        &self,
        config: &RuntimeConfig,
        host: &str,
        signal: BlockSignal,
        provider: Option<&str>,
        confirmation_allowed: bool,
    );

    fn supports_trigger(&self, trigger: u32) -> bool;

    /// Select the next fallback route without consuming the current one.
    /// Used by delay_conn to refine the route after reading the first payload.
    #[allow(clippy::too_many_arguments)]
    fn select_next(
        &self,
        config: &RuntimeConfig,
        route: &ConnectionRoute,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
    ) -> Option<ConnectionRoute>;

    /// Persist a confirmed route for a destination (UDP hint caching).
    fn store_route(
        &self,
        config: &RuntimeConfig,
        dest: SocketAddr,
        group_index: usize,
        attempted_mask: u64,
        host: Option<String>,
    );

    /// Clear the in-memory connection cache and persist the cleared state.
    /// Returns the number of entries cleared.
    fn clear_connection_cache(&self, config: &RuntimeConfig) -> usize;

    // --- Retry-penalty support ---

    fn build_retry_penalties(
        &self,
        group_count: usize,
        signatures: &[(usize, u64)],
        now_ms: u64,
    ) -> BTreeMap<usize, RetrySelectionPenalty>;

    // --- Direct-path learning ---

    fn note_direct_path_transport_attempt(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: TransportProtocol,
    );

    fn note_direct_path_udp_suppressed(&self, host: Option<&str>, targets: &[SocketAddr], now_ms: u64);

    fn note_direct_path_udp_failure(&self, host: Option<&str>, targets: &[SocketAddr]);

    fn note_direct_path_quic_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    );

    fn note_direct_path_tcp_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        strategy_family: Option<&str>,
        observer: Option<&dyn DirectPathLearningObserver>,
    );

    fn note_direct_path_tls_post_client_hello_failure(&self, host: Option<&str>, targets: &[SocketAddr]);

    fn note_direct_path_all_ips_failed(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    );

    fn emit_due_direct_path_learning_timeouts(&self, now_ms: u64, observer: Option<&dyn DirectPathLearningObserver>);

    // --- Autolearn / telemetry flush ---

    fn autolearn_state(&self, config: &RuntimeConfig) -> HostAutolearnState;
    fn drain_autolearn_events(&self) -> Vec<HostAutolearnEvent>;

    // --- Persistence (called on shutdown) ---

    fn flush_host_store(&self, config: &RuntimeConfig);
}
