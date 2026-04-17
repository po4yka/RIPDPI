use ripdpi_config::{DesyncGroup, QuicFakeProfile, QuicInitialMode, RuntimeConfig};
use ripdpi_packets::{
    IS_HTTP, IS_HTTPS, IS_UDP, MH_DMIX, MH_HMIX, MH_HOSTEXTRASPACE, MH_HOSTPAD, MH_HOSTTAB, MH_METHODEOL,
    MH_METHODSPACE, MH_SPACE, MH_UNIXEOL,
};

use crate::types::{
    ProxyConfigError, ProxyUiHostsConfig, ProxyUiParserEvasionConfig, ProxyUiProtocolConfig, ProxyUiQuicConfig,
    HOSTS_BLACKLIST, HOSTS_DISABLE, HOSTS_WHITELIST,
};

use super::shared::parse_hosts;

#[derive(Clone, Copy)]
pub(crate) struct GroupProtocolState {
    pub(crate) tcp_proto: u32,
    pub(crate) udp_enabled: bool,
}

pub(crate) fn apply_protocol_section(
    config: &mut RuntimeConfig,
    protocols: &ProxyUiProtocolConfig,
    quic: &ProxyUiQuicConfig,
) -> Result<(), ProxyConfigError> {
    config.network.resolve = protocols.resolve_domains;
    config.quic.initial_mode = parse_quic_initial_mode(&quic.initial_mode)?;
    config.quic.support_v1 = quic.support_v1;
    config.quic.support_v2 = quic.support_v2;
    Ok(())
}

pub(crate) fn append_whitelist_group(
    groups: &mut Vec<DesyncGroup>,
    hosts: &ProxyUiHostsConfig,
) -> Result<(), ProxyConfigError> {
    if hosts.mode == HOSTS_WHITELIST {
        let mut whitelist = DesyncGroup::new(groups.len());
        whitelist.matches.filters.hosts = parse_hosts(hosts.entries.as_deref())?;
        groups.push(whitelist);
    }
    Ok(())
}

pub(crate) fn build_primary_group(
    index: usize,
    hosts: &ProxyUiHostsConfig,
    protocols: &ProxyUiProtocolConfig,
    quic: &ProxyUiQuicConfig,
    parser_evasions: &ProxyUiParserEvasionConfig,
) -> Result<(DesyncGroup, GroupProtocolState), ProxyConfigError> {
    let mut group = DesyncGroup::new(index);
    match hosts.mode.as_str() {
        HOSTS_DISABLE | HOSTS_WHITELIST => {}
        HOSTS_BLACKLIST => {
            group.matches.filters.hosts = parse_hosts(hosts.entries.as_deref())?;
        }
        _ => return Err(ProxyConfigError::InvalidConfig("Unknown hostsMode".to_string())),
    }

    let tcp_proto = (u32::from(protocols.desync_http) * IS_HTTP) | (u32::from(protocols.desync_https) * IS_HTTPS);
    group.matches.proto = tcp_proto;
    group.actions.quic_fake_profile = parse_quic_fake_profile(&quic.fake_profile)?;
    group.actions.quic_fake_host = {
        let host = quic.fake_host.trim();
        if host.is_empty() {
            None
        } else {
            ripdpi_config::normalize_quic_fake_host(host).ok()
        }
    };
    group.actions.mod_http = (u32::from(parser_evasions.host_mixed_case) * MH_HMIX)
        | (u32::from(parser_evasions.domain_mixed_case) * MH_DMIX)
        | (u32::from(parser_evasions.host_remove_spaces) * MH_SPACE)
        | (u32::from(parser_evasions.http_method_eol) * MH_METHODEOL)
        | (u32::from(parser_evasions.http_method_space) * MH_METHODSPACE)
        | (u32::from(parser_evasions.http_unix_eol) * MH_UNIXEOL)
        | (u32::from(parser_evasions.http_host_pad) * MH_HOSTPAD)
        | (u32::from(parser_evasions.http_host_extra_space) * MH_HOSTEXTRASPACE)
        | (u32::from(parser_evasions.http_host_tab) * MH_HOSTTAB);

    Ok((group, GroupProtocolState { tcp_proto, udp_enabled: protocols.desync_udp }))
}

pub(crate) fn append_udp_group(groups: &mut Vec<DesyncGroup>, primary_group: &DesyncGroup, udp_enabled: bool) {
    if !udp_enabled {
        return;
    }

    let mut udp_group = DesyncGroup::new(groups.len());
    udp_group.matches.proto = IS_UDP;
    udp_group.actions.udp_chain = primary_group.actions.udp_chain.clone();
    udp_group.actions.quic_fake_profile = primary_group.actions.quic_fake_profile;
    udp_group.actions.quic_fake_host = primary_group.actions.quic_fake_host.clone();
    udp_group.matches.activation_filter = primary_group.matches.activation_filter.clone();
    groups.push(udp_group);
}

pub fn parse_quic_initial_mode(value: &str) -> Result<QuicInitialMode, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "disabled" => Ok(QuicInitialMode::Disabled),
        "route" => Ok(QuicInitialMode::Route),
        "route_and_cache" => Ok(QuicInitialMode::RouteAndCache),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown quicInitialMode: {value}"))),
    }
}

pub fn parse_quic_fake_profile(value: &str) -> Result<QuicFakeProfile, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "disabled" | "" => Ok(QuicFakeProfile::Disabled),
        "compat_default" => Ok(QuicFakeProfile::CompatDefault),
        "realistic_initial" => Ok(QuicFakeProfile::RealisticInitial),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown quicFakeProfile: {value}"))),
    }
}
