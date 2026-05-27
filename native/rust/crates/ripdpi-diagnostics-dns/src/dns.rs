mod encrypted;
mod endpoints;
mod fallback;
mod udp;
mod wire;

#[cfg(feature = "hickory")]
mod hickory_probe;

pub use encrypted::{
    ech_public_name, exchange_encrypted_dns_query, extract_ech_config_list_from_https_response,
    resolve_https_ech_configs_via_encrypted_dns_with_endpoint,
    resolve_https_service_bindings_via_encrypted_dns_with_endpoint, resolve_outbound_ech_config_via_encrypted_dns,
    resolve_via_encrypted_dns, resolve_via_encrypted_dns_with_raw, EchResolutionOutcome, EncryptedDnsEchResolver,
};
pub use endpoints::{
    bootstrap_ips_for_resolver, encrypted_dns_endpoint_for_resolver_id, encrypted_dns_endpoint_for_target,
    encrypted_dns_protocol, parse_bootstrap_ips, parse_url_host,
};
pub use fallback::build_fallback_encrypted_dns_endpoints;
#[cfg(feature = "hickory")]
pub use hickory_probe::resolve_via_hickory_dns;
pub use udp::{
    classify_udp_dns_error, is_retryable_udp_dns_error, resolve_via_udp_with_observations, resolve_via_udp_with_raw,
    UdpDnsResolution,
};
pub use wire::{build_dns_query_with_type, parse_dns_response, skip_dns_name};

#[cfg(test)]
pub use wire::build_dns_query;
