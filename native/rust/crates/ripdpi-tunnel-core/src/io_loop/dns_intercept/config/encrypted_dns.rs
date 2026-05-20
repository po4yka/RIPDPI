use std::io;
use std::net::IpAddr;
use std::str::FromStr;
use std::time::Duration;

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport};
use ripdpi_tunnel_config::Config;
use rustls::pki_types::{pem::PemObject, CertificateDer};
use rustls::RootCertStore;

use super::super::protect_hooks::encrypted_dns_connect_hooks;

pub(in crate::io_loop) fn build_encrypted_dns_resolver(config: &Config) -> io::Result<Option<EncryptedDnsResolver>> {
    let Some(mapdns) = &config.mapdns else {
        return Ok(None);
    };

    let protocol = mapdns
        .encrypted_dns_protocol
        .as_deref()
        .and_then(parse_encrypted_dns_protocol)
        .or_else(|| mapdns.encrypted_dns_doh_url.as_deref().map(|_| EncryptedDnsProtocol::Doh));
    let Some(protocol) = protocol else {
        return Ok(None);
    };

    let bootstrap_ips = mapdns
        .encrypted_dns_bootstrap_ips
        .iter()
        .map(|value| {
            IpAddr::from_str(value).map_err(|err| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("invalid encrypted DNS bootstrap entry '{value}': {err}"),
                )
            })
        })
        .collect::<Result<Vec<_>, _>>()?;

    let doh_url = mapdns.encrypted_dns_doh_url.clone();
    let host = mapdns
        .encrypted_dns_host
        .clone()
        .unwrap_or_else(|| doh_url.as_deref().and_then(parse_url_host).unwrap_or_default());
    let tls_roots = parse_encrypted_dns_tls_roots(mapdns.encrypted_dns_tls_roots_pem.as_deref())?;
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        EncryptedDnsEndpoint {
            protocol,
            resolver_id: mapdns.resolver_id.clone(),
            host,
            port: mapdns.encrypted_dns_port.unwrap_or_default(),
            tls_server_name: mapdns.encrypted_dns_tls_server_name.clone(),
            bootstrap_ips,
            doh_url,
            dnscrypt_provider_name: mapdns.encrypted_dns_dnscrypt_provider_name.clone(),
            dnscrypt_public_key: mapdns.encrypted_dns_dnscrypt_public_key.clone(),
        },
        EncryptedDnsTransport::Direct,
        Duration::from_millis(u64::from(mapdns.dns_query_timeout_ms)),
        tls_roots,
        encrypted_dns_connect_hooks(config.misc.protect_path.clone()),
    )
    .map_err(|err| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("initialize encrypted DNS resolver ({protocol:?}): {err}"))
    })?;

    Ok(Some(resolver))
}

fn parse_encrypted_dns_protocol(value: &str) -> Option<EncryptedDnsProtocol> {
    match value.trim().to_ascii_lowercase().as_str() {
        "dot" => Some(EncryptedDnsProtocol::Dot),
        "dnscrypt" => Some(EncryptedDnsProtocol::DnsCrypt),
        "doh" => Some(EncryptedDnsProtocol::Doh),
        "doq" => Some(EncryptedDnsProtocol::Doq),
        _ => None,
    }
}

fn parse_url_host(value: &str) -> Option<String> {
    let (_, rest) = value.split_once("://")?;
    let authority = rest.split('/').next()?;
    let host = authority.trim_start_matches('[').trim_end_matches(']').split(':').next().unwrap_or_default().trim();
    (!host.is_empty()).then(|| host.to_string())
}

fn parse_encrypted_dns_tls_roots(value: Option<&str>) -> io::Result<Vec<CertificateDer<'static>>> {
    let Some(pem) = value.map(str::trim).filter(|entry| !entry.is_empty()) else {
        return Ok(Vec::new());
    };
    let roots = CertificateDer::pem_slice_iter(pem.as_bytes()).collect::<Result<Vec<_>, _>>().map_err(|err| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid encrypted DNS TLS root PEM: {err}"))
    })?;
    if roots.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "encrypted DNS TLS root PEM is empty"));
    }
    let mut root_store = RootCertStore::empty();
    for root in &roots {
        root_store.add(root.clone()).map_err(|err| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid encrypted DNS TLS root certificate: {err}"))
        })?;
    }
    Ok(roots)
}
