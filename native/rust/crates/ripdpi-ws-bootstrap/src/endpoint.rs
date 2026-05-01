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
    let protocol = match context.protocol.trim().to_ascii_lowercase().as_str() {
        "dot" => EncryptedDnsProtocol::Dot,
        "doq" => EncryptedDnsProtocol::Doq,
        "dnscrypt" => EncryptedDnsProtocol::DnsCrypt,
        _ => EncryptedDnsProtocol::Doh,
    };
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
    })
}
