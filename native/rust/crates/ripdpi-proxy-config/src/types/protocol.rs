use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiProtocolConfig {
    #[serde(default = "super::common::default_true")]
    pub resolve_domains: bool,
    pub desync_http: bool,
    pub desync_https: bool,
    pub desync_udp: bool,
    /// Master switch for SOCKS5 UDP ASSOCIATE. `None` (wire key absent) means
    /// enabled, preserving the historical always-on behaviour; `Some(false)`
    /// disables UDP ASSOCIATE end-to-end (`RuntimeConfig::network::udp`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub udp_associate_enabled: Option<bool>,
}

impl Default for ProxyUiProtocolConfig {
    fn default() -> Self {
        Self {
            resolve_domains: true,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            udp_associate_enabled: None,
        }
    }
}
