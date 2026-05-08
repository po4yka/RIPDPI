use ripdpi_proxy_runtime_adapter::model::session::{
    observe_first_response_payload as observe_session_first_response_payload,
    observe_outbound_payload as observe_session_outbound_payload,
    observe_retry_response_payload as observe_session_retry_response_payload, OutboundProgress, SessionState,
};

pub(super) fn observe_first_outbound_payload(
    session_state: &mut SessionState,
    original_request: &[u8],
) -> OutboundProgress {
    observe_session_outbound_payload(session_state, original_request)
}

pub(super) fn observe_first_response_payload(session_state: &mut SessionState, bytes: &[u8]) -> bool {
    observe_session_first_response_payload(session_state, bytes)
}

pub(super) fn observe_retry_response_payload(session_state: &mut SessionState, bytes: &[u8]) {
    observe_session_retry_response_payload(session_state, bytes);
}
