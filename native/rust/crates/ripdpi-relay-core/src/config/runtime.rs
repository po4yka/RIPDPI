pub(crate) use super::*;
#[derive(Debug, Clone)]
pub struct ResolvedRelayRuntimeConfig {
    pub common: CommonRelayConfig,
    pub backend: RelayBackendConfig,
}

impl<'de> Deserialize<'de> for ResolvedRelayRuntimeConfig {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let flat = FlatResolvedRelayRuntimeConfig::deserialize(deserializer)?;
        // Reject any payload carrying an unsupported `schemaVersion` envelope
        // value. Runs on every deserialize path (`from_str` / `from_value` /
        // `from_reader`) because they all funnel through this impl.
        validate_schema_version(flat.schema_version).map_err(serde::de::Error::custom)?;
        validate_required_relay_identity(&flat).map_err(serde::de::Error::custom)?;
        Ok(flat.into())
    }
}

impl Serialize for ResolvedRelayRuntimeConfig {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        FlatResolvedRelayRuntimeConfig::from(self).serialize(serializer)
    }
}

impl ResolvedRelayRuntimeConfig {
    pub(crate) fn kind_id(&self) -> &str {
        self.backend.kind_id()
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommonRelayConfig {
    pub enabled: bool,
    pub profile_id: String,
    pub outbound_bind_ip: String,
    pub socket_protection: SocketProtection,
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub udp_enabled: bool,
    pub tcp_fallback_enabled: bool,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
    pub tls_fingerprint_profile: String,
    #[serde(default)]
    pub finalmask: ResolvedRelayFinalmaskConfig,
}

/// Wire representation of the runtime-owned socket-protection policy.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SocketProtection {
    #[default]
    Inactive,
    VpnRequired,
}

impl From<SocketProtection> for ripdpi_relay_tls_transports::SocketProtectionPolicy {
    fn from(value: SocketProtection) -> Self {
        match value {
            SocketProtection::Inactive => Self::Inactive,
            SocketProtection::VpnRequired => Self::VpnRequired,
        }
    }
}
