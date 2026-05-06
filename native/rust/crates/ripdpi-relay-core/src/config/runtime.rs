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
        Ok(FlatResolvedRelayRuntimeConfig::deserialize(deserializer)?.into())
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

    pub(crate) fn xhttp_path(&self) -> &str {
        match &self.backend {
            RelayBackendConfig::VlessReality(config) => &config.xhttp_path,
            RelayBackendConfig::CloudflareTunnel(config) => &config.xhttp_path,
            _ => "",
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommonRelayConfig {
    pub enabled: bool,
    pub profile_id: String,
    pub outbound_bind_ip: String,
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
