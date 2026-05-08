use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use super::flow::UdpFlowActivationState;

pub(super) fn observe_upstream_response(entry: &mut UdpFlowActivationState, payload: &[u8]) {
    entry.session.observe_upstream_response(payload);
}

pub(super) fn observe_datagram_outbound(entry: &mut UdpFlowActivationState, payload: &[u8]) -> OutboundProgress {
    entry.session.observe_datagram_outbound(payload)
}
