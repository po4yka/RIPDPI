use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn classify_first_outbound_payload(&self, payload: &[u8]) -> OutboundPayloadInfo {
        runtime_classify_first_outbound_payload(&self.first_outbound_payload_policy, payload)
    }
    pub(in crate::runtime) fn first_outbound_request_assembler(
        hostname_policy: bool,
    ) -> ripdpi_proxy_runtime_adapter::protocol_payload::OutboundRequestAssembler {
        runtime_outbound_request_assembler(hostname_policy)
    }
    pub(in crate::runtime) fn build_probe_client_hello(domain: &str) -> Vec<u8> {
        runtime_build_probe_client_hello(domain)
    }
}
