use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    pub password: String,
    pub server_name: String,
    pub inner_profile_id: String,
}

#[derive(Debug, Clone)]
pub struct ShadowTlsClient {
    config: Config,
}

impl ShadowTlsClient {
    pub fn new(config: Config) -> Self {
        Self { config }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }
}
