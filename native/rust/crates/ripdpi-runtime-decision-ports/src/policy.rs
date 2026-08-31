use std::collections::BTreeMap;
use std::net::{IpAddr, SocketAddr};

use ripdpi_failure_classifier::BlockSignal;

#[derive(Debug, Clone)]
pub struct ConnectionRoute {
    pub group_index: usize,
    pub attempted_mask: u64,
}

#[derive(Debug, Clone)]
pub struct HostAutolearnEvent {
    pub action: &'static str,
    pub host: Option<String>,
    pub group_index: Option<usize>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HostAutolearnState {
    pub enabled: bool,
    pub learned_host_count: usize,
    pub penalized_host_count: usize,
    pub blocked_host_count: usize,
    pub last_block_signal: Option<String>,
    pub last_block_provider: Option<String>,
}

pub struct RouteAdvance<'a> {
    pub dest: SocketAddr,
    pub payload: Option<&'a [u8]>,
    pub transport: TransportProtocol,
    pub trigger: u32,
    pub can_reconnect: bool,
    pub host: Option<String>,
    pub penalize_strategy_failure: bool,
    pub retry_penalties: Option<&'a BTreeMap<usize, RetrySelectionPenalty>>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RetrySelectionPenalty {
    pub same_signature_cooldown_ms: u64,
    pub family_cooldown_ms: u64,
    pub diversification_rank: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportProtocol {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedHost {
    pub host: String,
    pub source: HostSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostSource {
    Http,
    Tls,
    Quic,
}

pub struct DnsTamperingEvidence<'a> {
    pub host: &'a str,
    pub target_ip: IpAddr,
    pub answers: &'a [IpAddr],
    pub resolver_label: &'a str,
}

pub trait GeoMatcher {
    fn country_matches_ip(&self, country_code: &str, ip: IpAddr) -> bool;

    fn geosite_matches_host(&self, category: &str, host: &str) -> bool;

    fn country_match(&self, country_code: &str, ip: IpAddr) -> Option<bool> {
        Some(self.country_matches_ip(country_code, ip))
    }

    fn geosite_match(&self, category: &str, host: &str) -> Option<bool> {
        Some(self.geosite_matches_host(category, host))
    }
}

pub trait DirectPathLearningObserver {
    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    );
}

/// Port trait that abstracts direct-path learning feedback.
///
/// Implementations hold the concrete direct-path learning state. The port is
/// separate from [`PolicySelectionPort`] and [`PolicyLearningPort`] so callers
/// that only record direct-path observations do not depend on route selection
/// and host-policy persistence methods.
pub trait DirectPathLearningPort: Send + Sync {
    fn note_direct_path_transport_attempt(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        transport: TransportProtocol,
    );

    fn note_direct_path_udp_suppressed(&self, host: Option<&str>, targets: &[SocketAddr], now_ms: u64);

    fn note_direct_path_udp_failure(&self, host: Option<&str>, targets: &[SocketAddr]);

    fn note_direct_path_owned_stack_required(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    );

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
}

/// Port for choosing routes and maintaining route-selection state.
///
/// Consumers that only make routing decisions depend on this surface without
/// also acquiring host-learning, telemetry-drain, or persistence operations.
pub trait PolicySelectionPort: Send + Sync {
    fn select_initial(
        &self,
        target: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        allow_unknown_payload: bool,
        transport: TransportProtocol,
        geo: Option<&dyn GeoMatcher>,
    ) -> Option<ConnectionRoute>;

    fn advance_route(
        &self,
        route: &ConnectionRoute,
        advance: RouteAdvance<'_>,
    ) -> std::io::Result<Option<ConnectionRoute>>;

    fn supports_trigger(&self, trigger: u32) -> bool;

    #[allow(clippy::too_many_arguments)]
    fn select_next(
        &self,
        route: &ConnectionRoute,
        dest: SocketAddr,
        payload: Option<&[u8]>,
        host: Option<&str>,
        transport: TransportProtocol,
        trigger: u32,
        can_reconnect: bool,
        retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
        geo: Option<&dyn GeoMatcher>,
    ) -> Option<ConnectionRoute>;

    fn store_route(&self, dest: SocketAddr, group_index: usize, attempted_mask: u64, host: Option<String>);

    fn clear_connection_cache(&self) -> usize;

    fn build_retry_penalties(
        &self,
        group_count: usize,
        signatures: &[(usize, u64)],
        now_ms: u64,
    ) -> BTreeMap<usize, RetrySelectionPenalty>;
}

/// Port for recording policy outcomes and exposing learned host state.
///
/// The persistence and telemetry-drain operations live beside the outcome
/// signals that produce that state, while route-selection-only consumers can
/// remain independent of them.
pub trait PolicyLearningPort: Send + Sync {
    fn note_success(
        &self,
        target: SocketAddr,
        route: &ConnectionRoute,
        host: Option<&str>,
        transport: TransportProtocol,
    ) -> std::io::Result<()>;

    fn note_block_signal(&self, host: &str, signal: BlockSignal, provider: Option<&str>, confirmation_allowed: bool);

    fn autolearn_state(&self) -> HostAutolearnState;
    fn drain_autolearn_events(&self) -> Vec<HostAutolearnEvent>;

    fn flush_host_store(&self);
}

/// Aggregate policy capability for consumers that genuinely need both ports.
///
/// Implementors opt in explicitly; no blanket implementation is provided, so
/// downstream types retain control over their own trait implementations.
pub trait PolicyPort: PolicySelectionPort + PolicyLearningPort {}
