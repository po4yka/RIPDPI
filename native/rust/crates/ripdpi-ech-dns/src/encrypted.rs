use ripdpi_dns_resolver::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsResolver, EncryptedDnsTransport, HttpsRr,
    extract_ip_answers, parse_ech_config_list, parse_https_service_bindings,
};

use crate::transport::TransportConfig;
use crate::util::now_ms;

use super::wire::{DNS_RECORD_TYPE_A, DNS_RECORD_TYPE_HTTPS, DNS_RECORD_TYPE_SVCB, build_dns_query_with_type};

#[derive(Clone, Debug)]
pub enum EchResolutionOutcome {
    /// DoH succeeded and HTTPS record contained an EchConfigList.
    Available(Vec<u8>),
    /// DoH succeeded but the HTTPS response had no EchConfigList parameter.
    NotPublished,
    /// DoH query itself failed (network error, timeout, blocked, etc.).
    ResolutionFailed(String),
}

pub struct EncryptedDnsEchResolver {
    resolver_id: &'static str,
}

impl EncryptedDnsEchResolver {
    pub const fn adguard() -> Self {
        Self { resolver_id: "adguard" }
    }
}

impl ripdpi_tls_profiles::OutboundEchResolver for EncryptedDnsEchResolver {
    fn resolve_https_ech_config_list(
        &self,
        request: &ripdpi_tls_profiles::EchLookupRequest<'_>,
    ) -> Result<ripdpi_tls_profiles::EchLookupOutcome, ripdpi_tls_profiles::EchFacadeError> {
        let endpoint = super::encrypted_dns_endpoint_for_resolver_id(self.resolver_id);
        let transport = TransportConfig::Direct { route_experiment: None };
        match resolve_https_ech_configs_via_encrypted_dns_with_endpoint(request.inner_name, endpoint, &transport) {
            EchResolutionOutcome::Available(config_list) => {
                let public_name = ech_public_name(&config_list).ok_or_else(|| {
                    ripdpi_tls_profiles::EchFacadeError::LookupFailed("ECH public_name missing".into())
                })?;
                Ok(ripdpi_tls_profiles::EchLookupOutcome::Available { public_name, config_list })
            }
            EchResolutionOutcome::NotPublished => Ok(ripdpi_tls_profiles::EchLookupOutcome::NotPublished),
            EchResolutionOutcome::ResolutionFailed(error) => {
                Ok(ripdpi_tls_profiles::EchLookupOutcome::ResolutionFailed(error))
            }
        }
    }
}

pub fn resolve_outbound_ech_config_via_encrypted_dns(
    inner_name: &str,
) -> Result<Option<ripdpi_tls_profiles::OutboundEchConfig>, ripdpi_tls_profiles::EchFacadeError> {
    let request =
        ripdpi_tls_profiles::EchLookupRequest::new(inner_name, ripdpi_tls_profiles::EchLookupTransport::EncryptedDns);
    match ripdpi_tls_profiles::resolve_outbound_ech(
        &request,
        ripdpi_tls_profiles::EchPolicy::default(),
        &EncryptedDnsEchResolver::adguard(),
    )? {
        ripdpi_tls_profiles::EchSetup::Real(config) => Ok(Some(config)),
        ripdpi_tls_profiles::EchSetup::Grease | ripdpi_tls_profiles::EchSetup::OptedOut => Ok(None),
    }
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

pub fn ech_public_name(config_list: &[u8]) -> Option<String> {
    parse_ech_config_list(config_list).ok()?.configs.into_iter().find_map(|config| config.public_name)
}

#[cfg(test)]
mod tests {
    use super::*;

    const BORING_ECH_CONFIG_LIST: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e,
        0x04, 0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07,
        0x9b, 0x93, 0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];

    #[test]
    fn encrypted_dns_connect_hooks_install_dot_tls_builder() {
        let hooks = encrypted_dns_connect_hooks();

        assert!(hooks.dot_tls_connector_builder.is_some());
    }

    #[test]
    fn encrypted_ech_resolver_reuses_ech_config_list_parser_for_public_name() {
        assert_eq!(ech_public_name(BORING_ECH_CONFIG_LIST).as_deref(), Some("ech.com"));
    }
}
