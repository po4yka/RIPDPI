#[derive(Debug, Clone, Default)]
pub struct ChainRelayConfig {
    pub entry_server: String,
    pub entry_port: i32,
    pub entry_server_name: String,
    pub entry_public_key: String,
    pub entry_short_id: String,
    pub entry_profile_id: String,
    pub entry_uuid: Option<String>,
    pub exit_server: String,
    pub exit_port: i32,
    pub exit_server_name: String,
    pub exit_public_key: String,
    pub exit_short_id: String,
    pub exit_profile_id: String,
    pub exit_uuid: Option<String>,
}
