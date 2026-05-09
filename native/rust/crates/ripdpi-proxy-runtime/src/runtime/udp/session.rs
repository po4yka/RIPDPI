use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeOutboundProgress, RuntimeSessionState};

pub(super) struct UdpFlowSession {
    state: RuntimeSessionState,
}

impl UdpFlowSession {
    pub(super) fn new() -> Self {
        Self { state: RuntimeState::new_session_state() }
    }

    pub(super) fn observe_upstream_response(&mut self, payload: &[u8]) {
        RuntimeState::observe_session_inbound_payload(&mut self.state, payload);
    }

    pub(super) fn observe_datagram_outbound(&mut self, payload: &[u8]) -> RuntimeOutboundProgress {
        RuntimeState::observe_session_datagram_outbound_payload(&mut self.state, payload)
    }

    pub(super) fn round_count(&self) -> u32 {
        RuntimeState::session_round_count(&self.state)
    }
}
