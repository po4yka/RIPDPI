use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiTcpRotationCandidate {
    #[serde(default)]
    pub tcp_steps: Vec<super::steps::ProxyUiTcpChainStep>,
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
