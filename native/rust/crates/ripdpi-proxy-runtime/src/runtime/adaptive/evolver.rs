use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn note_evolver_success(state: &RuntimeState, _latency_ms: u64) {
    state.note_evolver_success();
}

pub(in crate::runtime) fn note_evolver_failure(
    state: &RuntimeState,
    class: ripdpi_proxy_runtime_adapter::failure::FailureClass,
) {
    state.note_evolver_failure(class);
}
