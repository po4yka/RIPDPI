#[derive(Debug, Clone, Default)]
pub struct TrojanGoRelayConfig {
    pub server: String,
    pub port: i32,
    pub password: Option<String>,
    pub sni: Option<String>,
    pub ws_path: Option<String>,
    pub ws_host: Option<String>,
    pub mux: String,
    pub inner_cipher: String,
}
