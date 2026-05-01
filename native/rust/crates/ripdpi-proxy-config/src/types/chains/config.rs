use serde::{Deserialize, Serialize};

use super::super::common::ProxyUiActivationFilter;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiChainConfig {
    #[serde(default = "super::defaults::default_tcp_chain_steps")]
    pub tcp_steps: Vec<super::steps::ProxyUiTcpChainStep>,
    #[serde(default)]
    pub tcp_rotation: Option<super::rotation::ProxyUiTcpRotationConfig>,
    #[serde(default)]
    pub udp_steps: Vec<super::steps::ProxyUiUdpChainStep>,
    #[serde(default)]
    pub group_activation_filter: Option<ProxyUiActivationFilter>,
    #[serde(default)]
    pub any_protocol: bool,
    #[serde(default)]
    pub payload_disable: Vec<String>,
}

impl Default for ProxyUiChainConfig {
    fn default() -> Self {
        Self {
            tcp_steps: super::defaults::default_tcp_chain_steps(),
            tcp_rotation: None,
            udp_steps: Vec::new(),
            group_activation_filter: None,
            any_protocol: false,
            payload_disable: Vec::new(),
        }
    }
}
