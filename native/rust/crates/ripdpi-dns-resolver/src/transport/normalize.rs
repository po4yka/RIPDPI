use url::Url;

use crate::dnscrypt::dnscrypt_verifying_key;
use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport};

#[cfg(test)]
mod socks5_contract_tests;

pub(crate) fn normalize_endpoint(
    mut endpoint: EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
) -> Result<EncryptedDnsEndpoint, EncryptedDnsError> {
    if let EncryptedDnsTransport::Socks5 { credentials: Some(credentials), .. } = transport
        && [&credentials.username, &credentials.password].iter().any(|value| !(1..=255).contains(&value.len()))
    {
        return Err(EncryptedDnsError::InvalidEndpoint("SOCKS5 credentials must contain 1..=255 bytes".into()));
    }
    if matches!(transport, EncryptedDnsTransport::Direct) && endpoint.bootstrap_ips.is_empty() {
        return Err(EncryptedDnsError::MissingBootstrapIps);
    }

    endpoint.host = endpoint.host.trim().to_string();
    if endpoint.port == 0 {
        endpoint.port = endpoint.protocol.default_port();
    }
    if endpoint.protocol == EncryptedDnsProtocol::Doq && endpoint.port == 53 {
        // RFC 9250 section 4.1.1: DoQ connections MUST NOT use UDP port 53.
        return Err(EncryptedDnsError::InvalidEndpoint("DoQ must not use UDP port 53".into()));
    }

    match endpoint.protocol {
        EncryptedDnsProtocol::Doh => normalize_doh_endpoint(&mut endpoint)?,
        EncryptedDnsProtocol::Odoh => normalize_odoh_endpoint(&mut endpoint)?,
        EncryptedDnsProtocol::Dot | EncryptedDnsProtocol::Doq => normalize_tls_endpoint(&mut endpoint)?,
        EncryptedDnsProtocol::DnsCrypt => normalize_dnscrypt_endpoint(&endpoint)?,
    }

    let uses_tls = match endpoint.protocol {
        EncryptedDnsProtocol::Dot | EncryptedDnsProtocol::Doq => true,
        EncryptedDnsProtocol::Doh | EncryptedDnsProtocol::Odoh => super::client::doh_uses_tls(&endpoint),
        EncryptedDnsProtocol::DnsCrypt => false,
    };
    if uses_tls {
        if matches!(endpoint.protocol, EncryptedDnsProtocol::Doh | EncryptedDnsProtocol::Odoh) {
            // reqwest uses the URL identity even when the manual TLS path has an override.
            let url_host = super::client::request_url_host(&endpoint).ok_or(EncryptedDnsError::MissingHost)?;
            ripdpi_tls_profiles::normalize_server_name(&url_host)
                .map_err(|error| EncryptedDnsError::InvalidEndpoint(error.to_string()))?;
        }
        if let Some(ip) = endpoint.host.strip_prefix('[').and_then(|host| host.strip_suffix(']'))
            && let Ok(ip) = ip.parse::<std::net::Ipv6Addr>()
        {
            endpoint.host = ip.to_string();
        }
        endpoint.tls_server_name = endpoint
            .tls_server_name
            .as_deref()
            .map(ripdpi_tls_profiles::normalize_server_name)
            .transpose()
            .map_err(|error| EncryptedDnsError::InvalidEndpoint(error.to_string()))?;
    }
    Ok(endpoint)
}

fn normalize_doh_endpoint(endpoint: &mut EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    let doh_url = endpoint.doh_url.clone().ok_or(EncryptedDnsError::MissingDohUrl)?;
    let parsed = Url::parse(&doh_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
    let url_host = parsed.host_str().ok_or(EncryptedDnsError::MissingHost)?.to_string();
    // RFC 8484 section 5: DoH runs over HTTPS. Plain HTTP is allowed only for a loopback
    // fixture: the URL host, the connect host and every bootstrap IP must stay on the device.
    let is_loopback_name = |name: &str| {
        name.eq_ignore_ascii_case("localhost")
            || name.trim_matches(['[', ']']).parse::<std::net::IpAddr>().is_ok_and(|ip| ip.is_loopback())
    };
    let loopback = is_loopback_name(&url_host)
        && (endpoint.host.is_empty() || is_loopback_name(&endpoint.host))
        && endpoint.bootstrap_ips.iter().all(std::net::IpAddr::is_loopback);
    // `Url::parse` lowercases the scheme.
    match parsed.scheme() {
        "https" => {}
        "http" if loopback => {}
        _ => return Err(EncryptedDnsError::InvalidUrl("DoH URL must use https (RFC 8484 section 5)".into())),
    }
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

#[cfg(test)]
mod tls_identity_tests;
