use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn new_session_state() -> RuntimeSessionState {
        RuntimeSessionState(new_session_state())
    }
    pub(in crate::runtime) fn observe_session_inbound_payload(state: &mut RuntimeSessionState, payload: &[u8]) {
        observe_inbound_payload(&mut state.0, payload);
    }
    pub(in crate::runtime) fn session_has_inbound_payload(state: &RuntimeSessionState) -> bool {
        has_inbound_payload(&state.0)
    }
    pub(in crate::runtime) fn observe_session_outbound_payload(
        state: &mut RuntimeSessionState,
        payload: &[u8],
    ) -> RuntimeOutboundProgress {
        runtime_outbound_progress(observe_outbound_payload(&mut state.0, payload))
    }
    pub(in crate::runtime) fn observe_session_datagram_outbound_payload(
        state: &mut RuntimeSessionState,
        payload: &[u8],
    ) -> RuntimeOutboundProgress {
        runtime_outbound_progress(observe_datagram_outbound_payload(&mut state.0, payload))
    }
    pub(in crate::runtime) fn single_payload_progress(payload_size: usize) -> RuntimeOutboundProgress {
        RuntimeOutboundProgress { round: 1, payload_size, stream_start: 0, stream_end: payload_size.saturating_sub(1) }
    }
    pub(in crate::runtime) fn observe_session_first_response_payload(
        state: &mut RuntimeSessionState,
        payload: &[u8],
    ) -> bool {
        observe_first_response_payload(&mut state.0, payload)
    }
    pub(in crate::runtime) fn observe_session_retry_response_payload(state: &mut RuntimeSessionState, payload: &[u8]) {
        observe_retry_response_payload(&mut state.0, payload);
    }
    pub(in crate::runtime) fn outbound_payload_count_this_round(state: &RuntimeSessionState) -> usize {
        outbound_payload_count_this_round(&state.0)
    }
    pub(in crate::runtime) fn session_round_count(state: &RuntimeSessionState) -> u32 {
        state.0.round_count
    }
}
