#[derive(Debug, Clone, Default)]
pub struct HysteriaV1RelayConfig {
    pub server: String,
    pub port: i32,
    pub auth_type: String,
    pub auth_payload: Option<String>,
    pub obfuscation: Option<String>,
    pub protocol: String,
    pub up_mbps: i32,
    pub down_mbps: i32,
    pub sni: Option<String>,
    pub alpn: Option<String>,
}
