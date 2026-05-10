use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, RuntimeConfig, TcpChainStepKind};
use ripdpi_runtime_decision_ports::ConnectionRoute;

use super::{connect_timeout, protect_path_owned, selected_desync_group};

pub fn tcp_fast_open_enabled(config: &RuntimeConfig) -> bool {
    config.network.tfo
}

pub fn group_uses_direct_syn_data_tfo(group: &DesyncGroup) -> bool {
    group.policy.ext_socks.is_none()
        && group.actions.tcp_chain.iter().any(|step| step.kind() == TcpChainStepKind::SynData)
}

pub fn group_requests_direct_syn_data_tfo(group: &DesyncGroup, payload: Option<&[u8]>) -> bool {
    payload.is_some_and(|bytes| !bytes.is_empty()) && group_uses_direct_syn_data_tfo(group)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TcpRouteSynDataSettings {
    direct_syn_data_groups: Vec<bool>,
}

pub fn tcp_route_syn_data_settings(config: &RuntimeConfig) -> TcpRouteSynDataSettings {
    TcpRouteSynDataSettings {
        direct_syn_data_groups: config.groups.iter().map(group_uses_direct_syn_data_tfo).collect(),
    }
}

pub fn connection_route_requests_direct_syn_data_tfo_with(
    settings: &TcpRouteSynDataSettings,
    route: &ConnectionRoute,
    payload: Option<&[u8]>,
) -> bool {
    payload.is_some_and(|bytes| !bytes.is_empty())
        && settings.direct_syn_data_groups.get(route.group_index).copied().unwrap_or(false)
}

#[derive(Clone)]
pub struct TcpRouteConnectSettings {
    pub tfo_enabled: bool,
    pub upstream_socks_addr: Option<SocketAddr>,
    pub pre_connect_rcvbuf: Option<u32>,
    pub connect_timeout: Option<Duration>,
    pub protect_path: Option<String>,
    pub drop_sack: bool,
    pub window_clamp: Option<u32>,
    pub strip_timestamps: bool,
}

#[derive(Clone)]
pub struct TcpRouteConnectProfile {
    pub tfo_enabled: bool,
    pub direct_syn_data_tfo: bool,
    pub upstream_socks_addr: Option<SocketAddr>,
    pub pre_connect_rcvbuf: Option<u32>,
    pub connect_timeout: Option<Duration>,
    pub protect_path: Option<String>,
    pub drop_sack: bool,
    pub window_clamp: Option<u32>,
    pub strip_timestamps: bool,
}

#[derive(Clone)]
pub struct TcpRouteConnectSettingsTable {
    groups: Vec<TcpRouteConnectProfile>,
}

pub fn tcp_route_connect_settings_table(config: &RuntimeConfig) -> TcpRouteConnectSettingsTable {
    TcpRouteConnectSettingsTable {
        groups: config.groups.iter().map(|group| tcp_route_connect_profile(config, group)).collect(),
    }
}

fn tcp_route_connect_profile(config: &RuntimeConfig, group: &DesyncGroup) -> TcpRouteConnectProfile {
    let pre_connect_rcvbuf = group.actions.wsize.map(|w| match w.scale {
        Some(scale) if (scale as u32) < 32 => w.window.checked_shl(scale as u32).unwrap_or(u32::MAX),
        Some(_) => u32::MAX,
        None => w.window,
    });
    TcpRouteConnectProfile {
        tfo_enabled: tcp_fast_open_enabled(config),
        direct_syn_data_tfo: group_uses_direct_syn_data_tfo(group),
        upstream_socks_addr: group.policy.ext_socks.map(|upstream| upstream.addr),
        pre_connect_rcvbuf,
        connect_timeout: connect_timeout(config),
        protect_path: protect_path_owned(config),
        drop_sack: group.actions.drop_sack,
        window_clamp: group.actions.wsize.map(|w| w.window).or(group.actions.window_clamp),
        strip_timestamps: group.actions.strip_timestamps,
    }
}

pub fn tcp_route_connect_settings_with(
    table: &TcpRouteConnectSettingsTable,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> Option<TcpRouteConnectSettings> {
    let profile = table.groups.get(group_index)?;
    let tfo_enabled = allow_tfo
        && (profile.tfo_enabled || (payload.is_some_and(|bytes| !bytes.is_empty()) && profile.direct_syn_data_tfo));
    Some(TcpRouteConnectSettings {
        tfo_enabled,
        upstream_socks_addr: profile.upstream_socks_addr,
        pre_connect_rcvbuf: profile.pre_connect_rcvbuf,
        connect_timeout: profile.connect_timeout,
        protect_path: profile.protect_path.clone(),
        drop_sack: profile.drop_sack,
        window_clamp: profile.window_clamp,
        strip_timestamps: profile.strip_timestamps,
    })
}

pub fn tcp_route_connect_settings(
    config: &RuntimeConfig,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> Option<TcpRouteConnectSettings> {
    tcp_route_connect_settings_with(&tcp_route_connect_settings_table(config), group_index, payload, allow_tfo)
}

pub fn route_requests_direct_syn_data_tfo(config: &RuntimeConfig, group_index: usize, payload: Option<&[u8]>) -> bool {
    selected_desync_group(config, group_index).is_some_and(|group| group_requests_direct_syn_data_tfo(group, payload))
}

pub fn connection_route_requests_direct_syn_data_tfo(
    config: &RuntimeConfig,
    route: &ConnectionRoute,
    payload: Option<&[u8]>,
) -> bool {
    connection_route_requests_direct_syn_data_tfo_with(&tcp_route_syn_data_settings(config), route, payload)
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;
    use std::time::Duration;

    use ripdpi_config::{DesyncGroup, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind, UpstreamSocksConfig};
    use ripdpi_runtime_decision_ports::ConnectionRoute;

    use super::*;

    #[test]
    fn direct_syn_data_tfo_requires_payload_and_direct_upstream() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));

        assert!(group_uses_direct_syn_data_tfo(&group));
        assert!(group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
        assert!(!group_requests_direct_syn_data_tfo(&group, None));
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(&[])));

        group.policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::from(([127, 0, 0, 1], 1080)) });
        assert!(!group_uses_direct_syn_data_tfo(&group));
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
    }

    #[test]
    fn connection_route_direct_syn_data_tfo_uses_route_group() {
        let mut direct = DesyncGroup::new(0);
        direct.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
        let plain = DesyncGroup::new(1);
        let config = RuntimeConfig { groups: vec![direct, plain], ..Default::default() };

        let direct_route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let plain_route = ConnectionRoute { group_index: 1, attempted_mask: 0 };

        assert!(
            connection_route_requests_direct_syn_data_tfo(&config, &direct_route, Some(b"GET / HTTP/1.1\r\n\r\n"),)
        );
        assert!(
            !connection_route_requests_direct_syn_data_tfo(&config, &plain_route, Some(b"GET / HTTP/1.1\r\n\r\n"),)
        );
    }

    #[test]
    fn projected_syn_data_settings_preserve_payload_and_route_group_policy() {
        let mut direct = DesyncGroup::new(0);
        direct.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
        let plain = DesyncGroup::new(1);
        let config = RuntimeConfig { groups: vec![direct, plain], ..Default::default() };
        let settings = tcp_route_syn_data_settings(&config);

        let direct_route = ConnectionRoute { group_index: 0, attempted_mask: 0 };
        let plain_route = ConnectionRoute { group_index: 1, attempted_mask: 0 };

        assert!(connection_route_requests_direct_syn_data_tfo_with(
            &settings,
            &direct_route,
            Some(b"GET / HTTP/1.1\r\n\r\n"),
        ));
        assert!(!connection_route_requests_direct_syn_data_tfo_with(&settings, &direct_route, Some(&[])));
        assert!(!connection_route_requests_direct_syn_data_tfo_with(
            &settings,
            &plain_route,
            Some(b"GET / HTTP/1.1\r\n\r\n"),
        ));
    }

    #[test]
    fn tcp_route_connect_settings_project_socket_context() {
        let mut config = RuntimeConfig::default();
        config.timeouts.connect_timeout_ms = 1500;
        config.process.protect_path = Some("/tmp/protect.sock".to_string());
        config.groups[0].actions.drop_sack = true;

        let settings = tcp_route_connect_settings(&config, 0, None, true).expect("connect settings");

        assert_eq!(settings.connect_timeout, Some(Duration::from_millis(1500)));
        assert_eq!(settings.protect_path.as_deref(), Some("/tmp/protect.sock"));
        assert!(settings.drop_sack);
    }

    #[test]
    fn tcp_route_connect_settings_table_preserves_tfo_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.network.tfo = false;
        let table = tcp_route_connect_settings_table(&config);

        let without_payload = tcp_route_connect_settings_with(&table, 0, None, true).expect("connect settings");
        let with_payload = tcp_route_connect_settings_with(&table, 0, Some(b"GET / HTTP/1.1\r\n\r\n"), true)
            .expect("connect settings");
        let tfo_disallowed = tcp_route_connect_settings_with(&table, 0, Some(b"GET / HTTP/1.1\r\n\r\n"), false)
            .expect("connect settings");

        assert!(!without_payload.tfo_enabled);
        assert!(with_payload.tfo_enabled);
        assert!(!tfo_disallowed.tfo_enabled);
        assert!(tcp_route_connect_settings_with(&table, 1, Some(b"x"), true).is_none());
    }
}
