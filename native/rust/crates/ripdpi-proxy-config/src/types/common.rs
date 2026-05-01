use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiNumericRange {
    #[serde(default)]
    pub start: Option<i64>,
    #[serde(default)]
    pub end: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyUiActivationFilter {
    #[serde(default)]
    pub round: Option<ProxyUiNumericRange>,
    #[serde(default)]
    pub payload_size: Option<ProxyUiNumericRange>,
    #[serde(default)]
    pub stream_bytes: Option<ProxyUiNumericRange>,
    #[serde(default)]
    pub tcp_has_timestamp: Option<bool>,
    #[serde(default)]
    pub tcp_has_ech: Option<bool>,
    #[serde(default)]
    pub tcp_window_below: Option<u16>,
    #[serde(default)]
    pub tcp_mss_below: Option<u16>,
}

pub(super) fn default_true() -> bool {
    true
}
