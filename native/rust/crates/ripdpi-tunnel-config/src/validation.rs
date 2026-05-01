use crate::errors::ConfigError;
use crate::raw::RawConfig;

pub(crate) fn validate(raw: &RawConfig) -> Result<(), ConfigError> {
    match (&raw.socks5.username, &raw.socks5.password) {
        (Some(_), None) | (None, Some(_)) => Err(ConfigError::MismatchedCredentials),
        _ => Ok(()),
    }
}
