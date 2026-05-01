use std::collections::HashMap;

use crate::error::{HysteriaError, Result};

#[derive(Debug, Clone)]
pub struct Config {
    pub auth: String,
    pub server_addr: String,
    pub server_name: String,
    pub insecure: bool,
    pub salamander_key: Option<String>,
    pub quic_bind_low_port: bool,
    pub quic_migrate_after_handshake: bool,
}

impl Config {
    pub fn from_url(url: &str) -> Result<Self> {
        let parsed = url::Url::parse(&url.replace("hysteria2://", "http://"))?;
        let host =
            parsed.host_str().ok_or_else(|| HysteriaError::InvalidAddress("missing hysteria host".to_string()))?;
        let port = parsed.port().ok_or_else(|| HysteriaError::InvalidAddress("missing hysteria port".to_string()))?;
        let query: HashMap<String, String> = parsed.query_pairs().into_owned().collect();

        Ok(Self {
            auth: parsed.username().to_string(),
            server_addr: format!("{host}:{port}"),
            server_name: query.get("sni").cloned().unwrap_or_else(|| host.to_string()),
            insecure: query.get("insecure").is_some_and(|value| value == "1"),
            salamander_key: query.get("obfs-password").cloned(),
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
        })
    }
}
