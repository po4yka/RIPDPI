#[derive(Debug, Clone, Default)]
pub struct VlessRelayConfig {
    pub vless_flow: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub uuid: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct VlessRealityRelayConfig {
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub vless_flow: String,
    pub vless_transport: String,
    pub xhttp_path: String,
    pub xhttp_host: String,
    pub uuid: Option<String>,
}
