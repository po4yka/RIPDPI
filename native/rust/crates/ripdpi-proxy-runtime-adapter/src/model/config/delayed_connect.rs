use ripdpi_config::RuntimeConfig;

use super::runtime_buffer_size;

pub fn delayed_connect_enabled(config: &RuntimeConfig) -> bool {
    config.network.delay_conn
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DelayedConnectSettings {
    pub enabled: bool,
    pub buffer_size: usize,
}

pub fn delayed_connect_settings(config: &RuntimeConfig) -> DelayedConnectSettings {
    DelayedConnectSettings { enabled: delayed_connect_enabled(config), buffer_size: runtime_buffer_size(config) }
}

#[cfg(test)]
mod tests {
    use ripdpi_config::RuntimeConfig;

    use super::*;

    #[test]
    fn delayed_connect_settings_project_enablement_and_buffer_size() {
        let mut config = RuntimeConfig::default();
        config.network.delay_conn = true;
        config.network.buffer_size = 512;

        assert_eq!(delayed_connect_settings(&config), DelayedConnectSettings { enabled: true, buffer_size: 16_384 },);
    }
}
