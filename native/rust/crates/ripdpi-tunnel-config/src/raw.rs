use serde::Deserialize;

use crate::mapdns::MapDnsConfig;
use crate::misc::MiscConfig;
use crate::socks5::Socks5Config;
use crate::tunnel::TunnelConfig;

/// The single native-config wire schema version this build understands.
///
/// Mirrors the Kotlin `Tun2SocksConfigSchemaVersion` constant. A payload
/// carrying any other version is rejected during validation.
pub(crate) const SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION: u32 = 1;

/// `serde(default)` provider for the additive `schemaVersion` envelope field.
///
/// A legacy payload with no `schemaVersion` key is treated as version 1, which
/// matches the Kotlin encoder: at version 1 the field is `@EncodeDefault(NEVER)`
/// and never reaches the wire.
pub(crate) fn default_native_config_schema_version() -> u32 {
    SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION
}

/// Private deserialization target.
#[derive(Deserialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) struct RawConfig {
    // The wire key is camelCase `schemaVersion` even though the rest of this
    // struct is kebab-case -- it mirrors the Kotlin `Tun2SocksConfig` field.
    #[serde(rename = "schemaVersion", default = "default_native_config_schema_version")]
    pub(crate) schema_version: u32,
    #[serde(default)]
    pub(crate) tunnel: TunnelConfig,
    pub(crate) socks5: Socks5Config,
    pub(crate) mapdns: Option<MapDnsConfig>,
    #[serde(default)]
    pub(crate) misc: MiscConfig,
}
