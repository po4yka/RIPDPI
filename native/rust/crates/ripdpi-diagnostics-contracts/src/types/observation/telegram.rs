use serde::{Deserialize, Serialize};

use super::defaults::{telegram_transfer_status_error, telegram_ws_tunnel_status_unknown};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramVerdict {
    Ok,
    Slow,
    Partial,
    Blocked,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramTransferStatus {
    Ok,
    Slow,
    Stalled,
    Blocked,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TelegramWsTunnelStatus {
    Ok,
    Unreachable,
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TelegramObservationFact {
    pub verdict: TelegramVerdict,
    #[serde(default)]
    pub quality_score: i32,
    #[serde(default = "telegram_transfer_status_error")]
    pub download_status: TelegramTransferStatus,
    #[serde(default = "telegram_transfer_status_error")]
    pub upload_status: TelegramTransferStatus,
    #[serde(default)]
    pub dc_reachable: usize,
    #[serde(default)]
    pub dc_total: usize,
    #[serde(default)]
    pub download_avg_bps: u64,
    #[serde(default)]
    pub download_peak_bps: u64,
    #[serde(default)]
    pub upload_avg_bps: u64,
    #[serde(default)]
    pub upload_peak_bps: u64,
    #[serde(default)]
    pub dc_results: Vec<String>,
    #[serde(default = "telegram_ws_tunnel_status_unknown")]
    pub ws_tunnel_status: TelegramWsTunnelStatus,
    #[serde(default)]
    pub ws_tunnel_rtt_ms: u64,
    #[serde(default)]
    pub ws_tunnel_error: Option<String>,
}
