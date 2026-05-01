use serde::{Deserialize, Serialize};

use super::constants::QUIC_FAKE_PROFILE_DISABLED;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiQuicConfig {
    #[serde(default = "default_quic_initial_mode")]
    pub initial_mode: String,
    #[serde(default = "super::common::default_true")]
    pub support_v1: bool,
    #[serde(default = "super::common::default_true")]
    pub support_v2: bool,
    #[serde(default = "default_quic_fake_profile")]
    pub fake_profile: String,
    #[serde(default)]
    pub fake_host: String,
}

impl Default for ProxyUiQuicConfig {
    fn default() -> Self {
        Self {
            initial_mode: default_quic_initial_mode(),
            support_v1: true,
            support_v2: true,
            fake_profile: default_quic_fake_profile(),
            fake_host: String::new(),
        }
    }
}

fn default_quic_initial_mode() -> String {
    "route_and_cache".to_string()
}

fn default_quic_fake_profile() -> String {
    QUIC_FAKE_PROFILE_DISABLED.to_string()
}
