use ripdpi_proxy_runtime_adapter::model::session::{OutboundProgress, SessionState};

use crate::runtime::relay::session::RelaySession;
use crate::runtime::state::RuntimeState;

pub(super) struct FirstOutboundSession {
    state: SessionState,
}

impl FirstOutboundSession {
    pub(super) fn new() -> Self {
        Self { state: RuntimeState::new_session_state() }
    }

    pub(super) fn into_relay_session(self) -> RelaySession {
        RelaySession::from_state(self.state)
    }

    pub(super) fn observe_first_outbound_payload(&mut self, original_request: &[u8]) -> OutboundProgress {
        RuntimeState::observe_session_outbound_payload(&mut self.state, original_request)
    }

    pub(super) fn observe_first_response_payload(&mut self, bytes: &[u8]) -> bool {
        RuntimeState::observe_session_first_response_payload(&mut self.state, bytes)
    }

    pub(super) fn observe_retry_response_payload(&mut self, bytes: &[u8]) {
        RuntimeState::observe_session_retry_response_payload(&mut self.state, bytes);
    }
}
