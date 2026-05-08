use ripdpi_proxy_runtime_adapter::model::session::{
    observe_datagram_outbound_payload as observe_session_datagram_outbound_payload,
    observe_inbound_payload as observe_session_inbound_payload, OutboundProgress, SessionState,
};

use super::flow::UdpFlowActivationState;

pub(super) fn observe_upstream_response(entry: &mut UdpFlowActivationState, payload: &[u8]) {
    observe_inbound_payload(&mut entry.session, payload);
}

pub(super) fn observe_datagram_outbound(entry: &mut UdpFlowActivationState, payload: &[u8]) -> OutboundProgress {
    observe_session_datagram_outbound_payload(&mut entry.session, payload)
}

fn observe_inbound_payload(session: &mut SessionState, payload: &[u8]) {
    observe_session_inbound_payload(session, payload);
}
