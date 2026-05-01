use serde::{Deserialize, Serialize};

use super::super::common::ProxyUiActivationFilter;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiTcpChainStep {
    pub kind: String,
    pub marker: String,
    #[serde(default)]
    pub midhost_marker: String,
    #[serde(default)]
    pub fake_host_template: String,
    #[serde(default)]
    pub fake_order: String,
    #[serde(default)]
    pub fake_seq_mode: String,
    #[serde(default)]
    pub tcp_flags_set: String,
    #[serde(default)]
    pub tcp_flags_unset: String,
    #[serde(default)]
    pub tcp_flags_orig_set: String,
    #[serde(default)]
    pub tcp_flags_orig_unset: String,
    #[serde(default)]
    pub overlap_size: i32,
    #[serde(default = "super::defaults::default_seqovl_fake_mode")]
    pub fake_mode: String,
    #[serde(default)]
    pub fragment_count: i32,
    #[serde(default)]
    pub min_fragment_size: i32,
    #[serde(default)]
    pub max_fragment_size: i32,
    #[serde(default)]
    pub inter_segment_delay_ms: u32,
    #[serde(default)]
    pub activation_filter: Option<ProxyUiActivationFilter>,
    #[serde(default = "super::defaults::default_ipv6_extension_profile")]
    pub ipv6_extension_profile: String,
    #[serde(default)]
    pub random_fake_host: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiUdpChainStep {
    pub kind: String,
    pub count: i32,
    #[serde(default)]
    pub split_bytes: i32,
    #[serde(default)]
    pub activation_filter: Option<ProxyUiActivationFilter>,
    #[serde(default = "super::defaults::default_ipv6_extension_profile")]
    pub ipv6_extension_profile: String,
}
