use std::io;
use std::net::IpAddr;

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol};
use ripdpi_proxy_config::ProxyEncryptedDnsContext;

pub fn encrypted_dns_label(context: &ProxyEncryptedDnsContext) -> String {
    context
        .doh_url
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{}:{}", context.host, context.port))
}

pub(crate) fn encrypted_dns_endpoint(context: &ProxyEncryptedDnsContext) -> io::Result<EncryptedDnsEndpoint> {
    let invalid = |message: String| io::Error::new(io::ErrorKind::InvalidInput, message);
    // ODoH needs relay and target config that this context does not carry, so it is refused too.
    let protocol = match context.protocol.trim().to_ascii_lowercase().as_str() {
        "doh" => EncryptedDnsProtocol::Doh,
        "dot" => EncryptedDnsProtocol::Dot,
        "doq" => EncryptedDnsProtocol::Doq,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        other => return Err(invalid(format!("unsupported encrypted DNS protocol for bootstrap: {other:?}"))),
    };
    if protocol == EncryptedDnsProtocol::Doh
        && let Some(url) = context.doh_url.as_deref()
        && !is_https_or_loopback_http(url)
    {
        return Err(invalid("encrypted DNS bootstrap DoH URL must use https".to_string()));
    }
    let bootstrap_ips = context
        .bootstrap_ips
        .iter()
        .map(|value| value.parse::<IpAddr>())
        .collect::<Result<Vec<_>, _>>()
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    if bootstrap_ips.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "encrypted DNS bootstrap requires at least one bootstrap IP",
        ));
    }

    Ok(EncryptedDnsEndpoint {
        protocol,
        resolver_id: context.resolver_id.clone(),
        host: context.host.clone(),
        port: context.port,
        tls_server_name: context.tls_server_name.clone(),
        bootstrap_ips,
        doh_url: context.doh_url.clone(),
        dnscrypt_provider_name: context.dnscrypt_provider_name.clone(),
        dnscrypt_public_key: context.dnscrypt_public_key.clone(),
        odoh: None,
    })
}

/// RFC 8484 DoH is https only; plain http is accepted for a loopback test resolver.
fn is_https_or_loopback_http(url: &str) -> bool {
    let url = url.trim().to_ascii_lowercase();
    if url.starts_with("https://") {
        return true;
    }
    let Some(rest) = url.strip_prefix("http://") else {
        return false;
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    let host = match authority.strip_prefix('[') {
        Some(bracketed) => bracketed.split(']').next().unwrap_or_default(),
        None => authority.rsplit_once(':').map_or(authority, |(host, _)| host),
    };
    host == "localhost" || host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
}
