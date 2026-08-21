//! Lower-level encrypted-DNS ECH resolution primitives.
//!
//! This crate hosts the encrypted-DNS (DoH/DoT) ECH-resolution path that was
//! historically embedded in `ripdpi-diagnostics-dns`. It is split out so relay
//! transports (e.g. `ripdpi-masque`) can depend on the ECH lookup without
//! pulling in the whole diagnostics-DNS scan stack — see NATIVE_RUST.md crate
//! layering and `rust-discipline`.
//!
//! `ripdpi-diagnostics-dns` re-exports every item here through its existing
//! `dns` module facade, so downstream diagnostics consumers are unchanged.
#![forbid(unsafe_code)]

mod encrypted;
mod endpoints;
mod fallback;
mod wire;

pub use encrypted::{
    EchResolutionOutcome, EncryptedDnsEchResolver, ech_public_name, exchange_encrypted_dns_query,
    extract_ech_config_list_from_https_response, resolve_https_ech_configs_via_encrypted_dns_with_endpoint,
    resolve_https_service_bindings_via_encrypted_dns_with_endpoint, resolve_outbound_ech_config_via_encrypted_dns,
    resolve_via_encrypted_dns, resolve_via_encrypted_dns_with_raw,
};
pub use endpoints::{
    bootstrap_ips_for_resolver, encrypted_dns_endpoint_for_resolver_id, encrypted_dns_endpoint_for_target,
    encrypted_dns_protocol, parse_bootstrap_ips, parse_url_host,
};
pub use fallback::build_fallback_encrypted_dns_endpoints;
pub use wire::{DNS_RECORD_TYPE_A, build_dns_query_with_type, parse_dns_response, skip_dns_name};

pub(crate) mod transport {
    pub use ripdpi_diagnostics_transport::transport::TransportConfig;
}

pub(crate) use ripdpi_diagnostics_contracts::types;

pub(crate) mod util {
    pub use ripdpi_diagnostics_contracts::util::{
        DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, DEFAULT_DOH_PORT, DEFAULT_DOH_URL, bounded_scan_io_timeout, now_ms,
    };
}
