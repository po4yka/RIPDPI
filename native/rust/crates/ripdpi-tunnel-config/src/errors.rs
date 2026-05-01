use thiserror::Error;

/// Errors that can occur when loading or validating a config file.
#[derive(Debug, Error)]
pub enum ConfigError {
    #[error(transparent)]
    Yaml(#[from] serde_yaml_ng::Error),
    #[error("socks5 credentials: username and password must both be present or both absent")]
    MismatchedCredentials,
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}
