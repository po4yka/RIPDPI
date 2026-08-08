mod encrypted_dns;

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use ripdpi_tunnel_config::Config;

use crate::dns_cache::DnsCache;

use super::MapDnsRuntime;

pub(in crate::io_loop) use encrypted_dns::build_encrypted_dns_resolver;
#[cfg(test)]
pub(in crate::io_loop) use encrypted_dns::mapdns_resolver_transport;

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
    if let Some(mut dns_cache) = dns_cache {
        dns_cache.set_ipv6_enabled(config.tunnel.ipv6.is_some());
        return Ok(Some(dns_cache));
    }

    let Some(runtime) = parse_mapdns_runtime(config)? else {
        return Ok(None);
    };
    let cache_size = config.mapdns.as_ref().map(|value| value.cache_size as usize).unwrap_or_default();
    if cache_size == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "mapdns.cache_size must be greater than zero"));
    }

    let mut dns_cache = DnsCache::new(runtime.synthetic_net, runtime.synthetic_mask, cache_size).map_err(|err| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid mapdns cache configuration: {err}"))
    })?;
    dns_cache.set_ipv6_enabled(config.tunnel.ipv6.is_some());
    Ok(Some(dns_cache))
}
