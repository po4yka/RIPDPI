use std::time::Duration;

use ripdpi_config::RuntimeConfig;

pub fn protect_path(config: &RuntimeConfig) -> Option<&str> {
    config.process.protect_path.as_deref()
}

pub fn protect_path_owned(config: &RuntimeConfig) -> Option<String> {
    config.process.protect_path.clone()
}

pub fn runtime_buffer_size(config: &RuntimeConfig) -> usize {
    config.network.buffer_size.max(16_384)
}

pub fn connect_timeout(config: &RuntimeConfig) -> Option<Duration> {
    (config.timeouts.connect_timeout_ms > 0).then(|| Duration::from_millis(config.timeouts.connect_timeout_ms as u64))
}
