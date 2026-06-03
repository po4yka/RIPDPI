use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

use crate::types::DnsTarget;
use crate::util::{DEFAULT_DOH_HOST, DEFAULT_DOH_URL};

use super::catalog::{
    bootstrap_strings_for_resolver, default_bootstrap_strings, default_port, resolver_catalog_entry,
    resolver_entry_endpoint,
};
use super::parse::{encrypted_dns_protocol, parse_bootstrap_ips, parse_url_host};

pub fn encrypted_dns_endpoint_for_target(target: &DnsTarget) -> Result<(EncryptedDnsEndpoint, Vec<String>), String> {
    let protocol = encrypted_dns_protocol(target.encrypted_protocol.as_deref());
    let bootstrap_strings = if target.encrypted_bootstrap_ips.is_empty() {
        bootstrap_strings_for_resolver(target.encrypted_resolver_id.as_deref())
    } else {
        target.encrypted_bootstrap_ips.clone()
    };
    let doh_url = target
        .encrypted_doh_url
        .clone()
        .or_else(|| (protocol == EncryptedDnsProtocol::Doh).then(|| DEFAULT_DOH_URL.to_string()));
    let host =
        target.encrypted_host.clone().or_else(|| doh_url.as_deref().and_then(parse_url_host)).unwrap_or_else(|| {
            if protocol == EncryptedDnsProtocol::Doh { DEFAULT_DOH_HOST.to_string() } else { String::new() }
        });
    let port = target.encrypted_port.unwrap_or_else(|| default_port(protocol));

    Ok((
        EncryptedDnsEndpoint {
            protocol,
            resolver_id: target.encrypted_resolver_id.clone().or_else(|| Some(protocol.as_str().to_string())),
            host,
            port,
            tls_server_name: target.encrypted_tls_server_name.clone(),
            bootstrap_ips: parse_bootstrap_ips(&bootstrap_strings)?,
            doh_url,
            dnscrypt_provider_name: target.encrypted_dnscrypt_provider_name.clone(),
            dnscrypt_public_key: target.encrypted_dnscrypt_public_key.clone(),
            odoh: None,
        },
        bootstrap_strings,
    ))
}

pub fn encrypted_dns_endpoint_for_resolver_id(resolver_id: &str) -> EncryptedDnsEndpoint {
    resolver_entry_endpoint(resolver_catalog_entry(resolver_id))
}

#[allow(dead_code)]
pub(super) fn default_bootstrap_strings_for_target() -> Vec<String> {
    default_bootstrap_strings()
}
