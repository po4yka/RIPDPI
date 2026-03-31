use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::str::FromStr;
use std::time::Duration;

use ripdpi_dns_resolver::{EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsResolver, EncryptedDnsTransport};
use ripdpi_tunnel_config::Config;

use crate::dns_cache::DnsCache;

use super::MapDnsRuntime;

pub(in crate::io_loop) fn parse_mapdns_runtime(config: &Config) -> io::Result<Option<MapDnsRuntime>> {
    let Some(mapdns) = &config.mapdns else {
        return Ok(None);
    };

    let intercept_ip = mapdns.address.parse::<Ipv4Addr>().map_err(|err| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid mapdns.address '{}': {err}", mapdns.address))
    })?;
    let synthetic_net =
        mapdns.network.as_deref().unwrap_or(mapdns.address.as_str()).parse::<Ipv4Addr>().map(u32::from).map_err(
            |err| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!(
                        "invalid mapdns.network '{}': {err}",
                        mapdns.network.as_deref().unwrap_or(mapdns.address.as_str())
                    ),
                )
            },
        )?;
    let synthetic_mask =
        mapdns.netmask.as_deref().unwrap_or("255.254.0.0").parse::<Ipv4Addr>().map(u32::from).map_err(|err| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("invalid mapdns.netmask '{}': {err}", mapdns.netmask.as_deref().unwrap_or("255.254.0.0")),
            )
        })?;
    let synthetic_net = synthetic_net & synthetic_mask;

    Ok(Some(MapDnsRuntime {
        intercept_addr: SocketAddr::new(IpAddr::V4(intercept_ip), mapdns.port),
        synthetic_net,
        synthetic_mask,
        intercept_port: mapdns.port,
    }))
}

pub(in crate::io_loop) fn parse_dns_cache(
    config: &Config,
    dns_cache: Option<DnsCache>,
) -> io::Result<Option<DnsCache>> {
    if dns_cache.is_some() {
        return Ok(dns_cache);
    }

    let Some(runtime) = parse_mapdns_runtime(config)? else {
        return Ok(None);
    };
    let cache_size = config.mapdns.as_ref().map(|value| value.cache_size as usize).unwrap_or_default();
    if cache_size == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "mapdns.cache_size must be greater than zero"));
    }

    Ok(Some(DnsCache::new(runtime.synthetic_net, runtime.synthetic_mask, cache_size)))
}

pub(in crate::io_loop) fn parse_encrypted_dns_protocol(value: &str) -> Option<EncryptedDnsProtocol> {
    match value.trim().to_ascii_lowercase().as_str() {
        "dot" => Some(EncryptedDnsProtocol::Dot),
        "dnscrypt" => Some(EncryptedDnsProtocol::DnsCrypt),
        "doh" => Some(EncryptedDnsProtocol::Doh),
        "doq" => Some(EncryptedDnsProtocol::Doq),
        _ => None,
    }
}

pub(in crate::io_loop) fn parse_url_host(value: &str) -> Option<String> {
    let (_, rest) = value.split_once("://")?;
    let authority = rest.split('/').next()?;
    let host = authority.trim_start_matches('[').trim_end_matches(']').split(':').next().unwrap_or_default().trim();
    (!host.is_empty()).then(|| host.to_string())
}

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
    let resolver = EncryptedDnsResolver::with_timeout(
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
    )
    .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;

    Ok(Some(resolver))
}
