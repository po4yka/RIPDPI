use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use ripdpi_config::{DesyncGroup, UpstreamSocksConfig};

use crate::types::{ProxyConfigError, ProxyUiWarpConfig, WARP_ROUTE_MODE_RULES};

use super::shared::parse_hosts;

const WARP_CONTROL_PLANE_HOSTS: &[&str] = &[
    "api.cloudflareclient.com",
    "connectivity.cloudflareclient.com",
    "engage.cloudflareclient.com",
    "downloads.cloudflareclient.com",
    "zero-trust-client.cloudflareclient.com",
    "pkg.cloudflareclient.com",
    "consumer-masque.cloudflareclient.com",
];

pub(crate) fn append_control_plane_group(
    groups: &mut Vec<DesyncGroup>,
    warp: &ProxyUiWarpConfig,
) -> Result<(), ProxyConfigError> {
    if !warp.enabled || !warp.built_in_rules_enabled {
        return Ok(());
    }

    let mut control_plane = DesyncGroup::new(groups.len());
    control_plane.matches.filters.hosts = parse_hosts(Some(&WARP_CONTROL_PLANE_HOSTS.join("\n")))?;
    control_plane.policy.label = "warp_control_plane".to_string();
    groups.push(control_plane);
    Ok(())
}

pub(crate) fn append_routed_group(
    groups: &mut Vec<DesyncGroup>,
    warp: &ProxyUiWarpConfig,
) -> Result<(), ProxyConfigError> {
    if !warp.enabled || warp.route_mode != WARP_ROUTE_MODE_RULES {
        return Ok(());
    }

    let local_socks_ip = warp.local_socks_host.trim().parse::<IpAddr>().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let local_socks_port = u16::try_from(warp.local_socks_port)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid warp.localSocksPort".to_string()))?;
    if local_socks_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid warp.localSocksPort".to_string()));
    }

    let route_hosts = parse_hosts(Some(warp.route_hosts.as_str()))?;
    if route_hosts.is_empty() {
        return Ok(());
    }

    let mut warp_group = DesyncGroup::new(groups.len());
    warp_group.matches.filters.hosts = route_hosts;
    warp_group.policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port) });
    warp_group.policy.label = "warp_routed".to_string();
    groups.push(warp_group);
    Ok(())
}
