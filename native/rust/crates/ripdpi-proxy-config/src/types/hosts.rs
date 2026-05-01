use serde::{Deserialize, Serialize};

use super::constants::HOSTS_DISABLE;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiHostsConfig {
    pub mode: String,
    #[serde(default)]
    pub entries: Option<String>,
}

impl Default for ProxyUiHostsConfig {
    fn default() -> Self {
        Self { mode: HOSTS_DISABLE.to_string(), entries: None }
    }
}
