use ripdpi_proxy_runtime_adapter::model::session::{
    new_session_state, observe_datagram_outbound_payload, observe_inbound_payload, OutboundProgress, SessionState,
};

pub(super) struct UdpFlowSession {
    state: SessionState,
}

impl UdpFlowSession {
    pub(super) fn new() -> Self {
        Self { state: new_session_state() }
    }

    pub(super) fn observe_upstream_response(&mut self, payload: &[u8]) {
        observe_inbound_payload(&mut self.state, payload);
    }

    pub(super) fn observe_datagram_outbound(&mut self, payload: &[u8]) -> OutboundProgress {
        observe_datagram_outbound_payload(&mut self.state, payload)
    }

    pub(super) fn round_count(&self) -> u32 {
        self.state.round_count
    }
}
