pub(crate) use super::*;
#[derive(Clone, Default)]
pub struct TorRelayConfig {
    pub state_dir: String,
    pub cache_dir: String,
    pub bridge_lines: Vec<String>,
    pub transports: Vec<TorPluggableTransportConfig>,
}

impl_redacted_debug!(TorRelayConfig { state_dir, cache_dir, transports });

#[derive(Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TorPluggableTransportConfig {
    #[serde(default)]
    pub protocols: Vec<String>,
    pub binary_path: String,
    #[serde(default)]
    pub arguments: Vec<String>,
    #[serde(default)]
    pub run_on_startup: bool,
}

impl_redacted_debug!(TorPluggableTransportConfig { protocols, binary_path, run_on_startup });
