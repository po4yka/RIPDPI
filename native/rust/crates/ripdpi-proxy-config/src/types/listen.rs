use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiListenConfig {
    pub ip: String,
    pub port: i32,
    pub max_connections: i32,
    pub buffer_size: i32,
    #[serde(default)]
    pub tcp_fast_open: bool,
    pub default_ttl: i32,
    pub custom_ttl: bool,
    #[serde(default)]
    pub freeze_detection_enabled: bool,
    #[serde(default)]
    pub auth_token: Option<String>,
}

impl Default for ProxyUiListenConfig {
    fn default() -> Self {
        Self {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            tcp_fast_open: false,
            default_ttl: 0,
            custom_ttl: false,
            freeze_detection_enabled: false,
            auth_token: None,
        }
    }
}
