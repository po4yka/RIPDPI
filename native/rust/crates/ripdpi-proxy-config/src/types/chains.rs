use serde::{Deserialize, Serialize};

use super::constants::SEQOVL_FAKE_MODE_PROFILE;

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
    #[serde(default = "default_seqovl_fake_mode")]
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
    pub activation_filter: Option<super::common::ProxyUiActivationFilter>,
    #[serde(default = "default_ipv6_extension_profile")]
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
    pub activation_filter: Option<super::common::ProxyUiActivationFilter>,
    #[serde(default = "default_ipv6_extension_profile")]
    pub ipv6_extension_profile: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiTcpRotationCandidate {
    #[serde(default)]
    pub tcp_steps: Vec<ProxyUiTcpChainStep>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiTcpRotationConfig {
    #[serde(default = "default_rotation_fail_threshold")]
    pub fails: usize,
    #[serde(default = "default_rotation_retrans_threshold")]
    pub retrans: u32,
    #[serde(default = "default_rotation_seq_threshold")]
    pub seq: u32,
    #[serde(default = "default_rotation_rst_threshold")]
    pub rst: u32,
    #[serde(default = "default_rotation_time_secs")]
    pub time_secs: u64,
    #[serde(default)]
    pub candidates: Vec<ProxyUiTcpRotationCandidate>,
    #[serde(default)]
    pub cancel_on_failure: Option<bool>,
}

impl Default for ProxyUiTcpRotationConfig {
    fn default() -> Self {
        Self {
            fails: default_rotation_fail_threshold(),
            retrans: default_rotation_retrans_threshold(),
            seq: default_rotation_seq_threshold(),
            rst: default_rotation_rst_threshold(),
            time_secs: default_rotation_time_secs(),
            candidates: Vec::new(),
            cancel_on_failure: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiChainConfig {
    #[serde(default = "default_tcp_chain_steps")]
    pub tcp_steps: Vec<ProxyUiTcpChainStep>,
    #[serde(default)]
    pub tcp_rotation: Option<ProxyUiTcpRotationConfig>,
    #[serde(default)]
    pub udp_steps: Vec<ProxyUiUdpChainStep>,
    #[serde(default)]
    pub group_activation_filter: Option<super::common::ProxyUiActivationFilter>,
    #[serde(default)]
    pub any_protocol: bool,
    #[serde(default)]
    pub payload_disable: Vec<String>,
}

impl Default for ProxyUiChainConfig {
    fn default() -> Self {
        Self {
            tcp_steps: default_tcp_chain_steps(),
            tcp_rotation: None,
            udp_steps: Vec::new(),
            group_activation_filter: None,
            any_protocol: false,
            payload_disable: Vec::new(),
        }
    }
}

fn default_rotation_fail_threshold() -> usize {
    3
}

fn default_rotation_retrans_threshold() -> u32 {
    3
}

fn default_rotation_seq_threshold() -> u32 {
    65_536
}

fn default_rotation_rst_threshold() -> u32 {
    1
}

fn default_rotation_time_secs() -> u64 {
    60
}

fn default_tcp_chain_steps() -> Vec<ProxyUiTcpChainStep> {
    vec![
        ProxyUiTcpChainStep {
            kind: "tlsrec".to_string(),
            marker: "extlen".to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 0,
            fake_mode: String::new(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: default_ipv6_extension_profile(),
            random_fake_host: false,
        },
        ProxyUiTcpChainStep {
            kind: "fake".to_string(),
            marker: "host+1".to_string(),
            midhost_marker: String::new(),
            fake_host_template: String::new(),
            fake_order: String::new(),
            fake_seq_mode: String::new(),
            tcp_flags_set: String::new(),
            tcp_flags_unset: String::new(),
            tcp_flags_orig_set: String::new(),
            tcp_flags_orig_unset: String::new(),
            overlap_size: 0,
            fake_mode: default_seqovl_fake_mode(),
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            activation_filter: None,
            ipv6_extension_profile: default_ipv6_extension_profile(),
            random_fake_host: false,
        },
    ]
}

fn default_seqovl_fake_mode() -> String {
    SEQOVL_FAKE_MODE_PROFILE.to_string()
}

fn default_ipv6_extension_profile() -> String {
    "none".to_string()
}
