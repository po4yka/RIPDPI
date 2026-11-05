use std::net::SocketAddr;

use std::collections::BTreeMap;

use crate::runtime::failure::RuntimeClassifiedFailure;
use crate::runtime::retry::maybe_emit_candidate_diversification;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeRetrySelectionPenalty};

pub(in crate::runtime) fn emit_failure_classified(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &RuntimeClassifiedFailure,
    host: Option<&str>,
) {
    if !RuntimeState::should_track_strategy_target(target) || !state.reports_failures() {
        return;
    }
    state.note_failure_classified(target, failure, host);
}

pub(super) fn emit_route_advance_telemetry(
    state: &RuntimeState,
    target: SocketAddr,
    previous_route: &RuntimeConnectionRoute,
    next_route: Option<&RuntimeConnectionRoute>,
    trigger: u32,
    failure: &RuntimeClassifiedFailure,
    host: Option<&str>,
    retry_penalties: &BTreeMap<usize, RuntimeRetrySelectionPenalty>,
) {
    if let Some(next_route) = next_route {
        maybe_emit_candidate_diversification(state, target, next_route, retry_penalties);
    }
    if let Some(next_route) = next_route {
        state.note_route_advanced(target, previous_route.group_index, next_route.group_index, trigger, host);
        state.note_adaptive_override(
            target,
            next_route.group_index,
            trigger,
            failure.class.as_str(),
            host,
            "route_advance",
        );
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
    use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;

    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::failure::{RuntimeFailureAction, RuntimeFailureClass, RuntimeFailureStage};

    #[derive(Default)]
    struct Recorder(Mutex<usize>);

    impl RuntimeTelemetrySink for Recorder {
        fn on_listener_started(&self, _: SocketAddr, _: usize, _: usize) {}
        fn on_listener_stopped(&self) {}
        fn on_client_accepted(&self) {}
        fn on_client_finished(&self) {}
        fn on_client_error(&self, _: &std::io::Error) {}
        fn on_route_selected(&self, _: SocketAddr, _: usize, _: Option<&str>, _: &'static str) {}
        fn on_failure_classified(&self, _: SocketAddr, _: &ClassifiedFailure, _: Option<&str>) {
            *self.0.lock().unwrap() += 1;
        }
        fn on_route_advanced(&self, _: SocketAddr, _: usize, _: usize, _: u32, _: Option<&str>) {}
        fn on_host_autolearn_state(&self, _: bool, _: usize, _: usize, _: usize, _: Option<&str>, _: Option<&str>) {}
        fn on_host_autolearn_event(&self, _: &'static str, _: Option<&str>, _: Option<usize>) {}
    }

    #[test]
    fn a_background_probe_state_reports_no_failures() {
        let telemetry = Arc::new(Recorder::default());
        let state = RuntimeState::test_with_telemetry(RuntimeConfig::default(), Some(telemetry.clone()));
        let target = SocketAddr::from(([203, 0, 113, 10], 443));
        let failure = RuntimeClassifiedFailure::new(
            RuntimeFailureClass::TcpReset,
            RuntimeFailureStage::FirstResponse,
            RuntimeFailureAction::RetryWithMatchingGroup,
            "reset",
        );

        emit_failure_classified(&state, target, &failure, None);
        assert_eq!(*telemetry.0.lock().unwrap(), 1);

        emit_failure_classified(&state.for_background_probe(), target, &failure, None);
        assert_eq!(*telemetry.0.lock().unwrap(), 1);
    }
}
