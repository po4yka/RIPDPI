use std::fs;
use std::str::FromStr;

use crate::errors::ConfigError;
use crate::mapdns::MapDnsConfig;
use crate::misc::MiscConfig;
use crate::raw::RawConfig;
use crate::socks5::Socks5Config;
use crate::tunnel::TunnelConfig;
use crate::validation;

/// Top-level tunnel configuration.
#[derive(Debug, Clone)]
pub struct Config {
    pub tunnel: TunnelConfig,
    pub socks5: Socks5Config,
    pub mapdns: Option<MapDnsConfig>,
    pub misc: MiscConfig,
}

impl Config {
    /// Read and parse configuration from a file path.
    pub fn from_file(path: &str) -> Result<Self, ConfigError> {
        let content = fs::read_to_string(path)?;
        content.parse()
    }

    fn from_raw(raw: RawConfig) -> Result<Self, ConfigError> {
        validation::validate_envelope(&raw)?;

        let config = Self { tunnel: raw.tunnel, socks5: raw.socks5, mapdns: raw.mapdns, misc: raw.misc };
        config.validate()?;
        Ok(config)
    }

    /// Validate values before the runtime adopts file descriptors or allocates buffers.
    pub fn validate(&self) -> Result<(), ConfigError> {
        validation::validate_values(self)
    }
}

impl FromStr for Config {
    type Err = ConfigError;

    fn from_str(yaml: &str) -> Result<Self, Self::Err> {
        let raw = serde_yaml_ng::from_str(yaml)?;
        Self::from_raw(raw)
    }
}
