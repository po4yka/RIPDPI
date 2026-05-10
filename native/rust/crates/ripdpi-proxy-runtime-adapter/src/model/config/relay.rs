use std::io;

use ripdpi_config::{DesyncGroup, RotationPolicy, RuntimeConfig, RuntimeTimeoutSettings};

use super::selected_desync_group;

pub fn relay_timeout_settings(config: &RuntimeConfig) -> RuntimeTimeoutSettings {
    config.timeouts
}

pub fn group_drop_sack_enabled(config: &RuntimeConfig, group_index: usize) -> Option<bool> {
    selected_desync_group(config, group_index).map(|group| group.actions.drop_sack)
}

pub fn group_rotation_policy_enabled(config: &RuntimeConfig, group_index: usize) -> bool {
    selected_desync_group(config, group_index).is_some_and(|group| group.actions.rotation_policy.is_some())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RelayGroupSettings {
    pub drop_sack: bool,
    pub rotation_enabled: bool,
    pub timeouts: RuntimeTimeoutSettings,
}

pub fn relay_group_settings(config: &RuntimeConfig, group_index: usize) -> Option<RelayGroupSettings> {
    let group = selected_desync_group(config, group_index)?;
    Some(RelayGroupSettings {
        drop_sack: group.actions.drop_sack,
        rotation_enabled: group.actions.rotation_policy.is_some(),
        timeouts: relay_timeout_settings(config),
    })
}

#[derive(Clone)]
pub struct RelayGroupSettingsTable {
    groups: Vec<RelayGroupSettings>,
    rotation_seeds: Vec<Option<(DesyncGroup, RotationPolicy)>>,
    primary_strategy_families: Vec<Option<&'static str>>,
}

pub fn relay_group_settings_table(config: &RuntimeConfig) -> RelayGroupSettingsTable {
    RelayGroupSettingsTable {
        groups: config
            .groups
            .iter()
            .map(|group| RelayGroupSettings {
                drop_sack: group.actions.drop_sack,
                rotation_enabled: group.actions.rotation_policy.is_some(),
                timeouts: relay_timeout_settings(config),
            })
            .collect(),
        rotation_seeds: config
            .groups
            .iter()
            .map(|group| group.actions.rotation_policy.clone().map(|policy| (group.clone(), policy)))
            .collect(),
        primary_strategy_families: config
            .groups
            .iter()
            .map(ripdpi_desync_runtime::primary_tcp_strategy_family)
            .collect(),
    }
}

pub fn relay_group_settings_with(table: &RelayGroupSettingsTable, group_index: usize) -> Option<RelayGroupSettings> {
    table.groups.get(group_index).copied()
}

pub fn tcp_rotation_seed_with(
    table: &RelayGroupSettingsTable,
    group_index: usize,
) -> io::Result<Option<(DesyncGroup, RotationPolicy)>> {
    table
        .rotation_seeds
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))
}

pub fn primary_tcp_strategy_family_with(table: &RelayGroupSettingsTable, group_index: usize) -> Option<&'static str> {
    table.primary_strategy_families.get(group_index).copied().flatten()
}

pub fn tcp_rotation_seed(
    config: &RuntimeConfig,
    group_index: usize,
) -> io::Result<Option<(DesyncGroup, RotationPolicy)>> {
    tcp_rotation_seed_with(&relay_group_settings_table(config), group_index)
}

pub fn primary_tcp_strategy_family_for_group(config: &RuntimeConfig, group_index: usize) -> Option<&'static str> {
    primary_tcp_strategy_family_with(&relay_group_settings_table(config), group_index)
}

#[cfg(test)]
mod tests {
    use ripdpi_config::{DesyncGroup, RotationPolicy, RuntimeConfig};

    use super::*;

    #[test]
    fn relay_group_settings_project_drop_sack_rotation_and_timeouts() {
        let mut group = DesyncGroup::new(0);
        group.actions.drop_sack = true;
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.timeouts.freeze_max_stalls = 7;

        let settings = relay_group_settings(&config, 0).expect("relay group settings");

        assert!(settings.drop_sack);
        assert!(!settings.rotation_enabled);
        assert_eq!(settings.timeouts.freeze_max_stalls, 7);
        assert!(relay_group_settings(&config, 1).is_none());
    }

    #[test]
    fn relay_group_settings_table_preserves_group_and_rotation_policy() {
        let mut group = DesyncGroup::new(0);
        group.actions.drop_sack = true;
        group.actions.rotation_policy = Some(RotationPolicy::default());
        let config = RuntimeConfig { groups: vec![group], ..Default::default() };
        let table = relay_group_settings_table(&config);

        let settings = relay_group_settings_with(&table, 0).expect("relay group settings");
        let seed = tcp_rotation_seed_with(&table, 0).expect("rotation lookup");

        assert!(settings.drop_sack);
        assert!(settings.rotation_enabled);
        assert!(seed.is_some());
        assert_eq!(primary_tcp_strategy_family_with(&table, 0), primary_tcp_strategy_family_for_group(&config, 0));
        assert!(relay_group_settings_with(&table, 1).is_none());
        assert!(tcp_rotation_seed_with(&table, 1).is_err());
        assert!(primary_tcp_strategy_family_with(&table, 1).is_none());
    }
}
