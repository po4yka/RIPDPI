use crate::errors::ConfigError;
use crate::raw::{RawConfig, SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION};

pub(crate) fn validate(raw: &RawConfig) -> Result<(), ConfigError> {
    // Reject any payload carrying an unsupported `schemaVersion` envelope value.
    // Runs on every parse path (`from_str` / `from_file`) via `Config::from_raw`.
    if raw.schema_version != SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION {
        return Err(ConfigError::UnsupportedSchemaVersion { found: raw.schema_version });
    }
    match (&raw.socks5.username, &raw.socks5.password) {
        (Some(_), None) | (None, Some(_)) => Err(ConfigError::MismatchedCredentials),
        _ => Ok(()),
    }
}
