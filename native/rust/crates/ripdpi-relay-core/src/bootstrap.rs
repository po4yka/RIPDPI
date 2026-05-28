use std::io;
use std::net::{IpAddr, SocketAddr};

use crate::config::{RelayBackendConfig, ResolvedChainRelayHopConfig, ResolvedRelayRuntimeConfig};

pub(crate) trait RelayEndpointBootstrapResolver {
    async fn resolve_direct(&mut self, host: &str, port: u16) -> io::Result<SocketAddr>;
}

pub(crate) struct SystemRelayEndpointBootstrapResolver;

impl RelayEndpointBootstrapResolver for SystemRelayEndpointBootstrapResolver {
    async fn resolve_direct(&mut self, host: &str, port: u16) -> io::Result<SocketAddr> {
        let mut addrs = tokio::net::lookup_host((host, port)).await?;
        addrs.next().ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "relay endpoint did not resolve"))
    }
}

pub(crate) async fn bootstrap_relay_endpoints(
    config: &ResolvedRelayRuntimeConfig,
) -> io::Result<ResolvedRelayRuntimeConfig> {
    let mut resolver = SystemRelayEndpointBootstrapResolver;
    bootstrap_relay_endpoints_with(config, &mut resolver).await
}

pub(crate) async fn bootstrap_relay_endpoints_with(
    config: &ResolvedRelayRuntimeConfig,
    resolver: &mut impl RelayEndpointBootstrapResolver,
) -> io::Result<ResolvedRelayRuntimeConfig> {
    let mut bootstrapped = config.clone();
    match &mut bootstrapped.backend {
        RelayBackendConfig::ChainRelay(chain) => {
            if let Some(entry) = chain.entry.as_mut() {
                resolve_chain_entry_hop(entry, resolver).await?;
            } else {
                chain.entry_server = resolve_endpoint(&chain.entry_server, chain.entry_port, resolver).await?;
            }
        }
        RelayBackendConfig::ShadowTlsV3(shadowtls) => {
            bootstrapped.common.server =
                resolve_endpoint(&bootstrapped.common.server, bootstrapped.common.server_port, resolver).await?;
            if let Some(inner) = shadowtls.inner.as_mut() {
                inner.server = resolve_endpoint(&inner.server, inner.server_port, resolver).await?;
            }
        }
        RelayBackendConfig::Masque(masque) => {
            masque.proxy_socket_addr = Some(resolve_masque_url_endpoint(&masque.url, resolver).await?);
        }
        RelayBackendConfig::Unsupported(_) => {}
        _ => {
            bootstrapped.common.server =
                resolve_endpoint(&bootstrapped.common.server, bootstrapped.common.server_port, resolver).await?;
        }
    }
    Ok(bootstrapped)
}

async fn resolve_chain_entry_hop(
    hop: &mut ResolvedChainRelayHopConfig,
    resolver: &mut impl RelayEndpointBootstrapResolver,
) -> io::Result<()> {
    if hop.server.is_empty() {
        return Ok(());
    }
    hop.server = resolve_endpoint(&hop.server, hop.server_port, resolver).await?;
    Ok(())
}

async fn resolve_masque_url_endpoint(
    url: &str,
    resolver: &mut impl RelayEndpointBootstrapResolver,
) -> io::Result<SocketAddr> {
    let parsed = url::Url::parse(url).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let host =
        parsed.host_str().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?;
    let port = parsed.port().unwrap_or(443);
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    emit_direct_bootstrap_lookup(host, port);
    resolver.resolve_direct(host, port).await
}

async fn resolve_endpoint(
    host: &str,
    port: i32,
    resolver: &mut impl RelayEndpointBootstrapResolver,
) -> io::Result<String> {
    if host.parse::<IpAddr>().is_ok() {
        return Ok(host.to_string());
    }
    let port = u16::try_from(port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "relay endpoint port must fit u16"))?;
    emit_direct_bootstrap_lookup(host, port);
    Ok(resolver.resolve_direct(host, port).await?.ip().to_string())
}

fn emit_direct_bootstrap_lookup(host: &str, port: u16) {
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "relay_endpoint_bootstrap_direct_lookup",
        "direct relay endpoint bootstrap lookup host={host} port={port}"
    );
}
