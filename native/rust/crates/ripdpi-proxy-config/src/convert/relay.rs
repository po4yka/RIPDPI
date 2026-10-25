use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use ripdpi_config::{DesyncGroup, UpstreamSocksAuth, UpstreamSocksConfig};

use crate::types::{ProxyConfigError, ProxyUiRelayConfig, RELAY_KIND_OFF};

pub(crate) fn validate_egress_requirements(
    relay: &ProxyUiRelayConfig,
    udp_associate_enabled: bool,
) -> Result<(), ProxyConfigError> {
    if relay.enabled && relay.kind != RELAY_KIND_OFF && udp_associate_enabled && !relay.udp_enabled {
        return Err(ProxyConfigError::InvalidConfig(format!(
            "relay transport {} is missing required capability UDP ASSOCIATE",
            relay.kind
        )));
    }
    Ok(())
}

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

    let auth = build_upstream_auth(relay)?;
    Ok(Some(UpstreamSocksConfig { addr: SocketAddr::new(local_socks_ip, local_socks_port), auth }))
}

/// The relay listener's per-session RFC 1929 credentials. Both fields or neither: a half-set pair would
/// otherwise degrade to NO-AUTH against a listener that requires authentication.
fn build_upstream_auth(relay: &ProxyUiRelayConfig) -> Result<Option<UpstreamSocksAuth>, ProxyConfigError> {
    let invalid = || ProxyConfigError::InvalidConfig("Invalid upstreamRelay local SOCKS credentials".to_string());
    match (&relay.local_socks_username, &relay.local_socks_password) {
        (None, None) => Ok(None),
        (Some(username), Some(password)) => {
            // RFC 1929 carries each field behind a one-byte length.
            let valid = |value: &String| (1..=255).contains(&value.len());
            if !valid(username) || !valid(&password.0) {
                return Err(invalid());
            }
            Ok(Some(UpstreamSocksAuth { username: username.clone(), password: password.0.clone() }))
        }
        _ => Err(invalid()),
    }
}

pub(crate) fn attach_upstream_to_existing_groups(groups: &mut [DesyncGroup], upstream: Option<&UpstreamSocksConfig>) {
    let Some(upstream) = upstream else {
        return;
    };

    for group in groups {
        if group.policy.ext_socks.is_none() {
            group.policy.ext_socks = Some(upstream.clone());
            if group.policy.label.is_empty() {
                group.policy.label = "relay_upstream".to_string();
            }
        }
    }
}

pub(crate) fn attach_upstream_to_group(group: &mut DesyncGroup, upstream: Option<&UpstreamSocksConfig>) {
    if let Some(upstream) = upstream {
        group.policy.ext_socks = Some(upstream.clone());
    }
}
