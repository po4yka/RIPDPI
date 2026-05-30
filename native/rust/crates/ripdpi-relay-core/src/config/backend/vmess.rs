#[derive(Debug, Clone, Default)]
pub struct VmessRelayConfig {
    pub server: String,
    pub port: i32,
    pub uuid: Option<String>,
    pub security: String,
    pub transport: String,
    pub ws_path: Option<String>,
    pub ws_host: Option<String>,
    pub grpc_service: Option<String>,
    pub h2_path: Option<String>,
    pub h2_host: Option<String>,
}
