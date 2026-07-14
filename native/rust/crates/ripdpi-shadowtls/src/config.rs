use std::fmt;

use serde::{Deserialize, Serialize};

#[derive(Clone, Deserialize, Serialize)]
pub struct Config {
    pub password: String,
    pub server_name: String,
    pub inner_profile_id: String,
    #[serde(skip, default)]
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
}

impl fmt::Debug for Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Config")
            .field("password", &"<redacted>")
            .field("server_name", &self.server_name)
            .field("inner_profile_id", &self.inner_profile_id)
            .field("socket_protection", &self.socket_protection)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_redacts_password() {
        let config = Config {
            password: "debug-shadowtls-secret".to_string(),
            server_name: "cover.example".to_string(),
            inner_profile_id: "chrome".to_string(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        };

        let rendered = format!("{config:?}");

        assert!(!rendered.contains("debug-shadowtls-secret"));
        assert!(rendered.contains("<redacted>"));
    }
}
