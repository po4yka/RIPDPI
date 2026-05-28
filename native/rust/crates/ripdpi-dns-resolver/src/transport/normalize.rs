use url::Url;

use crate::dnscrypt::dnscrypt_verifying_key;
use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport};

pub(crate) fn normalize_endpoint(
    mut endpoint: EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
) -> Result<EncryptedDnsEndpoint, EncryptedDnsError> {
    if matches!(transport, EncryptedDnsTransport::Direct) && endpoint.bootstrap_ips.is_empty() {
        return Err(EncryptedDnsError::MissingBootstrapIps);
    }

    endpoint.host = endpoint.host.trim().to_string();
    if endpoint.port == 0 {
        endpoint.port = endpoint.protocol.default_port();
    }

    match endpoint.protocol {
        EncryptedDnsProtocol::Doh => normalize_doh_endpoint(&mut endpoint)?,
        EncryptedDnsProtocol::Odoh => normalize_odoh_endpoint(&mut endpoint)?,
        EncryptedDnsProtocol::Dot | EncryptedDnsProtocol::Doq => normalize_tls_endpoint(&mut endpoint)?,
        EncryptedDnsProtocol::DnsCrypt => normalize_dnscrypt_endpoint(&endpoint)?,
    }

    Ok(endpoint)
}

fn normalize_doh_endpoint(endpoint: &mut EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    let doh_url = endpoint.doh_url.clone().ok_or(EncryptedDnsError::MissingDohUrl)?;
    let parsed = Url::parse(&doh_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
    let url_host = parsed.host_str().ok_or(EncryptedDnsError::MissingHost)?.to_string();
    if endpoint.host.is_empty() {
        endpoint.host = url_host.clone();
    }
    if endpoint.port == endpoint.protocol.default_port() {
        endpoint.port = parsed.port_or_known_default().unwrap_or(443);
    }
    if endpoint.tls_server_name.as_deref().unwrap_or_default().is_empty() {
        endpoint.tls_server_name = Some(url_host);
    }
    Ok(())
}

fn normalize_odoh_endpoint(endpoint: &mut EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    let odoh = endpoint
        .odoh
        .as_ref()
        .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("ODoH config is required".to_string()))?;
    validate_odoh_non_collusion(odoh)?;
    let parsed = Url::parse(&odoh.proxy_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
    let url_host = parsed.host_str().ok_or(EncryptedDnsError::MissingHost)?.to_string();
    if endpoint.host.is_empty() {
        endpoint.host = url_host.clone();
    }
    if endpoint.port == endpoint.protocol.default_port() {
        endpoint.port = parsed.port_or_known_default().unwrap_or(443);
    }
    if endpoint.tls_server_name.as_deref().unwrap_or_default().is_empty() {
        endpoint.tls_server_name = Some(url_host);
    }
    Ok(())
}

fn validate_odoh_non_collusion(odoh: &crate::types::OdohEndpointConfig) -> Result<(), EncryptedDnsError> {
    let proxy_operator = odoh.proxy_operator_id.trim();
    if proxy_operator.is_empty() {
        return Err(EncryptedDnsError::InvalidEndpoint("ODoH proxy operator is required".to_string()));
    }
    let target_operator = odoh.target_operator_id.trim();
    if target_operator.is_empty() {
        return Err(EncryptedDnsError::InvalidEndpoint("ODoH target operator is required".to_string()));
    }
    if proxy_operator.eq_ignore_ascii_case(target_operator) {
        return Err(EncryptedDnsError::InvalidEndpoint(format!(
            "ODoH proxy and target must be non-colluding operators: {proxy_operator}"
        )));
    }
    Ok(())
}

fn normalize_tls_endpoint(endpoint: &mut EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    if endpoint.host.is_empty() {
        return Err(EncryptedDnsError::MissingHost);
    }
    if endpoint.tls_server_name.as_deref().unwrap_or_default().is_empty() {
        endpoint.tls_server_name = Some(endpoint.host.clone());
    }
    Ok(())
}

fn normalize_dnscrypt_endpoint(endpoint: &EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    if endpoint.host.is_empty() {
        return Err(EncryptedDnsError::MissingHost);
    }
    if endpoint.dnscrypt_provider_name.as_deref().map(str::trim).filter(|value| !value.is_empty()).is_none() {
        return Err(EncryptedDnsError::MissingDnsCryptProviderName);
    }
    dnscrypt_verifying_key(endpoint)?;
    Ok(())
}
