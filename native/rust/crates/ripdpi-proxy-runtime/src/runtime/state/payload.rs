use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn classify_first_outbound_payload(&self, payload: &[u8]) -> OutboundPayloadInfo {
        runtime_classify_first_outbound_payload(&self.first_outbound_payload_policy, payload)
    }
    pub(in crate::runtime) fn first_outbound_tls_client_hello_assembler() -> RuntimeOutboundTlsClientHelloAssembler {
        runtime_outbound_tls_client_hello_assembler()
    }
    pub(in crate::runtime) fn build_probe_client_hello(domain: &str) -> Vec<u8> {
        runtime_build_probe_client_hello(domain)
    }
}
