use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use ripdpi_failure_classifier::BlockSignal;
use ripdpi_runtime_decision_ports::policy_ports::{
    ConnectionRoute, GeoMatcher, HostAutolearnEvent, HostAutolearnState, PolicyLearningPort, PolicySelectionPort,
    RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};

struct SelectionOnlyPolicy;

impl PolicySelectionPort for SelectionOnlyPolicy {
    fn select_initial(
        &self,
        _target: SocketAddr,
        _payload: Option<&[u8]>,
        _host: Option<&str>,
        _allow_unknown_payload: bool,
        _transport: TransportProtocol,
        _geo: Option<&dyn GeoMatcher>,
    ) -> Option<ConnectionRoute> {
        None
    }

    fn advance_route(
        &self,
        _route: &ConnectionRoute,
        _advance: RouteAdvance<'_>,
    ) -> io::Result<Option<ConnectionRoute>> {
        Ok(None)
    }

    fn supports_trigger(&self, _trigger: u32) -> bool {
        false
    }

    fn select_next(
        &self,
        _route: &ConnectionRoute,
        _dest: SocketAddr,
        _payload: Option<&[u8]>,
        _host: Option<&str>,
        _transport: TransportProtocol,
        _trigger: u32,
        _can_reconnect: bool,
        _retry_penalties: Option<&BTreeMap<usize, RetrySelectionPenalty>>,
        _geo: Option<&dyn GeoMatcher>,
    ) -> Option<ConnectionRoute> {
        None
    }

    fn store_route(&self, _dest: SocketAddr, _group_index: usize, _attempted_mask: u64, _host: Option<String>) {}

    fn clear_connection_cache(&self) -> usize {
        0
    }

    fn build_retry_penalties(
        &self,
        _group_count: usize,
        _signatures: &[(usize, u64)],
        _now_ms: u64,
    ) -> BTreeMap<usize, RetrySelectionPenalty> {
        BTreeMap::new()
    }
}

struct LearningOnlyPolicy;

impl PolicyLearningPort for LearningOnlyPolicy {
    fn note_success(
        &self,
        _target: SocketAddr,
        _route: &ConnectionRoute,
        _host: Option<&str>,
        _transport: TransportProtocol,
    ) -> io::Result<()> {
        Ok(())
    }

    fn note_block_signal(
        &self,
        _host: &str,
        _signal: BlockSignal,
        _provider: Option<&str>,
        _confirmation_allowed: bool,
    ) {
    }

    fn autolearn_state(&self) -> HostAutolearnState {
        HostAutolearnState::default()
    }

    fn drain_autolearn_events(&self) -> Vec<HostAutolearnEvent> {
        Vec::new()
    }

    fn flush_host_store(&self) {}
}

#[test]
fn test_doubles_can_implement_selection_and_learning_independently() {
    fn accepts_selection(_: &dyn PolicySelectionPort) {}
    fn accepts_learning(_: &dyn PolicyLearningPort) {}

    accepts_selection(&SelectionOnlyPolicy);
    accepts_learning(&LearningOnlyPolicy);
}
