use super::{OutboundProgress, SessionState};

pub fn new_session_state() -> SessionState {
    SessionState::default()
}

pub fn observe_inbound_payload(session: &mut SessionState, payload: &[u8]) {
    session.observe_inbound(payload);
}

pub fn observe_outbound_payload(session: &mut SessionState, payload: &[u8]) -> OutboundProgress {
    session.observe_outbound(payload)
}

pub fn observe_datagram_outbound_payload(session: &mut SessionState, payload: &[u8]) -> OutboundProgress {
    session.observe_datagram_outbound(payload)
}

pub fn has_inbound_payload(session: &SessionState) -> bool {
    session.recv_count > 0
}

pub fn observe_first_response_payload(session: &mut SessionState, payload: &[u8]) -> bool {
    observe_inbound_payload(session, payload);
    has_inbound_payload(session)
}

pub fn observe_retry_response_payload(session: &mut SessionState, payload: &[u8]) {
    observe_inbound_payload(session, payload);
}

pub fn outbound_payload_count_this_round(session: &SessionState) -> usize {
    session.sent_this_round
}
