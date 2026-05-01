use std::net::IpAddr;

use crate::{ConfigError, DesyncGroup, RuntimeConfig, StartupEnv, HOST_AUTOLEARN_DEFAULT_STORE_FILE};

use super::helpers::add_group;

pub(super) struct CliState {
    pub config: RuntimeConfig,
    pub all_limited: bool,
    pub current_group_index: usize,
}

impl CliState {
    pub(super) fn new(startup: &StartupEnv) -> Self {
        let mut config = RuntimeConfig::default();
        if let Some(port) = &startup.ss_local_port {
            if let Ok(port) = port.parse::<u16>() {
                config.network.listen.listen_port = port;
            } else {
                config.network.listen.listen_port = 0;
            }
            config.network.shadowsocks = true;
            if startup.protect_path_present {
                config.process.protect_path = Some("protect_path".to_owned());
            }
        }

        Self { config, all_limited: true, current_group_index: 0 }
    }

    pub(super) fn group(&mut self) -> &mut DesyncGroup {
        self.config.groups.get_mut(self.current_group_index).expect("current group exists")
    }

    pub(super) fn add_current_group(&mut self) -> Result<(), ConfigError> {
        add_group(&mut self.config.groups)?;
        self.current_group_index = self.config.groups.len() - 1;
        Ok(())
    }

    pub(super) fn finish(mut self) -> Result<RuntimeConfig, ConfigError> {
        if self.all_limited {
            add_group(&mut self.config.groups)?;
        }
        if !matches!(self.config.network.listen.bind_ip, IpAddr::V6(_)) {
            self.config.network.ipv6 = false;
        }
        if self.config.host_autolearn.enabled && self.config.host_autolearn.store_path.is_none() {
            self.config.host_autolearn.store_path = Some(HOST_AUTOLEARN_DEFAULT_STORE_FILE.to_owned());
        }
        Ok(self.config)
    }
}
