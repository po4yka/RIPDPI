use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::dns::resolve_https_service_bindings_via_encrypted_dns_with_endpoint;
use crate::transport::TransportConfig;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum DnsHttpsClass {
    EchCapable,
    NoHttpsRr,
    HttpsRrPresent,
    ResolutionFailed,
}

impl DnsHttpsClass {
    pub(super) fn as_str(self) -> &'static str {
        match self {
            Self::EchCapable => "ECH_CAPABLE",
            Self::NoHttpsRr => "NO_HTTPS_RR",
            Self::HttpsRrPresent => "HTTPS_RR_PRESENT",
            Self::ResolutionFailed => "RESOLUTION_FAILED",
        }
    }
}

pub(super) fn classify_dns_https_support(
    domain: &str,
    selected_endpoint: &EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> (DnsHttpsClass, usize, usize) {
    match resolve_https_service_bindings_via_encrypted_dns_with_endpoint(domain, selected_endpoint.clone(), transport) {
        Ok(bindings) => {
            let ech_record_count = bindings.iter().filter(|record| record.ech_capable).count();
            if ech_record_count > 0 {
                (DnsHttpsClass::EchCapable, bindings.len(), ech_record_count)
            } else if bindings.is_empty() {
                (DnsHttpsClass::NoHttpsRr, 0, 0)
            } else {
                (DnsHttpsClass::HttpsRrPresent, bindings.len(), 0)
            }
        }
        Err(_) => (DnsHttpsClass::ResolutionFailed, 0, 0),
    }
}
