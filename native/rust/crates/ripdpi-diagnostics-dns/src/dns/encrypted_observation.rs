use super::semantics::{parse_dns_response_semantics, semantic_address_result, transport_semantics};
use crate::transport::TransportConfig;
use ripdpi_diagnostics_contracts::types::{DnsResponseOutcome, DnsResponseSemantics};
use ripdpi_dns_resolver::{EncryptedDnsConnectHooks, EncryptedDnsEndpoint};
use ripdpi_ech_dns::{DNS_RECORD_TYPE_A, exchange_encrypted_dns_query_with_request};

/// Use the existing exchange once, retaining response facts even without usable addresses.
pub fn resolve_via_encrypted_dns_with_observations(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>, DnsResponseSemantics) {
    match exchange_encrypted_dns_query_with_request(
        domain,
        DNS_RECORD_TYPE_A,
        endpoint,
        transport,
        EncryptedDnsConnectHooks::new(),
    ) {
        Ok((query, raw)) => match parse_dns_response_semantics(&query, &raw) {
            Ok((addresses, facts)) => (semantic_address_result(addresses, &facts), Some(raw), facts),
            Err(error) => (Err(error), None, DnsResponseSemantics::unobserved("A", DnsResponseOutcome::Malformed)),
        },
        Err(error) => {
            let facts = transport_semantics(&error);
            (Err(error), None, facts)
        }
    }
}
