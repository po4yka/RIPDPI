use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_config::{IpIdMode, RuntimeConfig, UdpChainStepKind};

use super::shared::connect_timeout;

pub fn udp_source_rebind_policy(config: &RuntimeConfig, group_index: usize) -> UdpSourceRebindPolicy {
    UdpSourceRebindPolicy {
        after_handshake: config.groups.get(group_index).is_some_and(|group| group.actions.quic_migrate_after_handshake),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UdpSourceRebindPolicy {
    pub after_handshake: bool,
}

pub fn should_rebind_udp_source_port_with(
    policy: UdpSourceRebindPolicy,
    quic_migrated: bool,
    round_count: u32,
    inbound_payload: &[u8],
) -> bool {
    !quic_migrated
        && inbound_payload.first().is_some_and(|first| first & 0x80 == 0)
        && round_count >= 2
        && policy.after_handshake
}

pub fn should_rebind_udp_source_port(
    config: &RuntimeConfig,
    group_index: usize,
    quic_migrated: bool,
    round_count: u32,
    inbound_payload: &[u8],
) -> bool {
    should_rebind_udp_source_port_with(
        udp_source_rebind_policy(config, group_index),
        quic_migrated,
        round_count,
        inbound_payload,
    )
}

#[derive(Clone, Copy)]
pub struct UdpGroupSocketSettings {
    pub bind_low_port: bool,
}

pub fn udp_group_socket_settings(config: &RuntimeConfig, group_index: usize) -> UdpGroupSocketSettings {
    UdpGroupSocketSettings { bind_low_port: udp_bind_low_port(config, group_index) }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UdpGroupPacketSettings {
    pub default_ttl: u8,
    pub ip_id_mode: Option<IpIdMode>,
}

pub fn udp_group_packet_settings(config: &RuntimeConfig, group_index: usize) -> UdpGroupPacketSettings {
    UdpGroupPacketSettings { default_ttl: udp_default_ttl(config), ip_id_mode: udp_ip_id_mode(config, group_index) }
}

/// The single upstream SOCKS5 server a UDP flow on this group must traverse via
/// UDP ASSOCIATE, mirroring the TCP CONNECT projection
/// (`tcp_connect::TcpRouteConnectProfile::upstream_socks_addr`). `None` means
/// the flow egresses directly to its target.
pub fn udp_upstream_socks_addr(config: &RuntimeConfig, group_index: usize) -> Option<SocketAddr> {
    config.groups.get(group_index).and_then(|group| group.policy.ext_socks.map(|upstream| upstream.addr))
}

#[derive(Clone, Copy)]
pub struct UdpGroupSettings {
    pub socket: UdpGroupSocketSettings,
    pub packet: UdpGroupPacketSettings,
    pub source_rebind: UdpSourceRebindPolicy,
    pub execution_family: Option<&'static str>,
    pub upstream_socks_addr: Option<SocketAddr>,
    /// Control-TCP connect timeout for the upstream SOCKS5 UDP ASSOCIATE
    /// handshake, sourced from the same `connect_timeout` projection the TCP
    /// CONNECT path uses. Unused when `upstream_socks_addr` is `None`.
    pub connect_timeout: Option<Duration>,
}

#[derive(Clone)]
pub struct UdpGroupSettingsTable {
    groups: Vec<UdpGroupSettings>,
}

pub fn udp_group_settings_table(config: &RuntimeConfig) -> UdpGroupSettingsTable {
    UdpGroupSettingsTable {
        groups: config
            .groups
            .iter()
            .enumerate()
            .map(|(group_index, _)| UdpGroupSettings {
                socket: udp_group_socket_settings(config, group_index),
                packet: udp_group_packet_settings(config, group_index),
                source_rebind: udp_source_rebind_policy(config, group_index),
                execution_family: udp_primary_strategy_family(config, group_index),
                upstream_socks_addr: udp_upstream_socks_addr(config, group_index),
                connect_timeout: connect_timeout(config),
            })
            .collect(),
    }
}

pub fn udp_group_settings_with(table: &UdpGroupSettingsTable, group_index: usize) -> Option<UdpGroupSettings> {
    table.groups.get(group_index).copied()
}

pub fn udp_bind_low_port(config: &RuntimeConfig, group_index: usize) -> bool {
    config.groups.get(group_index).is_some_and(|group| group.actions.quic_bind_low_port)
}

pub fn udp_ip_id_mode(config: &RuntimeConfig, group_index: usize) -> Option<IpIdMode> {
    config.groups.get(group_index).and_then(|group| group.actions.ip_id_mode)
}

pub fn udp_primary_strategy_family(config: &RuntimeConfig, group_index: usize) -> Option<&'static str> {
    let group = config.groups.get(group_index)?;
    group.effective_udp_chain().into_iter().next().and_then(|step| match step.kind {
        UdpChainStepKind::FakeBurst => Some("quic_burst"),
        UdpChainStepKind::DummyPrepend => Some("quic_dummy_prepend"),
        UdpChainStepKind::QuicSniSplit => Some("quic_sni_split"),
        UdpChainStepKind::QuicFakeVersion => Some("quic_fake_version"),
        UdpChainStepKind::QuicCryptoSplit => Some("quic_crypto_split"),
        UdpChainStepKind::QuicPaddingLadder => Some("quic_padding_ladder"),
        UdpChainStepKind::QuicVersionNegotiationDecoy => Some("quic_version_negotiation_decoy"),
        UdpChainStepKind::QuicMultiInitialRealistic => Some("quic_multi_initial_realistic"),
        UdpChainStepKind::IpFrag2Udp => Some("quic_ipfrag2"),
        UdpChainStepKind::QuicCidChurn | UdpChainStepKind::QuicPacketNumberGap => Some("quic_burst"),
        _ => None,
    })
}

pub fn udp_default_ttl(config: &RuntimeConfig) -> u8 {
    config.network.default_ttl
}

pub fn ensure_default_ttl(
    config: &mut RuntimeConfig,
    detect_default_ttl: impl FnOnce() -> io::Result<u8>,
) -> io::Result<()> {
    if config.network.default_ttl == 0 {
        config.network.default_ttl = detect_default_ttl()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use ripdpi_config::{DesyncGroup, IpIdMode, RuntimeConfig};

    use super::*;

    #[test]
    fn udp_group_socket_settings_project_bind_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_bind_low_port = true;
        let config = RuntimeConfig { groups: vec![group], ..Default::default() };

        assert!(udp_group_socket_settings(&config, 0).bind_low_port);
        assert!(!udp_group_socket_settings(&config, 1).bind_low_port);
    }

    #[test]
    fn udp_group_settings_table_preserves_udp_group_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_bind_low_port = true;
        group.actions.quic_migrate_after_handshake = true;
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.network.default_ttl = 42;

        let table = udp_group_settings_table(&config);
        let settings = udp_group_settings_with(&table, 0).expect("udp group settings");

        assert!(settings.socket.bind_low_port);
        assert_eq!(settings.packet.default_ttl, 42);
        assert!(settings.source_rebind.after_handshake);
        assert!(udp_group_settings_with(&table, 1).is_none());
    }

    #[test]
    fn udp_source_rebind_policy_projects_quic_migration_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.quic_migrate_after_handshake = true;
        let config = RuntimeConfig { groups: vec![group], ..Default::default() };

        assert_eq!(udp_source_rebind_policy(&config, 0), UdpSourceRebindPolicy { after_handshake: true });
        assert_eq!(udp_source_rebind_policy(&config, 1), UdpSourceRebindPolicy { after_handshake: false });
    }

    #[test]
    fn udp_source_rebind_policy_waits_for_short_header_after_two_rounds() {
        let policy = UdpSourceRebindPolicy { after_handshake: true };

        assert!(!should_rebind_udp_source_port_with(policy, true, 2, &[0x40]));
        assert!(!should_rebind_udp_source_port_with(policy, false, 1, &[0x40]));
        assert!(!should_rebind_udp_source_port_with(policy, false, 2, &[0xc0]));
        assert!(!should_rebind_udp_source_port_with(
            UdpSourceRebindPolicy { after_handshake: false },
            false,
            2,
            &[0x40],
        ));
        assert!(should_rebind_udp_source_port_with(policy, false, 2, &[0x40]));
    }

    #[test]
    fn udp_group_packet_settings_project_ttl_and_ip_id_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.ip_id_mode = Some(IpIdMode::Seq);
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.network.default_ttl = 42;

        assert_eq!(
            udp_group_packet_settings(&config, 0),
            UdpGroupPacketSettings { default_ttl: 42, ip_id_mode: Some(IpIdMode::Seq) },
        );
        assert_eq!(udp_group_packet_settings(&config, 1), UdpGroupPacketSettings { default_ttl: 42, ip_id_mode: None });
    }
}
