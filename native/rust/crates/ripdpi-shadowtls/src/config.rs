use std::fmt;

use serde::{Deserialize, Serialize};

#[derive(Clone, Deserialize, Serialize)]
pub struct Config {
    pub password: String,
    pub server_name: String,
    pub inner_profile_id: String,
    #[serde(skip, default)]
    pub socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
    /// Local address the carrier socket is bound to before connect (interface
    /// pinning). `None` binds to an unspecified address. Not part of the
    /// serialized profile: it is runtime routing state supplied by the relay
    /// builder, mirroring `socket_protection`.
    #[serde(skip, default)]
    pub outbound_bind_ip: Option<std::net::IpAddr>,
}

impl fmt::Debug for Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Config")
            .field("password", &"<redacted>")
            .field("server_name", &self.server_name)
            .field("inner_profile_id", &self.inner_profile_id)
            .field("socket_protection", &self.socket_protection)
            .field("outbound_bind_ip", &self.outbound_bind_ip)
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
            outbound_bind_ip: None,
        };

        let rendered = format!("{config:?}");

        assert!(!rendered.contains("debug-shadowtls-secret"));
        assert!(rendered.contains("<redacted>"));
    }
}
