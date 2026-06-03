mod udp;

#[cfg(feature = "hickory")]
mod hickory_probe;

pub use ripdpi_ech_dns::{
    EchResolutionOutcome, EncryptedDnsEchResolver, bootstrap_ips_for_resolver, build_dns_query_with_type,
    build_fallback_encrypted_dns_endpoints, ech_public_name, encrypted_dns_endpoint_for_resolver_id,
    encrypted_dns_endpoint_for_target, encrypted_dns_protocol, exchange_encrypted_dns_query,
    extract_ech_config_list_from_https_response, parse_bootstrap_ips, parse_dns_response, parse_url_host,
    resolve_https_ech_configs_via_encrypted_dns_with_endpoint,
    resolve_https_service_bindings_via_encrypted_dns_with_endpoint, resolve_outbound_ech_config_via_encrypted_dns,
    resolve_via_encrypted_dns, resolve_via_encrypted_dns_with_raw, skip_dns_name,
};

#[cfg(feature = "hickory")]
pub use hickory_probe::resolve_via_hickory_dns;
pub use udp::{
    UdpDnsResolution, classify_udp_dns_error, is_retryable_udp_dns_error, resolve_via_udp_with_observations,
    resolve_via_udp_with_raw,
};
