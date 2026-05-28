#[derive(Debug, Clone, Default)]
pub struct TorRelayConfig {
    pub state_dir: String,
    pub cache_dir: String,
    pub bridge_lines: Vec<String>,
    pub transports: Vec<TorPluggableTransportConfig>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TorPluggableTransportConfig {
    pub protocols: Vec<String>,
    pub binary_path: String,
    pub arguments: Vec<String>,
    pub run_on_startup: bool,
}
