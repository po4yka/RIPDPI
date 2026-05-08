use ripdpi_proxy_runtime_adapter::model::session::{
    new_session_state, observe_first_response_payload as observe_session_first_response_payload,
    observe_outbound_payload as observe_session_outbound_payload,
    observe_retry_response_payload as observe_session_retry_response_payload, OutboundProgress, SessionState,
};

use crate::runtime::relay::session::RelaySession;

pub(super) struct FirstOutboundSession {
    state: SessionState,
}

impl FirstOutboundSession {
    pub(super) fn new() -> Self {
        Self { state: new_session_state() }
    }

    pub(super) fn into_relay_session(self) -> RelaySession {
        RelaySession::from_state(self.state)
    }

    pub(super) fn observe_first_outbound_payload(&mut self, original_request: &[u8]) -> OutboundProgress {
        observe_session_outbound_payload(&mut self.state, original_request)
    }

    pub(super) fn observe_first_response_payload(&mut self, bytes: &[u8]) -> bool {
        observe_session_first_response_payload(&mut self.state, bytes)
    }

    pub(super) fn observe_retry_response_payload(&mut self, bytes: &[u8]) {
        observe_session_retry_response_payload(&mut self.state, bytes);
    }
}
