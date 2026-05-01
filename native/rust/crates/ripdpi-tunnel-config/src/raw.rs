use serde::Deserialize;

use crate::mapdns::MapDnsConfig;
use crate::misc::MiscConfig;
use crate::socks5::Socks5Config;
use crate::tunnel::TunnelConfig;

/// Private deserialization target.
#[derive(Deserialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) struct RawConfig {
    #[serde(default)]
    pub(crate) tunnel: TunnelConfig,
    pub(crate) socks5: Socks5Config,
    pub(crate) mapdns: Option<MapDnsConfig>,
    #[serde(default)]
    pub(crate) misc: MiscConfig,
}
