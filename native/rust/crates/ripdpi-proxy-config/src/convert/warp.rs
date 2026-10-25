use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use ripdpi_config::{DesyncGroup, UpstreamSocksConfig};

use crate::types::{ProxyConfigError, ProxyUiWarpConfig, WARP_ROUTE_MODE_RULES};

use super::shared::parse_hosts;

const AMNEZIAWG_ALL_TRAFFIC_ROUTE_HOSTS: &str = "__ripdpi_awg_all_traffic__";

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

    let route_all_hosts = warp.route_hosts.trim() == AMNEZIAWG_ALL_TRAFFIC_ROUTE_HOSTS;
    let route_hosts = if route_all_hosts { Vec::new() } else { parse_hosts(Some(warp.route_hosts.as_str()))? };
    if route_hosts.is_empty() && !route_all_hosts {
        return Ok(());
    }

    let mut warp_group = DesyncGroup::new(groups.len());
    warp_group.matches.filters.hosts = route_hosts;
    warp_group.policy.ext_socks =
        Some(UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port), auth: None });
    warp_group.policy.label = "warp_routed".to_string();
    groups.push(warp_group);
    Ok(())
}

pub(crate) fn attach_all_traffic_upstream(
    groups: &mut [DesyncGroup],
    warp: &ProxyUiWarpConfig,
) -> Result<(), ProxyConfigError> {
    if !warp.enabled
        || warp.route_mode != WARP_ROUTE_MODE_RULES
        || warp.route_hosts.trim() != AMNEZIAWG_ALL_TRAFFIC_ROUTE_HOSTS
    {
        return Ok(());
    }

    let local_socks_ip = warp.local_socks_host.trim().parse::<IpAddr>().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let local_socks_port = u16::try_from(warp.local_socks_port)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid warp.localSocksPort".to_string()))?;
    if local_socks_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid warp.localSocksPort".to_string()));
    }
    let upstream = UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port), auth: None };

    for group in groups {
        group.policy.ext_socks = Some(upstream.clone());
    }
    Ok(())
}

/// Accept resolver authority only for the standalone AWG loopback route.
pub(crate) fn resolver_authority(warp: &ProxyUiWarpConfig) -> Result<Vec<IpAddr>, ProxyConfigError> {
    if warp.awg_dns_servers.is_empty() {
        return Ok(Vec::new());
    }
    let valid_upstream = warp.local_socks_host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
        && u16::try_from(warp.local_socks_port).is_ok_and(|port| port != 0);
    if !warp.enabled
        || warp.route_mode != WARP_ROUTE_MODE_RULES
        || warp.route_hosts != AMNEZIAWG_ALL_TRAFFIC_ROUTE_HOSTS
        || !valid_upstream
    {
        return Err(ProxyConfigError::InvalidConfig("AWG DNS authority requires an active loopback AWG route".into()));
    }
    warp.awg_dns_servers
        .iter()
        .map(|address| {
            address
                .parse::<IpAddr>()
                .map_err(|_| ProxyConfigError::InvalidConfig("AWG DNS servers must be numeric addresses".into()))
        })
        .collect()
}

#[cfg(test)]
mod resolver_tests {
    use super::*;

    fn active() -> ProxyUiWarpConfig {
        ProxyUiWarpConfig {
            enabled: true,
            route_mode: WARP_ROUTE_MODE_RULES.into(),
            route_hosts: AMNEZIAWG_ALL_TRAFFIC_ROUTE_HOSTS.into(),
            awg_dns_servers: vec!["10.77.0.1".into(), "fd77::1".into()],
            ..ProxyUiWarpConfig::default()
        }
    }

    #[test]
    fn resolver_authority_requires_numeric_addresses_and_active_loopback_awg() {
        assert_eq!(
            resolver_authority(&active()).unwrap(),
            vec!["10.77.0.1".parse::<IpAddr>().unwrap(), "fd77::1".parse().unwrap()]
        );
        assert!(resolver_authority(&ProxyUiWarpConfig::default()).unwrap().is_empty());
        let invalid = [
            ProxyUiWarpConfig { enabled: false, ..active() },
            ProxyUiWarpConfig { route_mode: "off".into(), ..active() },
            ProxyUiWarpConfig { route_hosts: "example.org".into(), ..active() },
            ProxyUiWarpConfig { local_socks_host: "example.org".into(), ..active() },
            ProxyUiWarpConfig { local_socks_host: "192.0.2.1".into(), ..active() },
            ProxyUiWarpConfig { local_socks_port: 0, ..active() },
            ProxyUiWarpConfig { local_socks_port: 65536, ..active() },
        ];
        for config in invalid {
            assert!(resolver_authority(&config).is_err());
        }
        for address in ["resolver.example", "", "10.77.0.1/32", "[fd77::1]", "fd77::1%1", " 10.77.0.1", "999.1.1.1"] {
            assert!(
                resolver_authority(&ProxyUiWarpConfig { awg_dns_servers: vec![address.into()], ..active() }).is_err()
            );
        }
    }

    #[test]
    fn resolver_wire_field_is_absent_when_empty_and_round_trips_when_active() {
        let empty: ProxyUiWarpConfig = serde_json::from_str("{}").unwrap();
        assert!(empty.awg_dns_servers.is_empty());
        assert!(serde_json::to_value(empty).unwrap().get("awgDnsServers").is_none());
        let value = serde_json::to_value(active()).unwrap();
        assert_eq!(serde_json::from_value::<ProxyUiWarpConfig>(value).unwrap(), active());
        for value in [serde_json::json!(null), serde_json::json!("10.77.0.1"), serde_json::json!([42])] {
            assert!(serde_json::from_value::<ProxyUiWarpConfig>(serde_json::json!({"awgDnsServers": value})).is_err());
        }
    }
}
