use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use ripdpi_config::{DesyncGroup, UpstreamSocksConfig};

use crate::types::{ProxyConfigError, ProxyUiRelayConfig, RELAY_KIND_OFF};

pub(crate) fn build_upstream(relay: &ProxyUiRelayConfig) -> Result<Option<UpstreamSocksConfig>, ProxyConfigError> {
    if !relay.enabled || relay.kind == RELAY_KIND_OFF {
        return Ok(None);
    }

    let local_socks_ip = relay.local_socks_host.trim().parse::<IpAddr>().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let local_socks_port = u16::try_from(relay.local_socks_port)
        .map_err(|_| ProxyConfigError::InvalidConfig("Invalid upstreamRelay.localSocksPort".to_string()))?;
    if local_socks_port == 0 {
        return Err(ProxyConfigError::InvalidConfig("Invalid upstreamRelay.localSocksPort".to_string()));
    }

    Ok(Some(UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port) }))
}

pub(crate) fn attach_upstream_to_existing_groups(groups: &mut [DesyncGroup], upstream: Option<UpstreamSocksConfig>) {
    let Some(upstream) = upstream else {
        return;
    };

    for group in groups {
        if group.is_actionable() && group.policy.ext_socks.is_none() {
            group.policy.ext_socks = Some(upstream);
            if group.policy.label.is_empty() {
                group.policy.label = "relay_upstream".to_string();
            }
        }
    }
}

pub(crate) fn attach_upstream_to_group(group: &mut DesyncGroup, upstream: Option<UpstreamSocksConfig>) {
    if let Some(upstream) = upstream {
        group.policy.ext_socks = Some(upstream);
    }
}
