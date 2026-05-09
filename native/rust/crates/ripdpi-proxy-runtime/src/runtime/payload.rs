use ripdpi_proxy_runtime_adapter::model::config::FirstResponseSettings;
use ripdpi_proxy_runtime_adapter::protocol_payload::{
    build_probe_client_hello, FirstResponseBoundaryTracker, OutboundTlsClientHelloAssembler,
};
#[cfg(test)]
use ripdpi_proxy_runtime_adapter::protocol_payload::{
    TlsRecordBoundaryTracker, DEFAULT_FAKE_TLS, FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT,
    FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT,
};

pub(super) type RuntimeFirstResponseBoundaryTracker = FirstResponseBoundaryTracker;
pub(super) type RuntimeOutboundTlsClientHelloAssembler = OutboundTlsClientHelloAssembler;
#[cfg(test)]
pub(super) type RuntimeTlsRecordBoundaryTracker = TlsRecordBoundaryTracker;
#[cfg(test)]
pub(super) const RUNTIME_DEFAULT_FAKE_TLS: &[u8] = DEFAULT_FAKE_TLS;
#[cfg(test)]
pub(super) const RUNTIME_FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT: std::time::Duration =
    FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT;
#[cfg(test)]
pub(super) const RUNTIME_FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT: usize = FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT;

pub(super) fn runtime_first_response_boundary_tracker(
    request: &[u8],
    settings: FirstResponseSettings,
) -> RuntimeFirstResponseBoundaryTracker {
    FirstResponseBoundaryTracker::for_request(request, settings)
}

pub(super) fn runtime_outbound_tls_client_hello_assembler() -> RuntimeOutboundTlsClientHelloAssembler {
    OutboundTlsClientHelloAssembler::new()
}

pub(super) fn runtime_build_probe_client_hello(domain: &str) -> Vec<u8> {
    build_probe_client_hello(domain)
}
