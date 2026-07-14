use thiserror::Error;

use crate::raw::SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION;

/// Errors that can occur when loading or validating a config file.
#[derive(Debug, Error)]
pub enum ConfigError {
    #[error(transparent)]
    Yaml(#[from] serde_yaml_ng::Error),
    #[error("socks5 credentials: username and password must both be present or both absent")]
    MismatchedCredentials,
    #[error("invalid {field}: {message}")]
    InvalidValue { field: &'static str, message: String },
    #[error(
        "unsupported native config schemaVersion {found}; \
         this build supports {SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION}"
    )]
    UnsupportedSchemaVersion { found: u32 },
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}
