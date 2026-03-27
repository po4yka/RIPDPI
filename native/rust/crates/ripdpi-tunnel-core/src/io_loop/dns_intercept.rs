use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::str::FromStr;
use std::sync::Arc;

use ripdpi_dns_resolver::{
    EncryptedDnsEndpoint, EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsProtocol,
    EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_tunnel_config::Config;
use tokio::io::unix::AsyncFd;
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};

use crate::dns_cache::DnsCache;
use crate::Stats;

use super::bridge::try_write_tun_packet;
use super::packet::build_udp_response;

#[derive(Debug, Clone, Copy)]
pub(super) struct MapDnsRuntime {
    pub(super) intercept_addr: SocketAddr,
    pub(super) synthetic_net: u32,
    pub(super) synthetic_mask: u32,
    pub(super) intercept_port: u16,
}

#[derive(Debug, Clone)]
pub(super) struct DnsRequest {
    pub(super) src: SocketAddr,
    pub(super) query: Vec<u8>,
    pub(super) host: Option<String>,
}

#[derive(Debug, Clone)]
pub(super) struct DnsResponse {
    pub(super) src: SocketAddr,
    pub(super) query: Vec<u8>,
    pub(super) host: Option<String>,
    pub(super) upstream: Result<EncryptedDnsExchangeSuccess, String>,
    pub(super) resolver_error_kind: Option<EncryptedDnsErrorKind>,
}

pub(super) fn parse_mapdns_runtime(config: &Config) -> io::Result<Option<MapDnsRuntime>> {
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

    Ok(Some(MapDnsRuntime {
        intercept_addr: SocketAddr::new(IpAddr::V4(intercept_ip), mapdns.port),
        synthetic_net,
        synthetic_mask,
        intercept_port: mapdns.port,
    }))
}

pub(super) fn parse_dns_cache(config: &Config, dns_cache: Option<DnsCache>) -> io::Result<Option<DnsCache>> {
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

pub(super) fn parse_encrypted_dns_protocol(value: &str) -> Option<EncryptedDnsProtocol> {
    match value.trim().to_ascii_lowercase().as_str() {
        "dot" => Some(EncryptedDnsProtocol::Dot),
        "dnscrypt" => Some(EncryptedDnsProtocol::DnsCrypt),
        "doh" => Some(EncryptedDnsProtocol::Doh),
        "doq" => Some(EncryptedDnsProtocol::Doq),
        _ => None,
    }
}

pub(super) fn parse_url_host(value: &str) -> Option<String> {
    let (_, rest) = value.split_once("://")?;
    let authority = rest.split('/').next()?;
    let host = authority.trim_start_matches('[').trim_end_matches(']').split(':').next().unwrap_or_default().trim();
    (!host.is_empty()).then(|| host.to_string())
}

pub(super) fn build_encrypted_dns_resolver(config: &Config) -> io::Result<Option<EncryptedDnsResolver>> {
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

use std::time::Duration;

pub(super) fn dns_query_name(packet: &[u8]) -> Option<String> {
    if packet.len() < 12 {
        return None;
    }
    if u16::from_be_bytes([packet[4], packet[5]]) == 0 {
        return None;
    }

    let mut offset = 12usize;
    let mut labels = Vec::new();
    loop {
        let length = *packet.get(offset)? as usize;
        offset += 1;
        if length == 0 {
            break;
        }
        if length & 0b1100_0000 != 0 {
            return None;
        }
        let label = packet.get(offset..offset + length)?;
        labels.push(std::str::from_utf8(label).ok()?.to_string());
        offset += length;
    }
    if labels.is_empty() {
        None
    } else {
        Some(labels.join("."))
    }
}

pub(super) fn handle_dns_result(
    tun: &AsyncFd<std::fs::File>,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dns_cache: &mut DnsCache,
    response: DnsResponse,
) {
    match response.upstream {
        Ok(upstream) => match dns_cache.rewrite_response(&response.query, &upstream.response_bytes) {
            Ok(result) => {
                stats.record_dns_success(
                    &result.host,
                    result.cache_hits,
                    result.cache_misses,
                    Some(&upstream.endpoint_label),
                    Some(upstream.latency_ms),
                );
                let raw = build_udp_response(mapdns.intercept_addr, response.src, &result.response);
                try_write_tun_packet(tun, stats, &raw, "dns");
            }
            Err(err) => {
                let message = err.to_string();
                stats.record_dns_failure(response.host.as_deref(), &message, Some(&upstream.endpoint_label));
                match dns_cache.servfail_response(&response.query) {
                    Ok(servfail) => {
                        let raw = build_udp_response(mapdns.intercept_addr, response.src, &servfail);
                        try_write_tun_packet(tun, stats, &raw, "dns-servfail");
                    }
                    Err(servfail_err) => debug!("failed to synthesize SERVFAIL after rewrite error: {servfail_err}"),
                }
            }
        },
        Err(err) => {
            let formatted_error = response.resolver_error_kind.map(|kind| format!("{kind:?}: {err}")).unwrap_or(err);
            stats.record_dns_failure(response.host.as_deref(), &formatted_error, None);
            match dns_cache.servfail_response(&response.query) {
                Ok(servfail) => {
                    let raw = build_udp_response(mapdns.intercept_addr, response.src, &servfail);
                    try_write_tun_packet(tun, stats, &raw, "dns-servfail");
                }
                Err(servfail_err) => debug!("failed to synthesize SERVFAIL after upstream failure: {servfail_err}"),
            }
        }
    }
}

pub(super) fn resolve_mapped_target(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    dst: SocketAddr,
) -> SocketAddr {
    let Some(cache) = dns_cache.as_mut() else {
        return dst;
    };
    let IpAddr::V4(v4) = dst.ip() else {
        return dst;
    };
    let ip = u32::from(v4);
    if !cache.contains_mapped_ip(ip) {
        return dst;
    }
    let Some(entry) = cache.lookup(ip) else {
        warn!("mapdns reverse lookup miss for synthetic target {}", dst);
        return dst;
    };
    stats.record_last_host(Some(&entry.host));
    SocketAddr::new(IpAddr::V4(Ipv4Addr::from(entry.real_ip)), dst.port())
}

pub(super) fn spawn_dns_worker(
    resolver: EncryptedDnsResolver,
    cancel: CancellationToken,
) -> (tokio::sync::mpsc::Sender<DnsRequest>, tokio::sync::mpsc::Receiver<DnsResponse>) {
    let (req_tx, mut req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(super::DNS_QUEUE_CAPACITY);
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(super::DNS_QUEUE_CAPACITY);
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                request = req_rx.recv() => {
                    let Some(request) = request else {
                        break;
                    };
                    let upstream =
                        resolver
                            .exchange_with_metadata(&request.query)
                            .await
                            .map_err(|err| {
                                let kind = err.kind();
                                (kind, err.to_string())
                            });
                    let (resolver_error_kind, upstream) = match upstream {
                        Ok(success) => (None, Ok(success)),
                        Err((kind, message)) => (Some(kind), Err(message)),
                    };
                    if resp_tx.send(DnsResponse {
                        src: request.src,
                        query: request.query,
                        host: request.host,
                        upstream,
                        resolver_error_kind,
                    }).await.is_err() {
                        break;
                    }
                }
            }
        }
    });
    (req_tx, resp_rx)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use ripdpi_dns_resolver::EncryptedDnsProtocol;

    fn tunnel_config_with_mapdns(mapdns: Option<ripdpi_tunnel_config::MapDnsConfig>) -> ripdpi_tunnel_config::Config {
        ripdpi_tunnel_config::Config {
            tunnel: ripdpi_tunnel_config::TunnelConfig::default(),
            socks5: ripdpi_tunnel_config::Socks5Config {
                port: 1080,
                address: "127.0.0.1".to_string(),
                udp: None,
                udp_address: None,
                pipeline: None,
                username: None,
                password: None,
                mark: None,
            },
            mapdns,
            misc: ripdpi_tunnel_config::MiscConfig::default(),
        }
    }

    #[test]
    fn parse_mapdns_runtime_uses_address_defaults_for_network_and_mask() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 5300,
            network: None,
            netmask: None,
            cache_size: 8,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");

        assert_eq!(runtime.intercept_addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 5300));
        assert_eq!(runtime.synthetic_net, u32::from(Ipv4Addr::new(198, 18, 0, 10)));
        assert_eq!(runtime.synthetic_mask, u32::from(Ipv4Addr::new(255, 254, 0, 0)));
        assert_eq!(runtime.intercept_port, 5300);
    }

    #[test]
    fn parse_dns_cache_rejects_zero_cache_size() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 53,
            network: None,
            netmask: None,
            cache_size: 0,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let Err(err) = parse_dns_cache(&config, None) else {
            panic!("zero cache size should fail");
        };

        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        assert_eq!(err.to_string(), "mapdns.cache_size must be greater than zero");
    }

    #[test]
    fn build_encrypted_dns_resolver_uses_doh_url_defaults() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 53,
            network: None,
            netmask: None,
            cache_size: 16,
            resolver_id: Some("fixture".to_string()),
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
            encrypted_dns_doh_url: Some("https://dns.example.test/dns-query".to_string()),
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 2500,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let resolver = build_encrypted_dns_resolver(&config).expect("resolver build").expect("resolver");
        let endpoint = resolver.endpoint();

        assert_eq!(endpoint.protocol, EncryptedDnsProtocol::Doh);
        assert_eq!(endpoint.host, "dns.example.test");
        assert_eq!(endpoint.port, 443);
        assert_eq!(endpoint.tls_server_name.as_deref(), Some("dns.example.test"));
        assert_eq!(
            endpoint.bootstrap_ips,
            vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        );
        assert_eq!(endpoint.doh_url.as_deref(), Some("https://dns.example.test/dns-query"));
    }

    #[test]
    fn dns_query_name_extracts_labels_and_rejects_compression_pointers() {
        let plain_query = [
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, b'w', b'w', b'w', 0x07, b'e',
            b'x', b'a', b'm', b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
        ];
        let compressed_query = [0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xc0, 0x0c];

        assert_eq!(dns_query_name(&plain_query), Some("www.example.com".to_string()));
        assert_eq!(dns_query_name(&compressed_query), None);
    }
}
