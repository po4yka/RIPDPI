use ripdpi_dns_resolver::{
    extract_ip_answers, parse_https_service_bindings, EncryptedDnsConnectHooks, EncryptedDnsEndpoint,
    EncryptedDnsResolver, EncryptedDnsTransport, HttpsRr,
};

use crate::transport::TransportConfig;
use crate::util::now_ms;

use super::wire::{build_dns_query_with_type, DNS_RECORD_TYPE_A, DNS_RECORD_TYPE_HTTPS, DNS_RECORD_TYPE_SVCB};

#[derive(Clone, Debug)]
pub enum EchResolutionOutcome {
    /// DoH succeeded and HTTPS record contained an EchConfigList.
    Available(Vec<u8>),
    /// DoH succeeded but the HTTPS response had no EchConfigList parameter.
    NotPublished,
    /// DoH query itself failed (network error, timeout, blocked, etc.).
    ResolutionFailed(String),
}

pub fn resolve_via_encrypted_dns(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<String>, String> {
    let (result, _raw) = resolve_via_encrypted_dns_with_raw(domain, endpoint, transport);
    result
}

/// Like [`resolve_via_encrypted_dns`] but also returns the raw response bytes
/// for record-level comparison with the UDP response.
pub fn resolve_via_encrypted_dns_with_raw(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>) {
    match exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_A, endpoint, transport) {
        Ok(raw) => {
            let parsed = extract_ip_answers(&raw).map_err(|err| err.to_string());
            (parsed, Some(raw))
        }
        Err(err) => (Err(err), None),
    }
}

pub fn resolve_https_ech_configs_via_encrypted_dns_with_endpoint(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> EchResolutionOutcome {
    match exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_HTTPS, endpoint, transport) {
        Err(err) => EchResolutionOutcome::ResolutionFailed(err),
        Ok(response) => match extract_ech_config_list_from_https_response(&response) {
            Err(err) => EchResolutionOutcome::ResolutionFailed(err),
            Ok(None) => EchResolutionOutcome::NotPublished,
            Ok(Some(bytes)) => EchResolutionOutcome::Available(bytes),
        },
    }
}

pub fn resolve_https_service_bindings_via_encrypted_dns_with_endpoint(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<HttpsRr>, String> {
    let mut bindings = Vec::new();
    for record_type in [DNS_RECORD_TYPE_HTTPS, DNS_RECORD_TYPE_SVCB] {
        let response = exchange_encrypted_dns_query(domain, record_type, endpoint.clone(), transport)?;
        bindings.extend(parse_https_service_bindings(&response).map_err(|error| error.to_string())?);
    }
    Ok(bindings)
}

pub fn exchange_encrypted_dns_query(
    domain: &str,
    record_type: u16,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<u8>, String> {
    let transport = match transport {
        TransportConfig::Direct { .. } => EncryptedDnsTransport::Direct,
        TransportConfig::Socks5 { host, port } => EncryptedDnsTransport::Socks5 { host: host.clone(), port: *port },
    };
    let resolver = EncryptedDnsResolver::with_connect_hooks(endpoint, transport, encrypted_dns_connect_hooks())
        .map_err(|err| err.to_string())?;
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = build_dns_query_with_type(domain, query_id, record_type)?;
    resolver.exchange_blocking(&packet).map_err(|err| err.to_string())
}

fn encrypted_dns_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_dot_tls_connector_builder(|| {
        ripdpi_tls_profiles::configure_builder("chrome_stable").map_err(|error| error.to_string())
    })
}

pub fn extract_ech_config_list_from_https_response(packet: &[u8]) -> Result<Option<Vec<u8>>, String> {
    Ok(parse_https_service_bindings(packet)
        .map_err(|error| error.to_string())?
        .into_iter()
        .find_map(|record| record.ech_config.map(|config| config.raw_list_bytes)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encrypted_dns_connect_hooks_install_dot_tls_builder() {
        let hooks = encrypted_dns_connect_hooks();

        assert!(hooks.dot_tls_connector_builder.is_some());
    }
}
